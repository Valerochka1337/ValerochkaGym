package com.valerochka1337.valerochkagym.service.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.tasks.OnSuccessListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адаптер официального Xiaomi Wear SDK из Vela interconnect demo.
 *
 * SDK работает поверх Mi Fitness: сначала ждём сервис Mi Fitness, затем находим сопряжённый node,
 * запрашиваем DEVICE_MANAGER и только после этого регистрируем слушатель сообщений. Все переходы
 * состояния сериализованы на main looper: callback'и Binder SDK могут приходить с разных потоков.
 */
@Singleton
class XiaomiWearTransport
@Inject
constructor(
    @ApplicationContext context: Context,
) : WearableTransport {

  private val mainHandler = Handler(Looper.getMainLooper())
  private val nodeApi = Wearable.getNodeApi(context)
  private val authApi = Wearable.getAuthApi(context)
  private val messageApi = Wearable.getMessageApi(context)
  private val serviceApi = Wearable.getServiceApi(context)

  private var started = false
  private var discoveryInFlight = false
  private var permissionCheckInFlight = false
  private var permissionRequestInFlight = false
  private var listenerRegistrationInFlight = false
  private var listenerRegistered = false
  private var permissionRequestedNodeId: String? = null
  private var nodeId: String? = null
  private var onConnected: (() -> Unit)? = null
  private var onMessage: ((ByteArray) -> Unit)? = null

  private val discoveryRunnable =
      object : Runnable {
        override fun run() {
          discoverNode()
          if (started && !listenerRegistered) {
            mainHandler.postDelayed(this, DISCOVERY_RETRY_MS)
          }
        }
      }

  private val messageListener =
      OnMessageReceivedListener { nodeId, message ->
          val copy = message.copyOf()
          mainHandler.post {
              if (started && nodeId == this@XiaomiWearTransport.nodeId) {
                  onMessage?.invoke(copy)
              }
          }
      }

  private val serviceConnectionListener =
      object : OnServiceConnectionListener {
        override fun onServiceConnected() {
          mainHandler.post(::restartDiscovery)
        }

        override fun onServiceDisconnected() {
          mainHandler.post {
            detachNode()
            restartDiscovery()
          }
        }
      }

  override fun start(
      onConnected: () -> Unit,
      onMessage: (ByteArray) -> Unit,
  ) {
    mainHandler.post {
      this.onConnected = onConnected
      this.onMessage = onMessage

      if (started) {
        if (listenerRegistered) onConnected()
        return@post
      }

      started = true
      serviceApi.registerServiceConnectionListener(serviceConnectionListener)
      restartDiscovery()
    }
  }

  override fun stop() {
    mainHandler.post {
      if (!started) return@post

      started = false
      mainHandler.removeCallbacks(discoveryRunnable)
      detachNode()
      serviceApi.unregisterServiceConnectionListener(serviceConnectionListener)
      onConnected = null
      onMessage = null
    }
  }

  override fun send(message: ByteArray) {
    val copy = message.copyOf()
    mainHandler.post {
      val destination = nodeId
      if (!started || !listenerRegistered || destination == null) return@post

      runCatching {
            messageApi
                .sendMessage(destination, copy)
                .addOnFailureListener {
                    mainHandler.post {
                        if (destination == nodeId) {
                            listenerRegistered = false
                            restartDiscovery()
                        }
                    }
                }
      }
          .onFailure {
            listenerRegistered = false
            restartDiscovery()
          }
    }
  }

  private fun restartDiscovery() {
    if (!started) return
    mainHandler.removeCallbacks(discoveryRunnable)
    mainHandler.post(discoveryRunnable)
  }

  private fun discoverNode() {
    if (!started || discoveryInFlight || listenerRegistered) return

    discoveryInFlight = true
    runCatching {
          nodeApi
              .connectedNodes
              .addOnSuccessListener(
                  OnSuccessListener { nodes ->
                    mainHandler.post {
                      discoveryInFlight = false
                      handleNodes(nodes)
                    }
                  },
              )
              .addOnFailureListener { mainHandler.post { discoveryInFlight = false } }
    }
        .onFailure { discoveryInFlight = false }
  }

  private fun handleNodes(nodes: List<Node>) {
    if (!started) return

    val node = nodes.firstOrNull { it.id.isNotBlank() }
    if (node == null) {
      detachNode()
      return
    }

    if (node.id != nodeId) {
      detachNode()
      nodeId = node.id
    }

    ensureDeviceManagerPermission(node.id)
  }

  private fun ensureDeviceManagerPermission(currentNodeId: String) {
    if (!isCurrentNode(currentNodeId) || permissionCheckInFlight || listenerRegistrationInFlight) {
      return
    }

    permissionCheckInFlight = true
    runCatching {
          authApi
              .checkPermission(currentNodeId, Permission.DEVICE_MANAGER)
              .addOnSuccessListener(
                  OnSuccessListener { granted ->
                    mainHandler.post {
                      permissionCheckInFlight = false
                      if (!isCurrentNode(currentNodeId)) return@post
                      if (granted) {
                        registerMessageListener(currentNodeId)
                      } else {
                        requestDeviceManagerPermission(currentNodeId)
                      }
                    }
                  },
              )
              .addOnFailureListener {
                  mainHandler.post {
                      permissionCheckInFlight = false
                      if (isCurrentNode(currentNodeId)) {
                          requestDeviceManagerPermission(currentNodeId)
                      }
                  }
              }
    }
        .onFailure { permissionCheckInFlight = false }
  }

  private fun requestDeviceManagerPermission(currentNodeId: String) {
    if (
        !isCurrentNode(currentNodeId) ||
            permissionRequestInFlight ||
            permissionRequestedNodeId == currentNodeId
    ) {
      return
    }

    permissionRequestInFlight = true
    permissionRequestedNodeId = currentNodeId
    runCatching {
          authApi
              .requestPermission(currentNodeId, Permission.DEVICE_MANAGER)
              .addOnSuccessListener(
                  OnSuccessListener {
                    mainHandler.post {
                      permissionRequestInFlight = false
                      if (isCurrentNode(currentNodeId)) {
                        registerMessageListener(currentNodeId)
                      }
                    }
                  },
              )
              .addOnFailureListener { mainHandler.post { permissionRequestInFlight = false } }
    }
        .onFailure { permissionRequestInFlight = false }
  }

  private fun registerMessageListener(currentNodeId: String) {
    if (!isCurrentNode(currentNodeId) || listenerRegistered || listenerRegistrationInFlight) {
      return
    }

    listenerRegistrationInFlight = true
    runCatching {
          messageApi
              .addListener(currentNodeId, messageListener)
              .addOnSuccessListener(
                  OnSuccessListener {
                    mainHandler.post {
                      listenerRegistrationInFlight = false
                      if (!isCurrentNode(currentNodeId)) return@post

                      listenerRegistered = true
                      mainHandler.removeCallbacks(discoveryRunnable)
                      onConnected?.invoke()
                    }
                  },
              )
              .addOnFailureListener { mainHandler.post { listenerRegistrationInFlight = false } }
    }
        .onFailure { listenerRegistrationInFlight = false }
  }

  private fun detachNode() {
    val oldNodeId = nodeId
    if (oldNodeId != null) {
      runCatching { messageApi.removeListener(oldNodeId) }
    }

    nodeId = null
    listenerRegistered = false
    listenerRegistrationInFlight = false
    permissionCheckInFlight = false
    permissionRequestInFlight = false
    permissionRequestedNodeId = null
  }

  private fun isCurrentNode(candidate: String): Boolean = started && nodeId == candidate

  private companion object {
    const val DISCOVERY_RETRY_MS = 2_000L
  }
}
