package com.valerochka1337.valerochkagym.service.heartrate

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.valerochka1337.valerochkagym.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android BLE-реализация стандартного Heart Rate Profile.
 *
 * Share HR на Band 10 выглядит для Android так же, как любой другой HRS-датчик. Монитор не
 * переподключается сам: после потери соединения пользователь видит честный статус и решает,
 * повторять ли попытку.
 */
@Singleton
class BleHeartRateMonitor
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : HeartRateMonitor {

  private val bluetoothManager: BluetoothManager? by lazy {
    context.getSystemService(BluetoothManager::class.java)
  }

  private val candidates = linkedMapOf<String, HeartRateDevice>()
  private var scanner: BluetoothLeScanner? = null
  private var scanning = false
  private var scanTimeoutJob: Job? = null
  private var staleReadingJob: Job? = null
  private var gatt: BluetoothGatt? = null
  private var connectedDevice: HeartRateDevice? = null

  private val _state = MutableStateFlow<HeartRateConnectionState>(HeartRateConnectionState.Idle)
  override val state: StateFlow<HeartRateConnectionState> = _state.asStateFlow()

  private val _reading = MutableStateFlow<HeartRateReading?>(null)
  override val reading: StateFlow<HeartRateReading?> = _reading.asStateFlow()

  private val lifecycle =
      HeartRateConnectionLifecycle(
          closeGatt = ::closeGatt,
          clearReading = ::clearReading,
          emitState = { _state.value = it },
      )

  override fun scan() {
    if (!hasBluetoothPermissions()) {
      _state.value = HeartRateConnectionState.PermissionRequired
      return
    }

    val adapter = bluetoothManager?.adapter
    if (adapter == null) {
      _state.value = HeartRateConnectionState.Error("Bluetooth недоступен на этом телефоне")
      return
    }
    if (!adapter.isEnabled) {
      _state.value = HeartRateConnectionState.Error("Включите Bluetooth и повторите")
      return
    }

    stopScan()
    closeGatt()
    clearReading()
    candidates.clear()
    scanner = adapter.bluetoothLeScanner
    val currentScanner = scanner
    if (currentScanner == null) {
      _state.value = HeartRateConnectionState.Error("Не удалось запустить поиск Bluetooth")
      return
    }

    _state.value = HeartRateConnectionState.Searching
    scanning = true
    runCatching {
          currentScanner.startScan(
              listOf(
                  ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID)).build(),
              ),
              ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
              scanCallback,
          )
        }
        .onFailure {
          scanning = false
          _state.value = HeartRateConnectionState.Error("Не удалось начать поиск пульсометра")
          return
        }

    scanTimeoutJob =
        applicationScope.launch {
          delay(SCAN_WINDOW_MILLIS.milliseconds)
          finishScan()
        }
  }

  override fun connect(device: HeartRateDevice) {
    if (!hasBluetoothPermissions()) {
      _state.value = HeartRateConnectionState.PermissionRequired
      return
    }

    val adapter = bluetoothManager?.adapter
    if (adapter == null || !adapter.isEnabled) {
      _state.value = HeartRateConnectionState.Error("Включите Bluetooth и повторите")
      return
    }

    stopScan()
    closeGatt()
    clearReading()
    connectedDevice = device
    _state.value = HeartRateConnectionState.Connecting(device)

    val bluetoothDevice =
        runCatching { adapter.getRemoteDevice(device.address) }
            .getOrElse {
              connectionError("Не удалось выбрать пульсометр")
              return
            }
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      _state.value = HeartRateConnectionState.PermissionRequired
      return
    }
    gatt =
        runCatching {
              // BluetoothGattConnectionSettings появился в свежем SDK, но отсутствует на части
              // прошивок телефонов. Этот overload стабилен с API 23 и явно оставляет LE transport.
              @Suppress("DEPRECATION")
              bluetoothDevice.connectGatt(
                  context,
                  false,
                  gattCallback,
                  BluetoothDevice.TRANSPORT_LE,
              )
            }
            .getOrElse {
              connectionError("Не удалось подключиться к пульсометру")
              return
            }

    if (gatt == null) {
      connectionError("Не удалось подключиться к пульсометру")
    }
  }

  override fun stop() {
    stopScan()
    lifecycle.stop()
  }

  override fun reportError(message: String) {
    stopScan()
    lifecycle.fail(message)
  }

  private fun finishScan() {
    if (!scanning) return
    stopScan()
    val found = candidates.values.toList()
    if (found.isEmpty()) {
      _state.value =
          HeartRateConnectionState.Error(
              "Пульсометр не найден — включите Share HR и повторите",
          )
    } else if (found.size == 1) {
      connect(found.single())
    } else {
      _state.value = HeartRateConnectionState.Selection(found)
    }
  }

  private fun stopScan() {
    scanTimeoutJob?.cancel()
    scanTimeoutJob = null
    if (
        scanning &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
    ) {
      runCatching { scanner?.stopScan(scanCallback) }
    }
    scanning = false
    scanner = null
  }

  private fun closeGatt() {
    val activeGatt = gatt
    gatt = null
    connectedDevice = null
    if (activeGatt == null) return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    ) {
      runCatching {
        activeGatt.disconnect()
        activeGatt.close()
      }
    }
  }

  private fun clearReading() {
    staleReadingJob?.cancel()
    staleReadingJob = null
    _reading.value = null
  }

  private fun connectionError(message: String) {
    lifecycle.fail(message)
  }

  private fun hasBluetoothPermissions(): Boolean =
      hasBluetoothScanPermission() && hasBluetoothConnectPermission()

  private fun hasBluetoothScanPermission(): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
          PackageManager.PERMISSION_GRANTED

  private fun hasBluetoothConnectPermission(): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED

  private fun deviceFrom(result: ScanResult): HeartRateDevice? {
    val address = result.device.address ?: return null
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    )
        return null
    val name = runCatching { result.device.name }.getOrNull()
    return HeartRateDevice(address = address, name = name, rssi = result.rssi)
  }

  private fun emitReading(value: ByteArray) {
    val bpm = parseHeartRateMeasurement(value) ?: return
    val device = connectedDevice ?: return
    val fresh = HeartRateReading(bpm = bpm, updatedAtMillis = System.currentTimeMillis())
    _reading.value = fresh
    _state.value = HeartRateConnectionState.Live(device, fresh)
    staleReadingJob?.cancel()
    staleReadingJob =
        applicationScope.launch {
          delay(HEART_RATE_STALE_AFTER_MILLIS.milliseconds)
          if (_reading.value == fresh) {
            _reading.value = null
            if (connectedDevice == device && gatt != null) {
              _state.value = HeartRateConnectionState.Connected(device)
            }
          }
        }
  }

  private fun enableMeasurements(callbackGatt: BluetoothGatt) {
    val device = connectedDevice ?: return
    if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    ) {
      _state.value = HeartRateConnectionState.PermissionRequired
      return
    }
    val configured =
        runCatching {
              val characteristic =
                  callbackGatt
                      .getService(HEART_RATE_SERVICE_UUID)
                      ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID) ?: return@runCatching false
              val properties = characteristic.properties
              val notificationValue =
                  when {
                    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                    else -> return@runCatching false
                  }
              val descriptor =
                  characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
                      ?: return@runCatching false
              callbackGatt.setCharacteristicNotification(characteristic, true) &&
                  callbackGatt.writeDescriptor(descriptor, notificationValue) ==
                      BluetoothStatusCodes.SUCCESS
            }
            .getOrDefault(false)
    if (!configured) {
      connectionError("Не удалось включить передачу пульса")
      return
    }
    _state.value = HeartRateConnectionState.Connected(device)
  }

  private val scanCallback =
      object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
          if (!scanning) return
          val device = deviceFrom(result) ?: return
          candidates[device.address] = device
        }

        override fun onScanFailed(errorCode: Int) {
          stopScan()
          _state.value = HeartRateConnectionState.Error("Поиск пульсометра прерван Bluetooth")
        }
      }

  private val gattCallback =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            callbackGatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
          if (callbackGatt !== gatt) return
          when {
            status != BluetoothGatt.GATT_SUCCESS -> {
              connectionError("Не удалось подключиться к пульсометру")
            }

            newState == BluetoothProfile.STATE_CONNECTED -> {
              if (
                  ContextCompat.checkSelfPermission(
                      context,
                      Manifest.permission.BLUETOOTH_CONNECT,
                  ) != PackageManager.PERMISSION_GRANTED
              ) {
                _state.value = HeartRateConnectionState.PermissionRequired
                return
              }
              val startedDiscovery =
                  runCatching { callbackGatt.discoverServices() }.getOrDefault(false)
              if (!startedDiscovery) {
                connectionError("Не удалось прочитать службы пульсометра")
              }
            }

            newState == BluetoothProfile.STATE_DISCONNECTED -> {
              val device = connectedDevice
              lifecycle.lose(device)
            }
          }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
          if (callbackGatt !== gatt) return
          if (status != BluetoothGatt.GATT_SUCCESS) {
            connectionError("Не удалось прочитать службы пульсометра")
            return
          }
          enableMeasurements(callbackGatt)
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
          if (callbackGatt !== gatt || descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
              return
          if (status != BluetoothGatt.GATT_SUCCESS) {
            connectionError("Не удалось включить передачу пульса")
          }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
          if (callbackGatt !== gatt || characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) return
          emitReading(value)
        }
      }

  private companion object {
    val HEART_RATE_SERVICE_UUID: UUID = uuid16("180D")
    val HEART_RATE_MEASUREMENT_UUID: UUID = uuid16("2A37")
    val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID = uuid16("2902")
    const val SCAN_WINDOW_MILLIS = 5_000L

    fun uuid16(shortUuid: String): UUID =
        UUID.fromString("0000$shortUuid-0000-1000-8000-00805f9b34fb")
  }
}
