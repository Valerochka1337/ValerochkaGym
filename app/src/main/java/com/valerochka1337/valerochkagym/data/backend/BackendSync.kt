package com.valerochka1337.valerochkagym.data.backend

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Singleton
class BackendSync
@Inject
constructor(
    private val database: GymDatabase,
    private val api: BackendTransport,
    private val tokens: BackendSessionStore,
) {
  val mutex = Mutex()
  private val catalog = CatalogSync(database, api)
  private val mutableCatalogConflict = MutableStateFlow(false)
  val catalogConflict = mutableCatalogConflict.asStateFlow()

  suspend fun personalCopy(kind: String, id: String): String =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          database.withTransaction {
            check(!active()) { "Завершите тренировку перед созданием копии" }
            val payload = PortableData(db).snapshot(includeStandard = true).getValue("$kind:$id")
            catalog.personalCopy(kind, payload)
          }
        }
      }

  private val mutableStatus = MutableStateFlow("Ожидает синхронизации")
  val status = mutableStatus.asStateFlow()
  private val mutableConflict = MutableStateFlow(false)
  val conflict = mutableConflict.asStateFlow()
  private val db
    get() = database.openHelper.writableDatabase

  private fun baseline(): Map<String, CloudRecord> =
      db.query("SELECT recordJson FROM backend_baseline").use { c ->
        buildMap {
          while (c.moveToNext()) {
            val r = api.json.decodeFromString<CloudRecord>(c.getString(0))
            put(r.key, r)
          }
        }
      }

  private fun saveBaseline(r: CloudRecord) {
    db.execSQL(
        "INSERT OR REPLACE INTO backend_baseline(`key`,recordJson) VALUES (?,?)",
        arrayOf(r.key, api.json.encodeToString(r)),
    )
  }

  fun owner(): String? =
      db.query("SELECT owner FROM backend_state WHERE id=1").use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
      }

  suspend fun claim(user: String) =
      withContext(Dispatchers.IO) {
        database.withTransaction {
          val previous = owner()
          if (previous != null && previous != user)
              throw BackendException(
                  409,
                  "local_owner",
                  "На устройстве данные другого аккаунта. Войдите в прежний аккаунт; для смены сначала экспортируйте и очистите его данные",
              )
          db.execSQL("INSERT OR IGNORE INTO backend_state(id,owner,generation) VALUES (1,NULL,0)")
          db.execSQL("UPDATE backend_state SET owner=? WHERE id=1", arrayOf(user))
        }
      }

  private fun assertOwner(user: String) {
    if (owner() != user || tokens.session.value?.userId != user)
        throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  private fun active(): Boolean =
      db.query("SELECT 1 FROM workouts WHERE finishedAt IS NULL LIMIT 1").use { it.moveToFirst() }

  suspend fun hasActiveWorkout() = withContext(Dispatchers.IO) { active() }

  suspend fun run(resolve: String? = null) =
      withContext(Dispatchers.IO) {
        mutex.withLock {
          try {
            if (active()) {
              mutableStatus.value = "Синхронизация продолжится после тренировки"
              return@withLock
            }
            val wasCatalogConflict =
                mutableCatalogConflict.value ||
                    db.query("SELECT pendingSnapshot FROM catalog_state WHERE id=1").use {
                      it.moveToFirst() && !it.isNull(0)
                    }
            catalog.refresh(resolve)
            val personalResolve = if (wasCatalogConflict) null else resolve
            mutableCatalogConflict.value = false
            val user = tokens.session.value?.userId ?: return@withLock
            assertOwner(user)
            mutableStatus.value = "Синхронизация…"
            // No network response is allowed to mutate the set IDs currently used by the foreground
            // service.
            if (active()) {
              mutableStatus.value = "Синхронизация продолжится после тренировки"
              return@withLock
            }
            if (resolve != null && !wasCatalogConflict) db.execSQL("DELETE FROM backend_outbox")
            val pending =
                db.query("SELECT owner,requestJson FROM backend_outbox WHERE id=1").use { c ->
                  if (c.moveToFirst()) {
                    check(c.getString(0) == user)
                    api.json.decodeFromString<CloudPush>(c.getString(1))
                  } else null
                }
            if (pending != null) send(user, pending)
            val remote =
                api.json.decodeFromJsonElement<CloudSnapshot>(api.authorized("GET", "/sync"))
            database.withTransaction {
              assertOwner(user)
              if (active())
                  throw BackendException(
                      409,
                      "workout_active",
                      "Завершите тренировку перед синхронизацией",
                  )
              val local = PortableData(db).snapshot()
              val base = baseline()
              val conflicts = CloudMerge.conflicts(local, base, remote.records)
              if (conflicts.isNotEmpty() && personalResolve == null) {
                mutableConflict.value = true
                throw BackendException(
                    409,
                    "revision_conflict",
                    "Есть изменения с другого устройства. Выберите версию в настройках аккаунта",
                )
              }
              val incoming =
                  remote.records.filter { r ->
                    val changed = base[r.key]?.revision != r.revision
                    val localChanged = local[r.key] != base[r.key]?.payload
                    changed &&
                        (!localChanged ||
                            local[r.key] == r.payload ||
                            personalResolve == "server" && r.key in conflicts ||
                            base[r.key] == null &&
                                r.kind == "exercise" &&
                                local[r.key]?.get("isCustom")?.toString() == "false")
                  }
              PortableData(db)
                  .apply(incoming.filter { !it.deleted }, incoming.filter { it.deleted })
              remote.records.forEach(::saveBaseline)
            }
            // Capture each exact payload durably before HTTP. A local edit during HTTP is compared
            // against
            // the acknowledged payload in the next iteration, so acknowledgement never clears a
            // newer edit.
            repeat(100) {
              val batch =
                  database.withTransaction {
                    assertOwner(user)
                    if (active()) return@withTransaction null
                    val local = PortableData(db).snapshot()
                    val base = baseline()
                    val order =
                        listOf("exercise", "gym", "routine", "workout", "measurement", "schedule")
                    val changes =
                        (local.keys + base.keys)
                            .mapNotNull { key ->
                              val old = base[key]
                              val payload = local[key]
                              if (payload == old?.payload) null
                              else {
                                val kind = key.substringBefore(':')
                                val id = key.substringAfter(':')
                                CloudChange(kind, id, old?.revision ?: 0, payload == null, payload)
                              }
                            }
                            .sortedBy {
                              if (it.deleted) 20 - order.indexOf(it.kind)
                              else order.indexOf(it.kind)
                            }
                            .take(1000)
                    if (changes.isEmpty()) null
                    else
                        CloudPush(UUID.randomUUID().toString(), changes, catalog.revision()).also {
                          db.execSQL(
                              "INSERT OR REPLACE INTO backend_outbox(id,owner,requestJson) VALUES (1,?,?)",
                              arrayOf(user, api.json.encodeToString(it)),
                          )
                        }
                  }
              if (batch == null) {
                database.withTransaction {
                  assertOwner(user)
                  val local = PortableData(db).snapshot()
                  baseline()
                      .values
                      .filter { !it.deleted && it.payload == local[it.key] }
                      .forEach { r ->
                        val table =
                            when (r.kind) {
                              "workout" -> "workouts"
                              "measurement" -> "body_measurements"
                              else -> null
                            }
                        if (table != null)
                            db.execSQL(
                                "UPDATE $table SET uploadStatus='UPLOADED',uploadError=NULL WHERE id=? AND uploadStatus!='UPLOADED'",
                                arrayOf(r.id),
                            )
                      }
                }
                mutableStatus.value = "Данные синхронизированы"
                mutableConflict.value = false
                return@withLock
              }
              send(user, batch)
            }
            mutableStatus.value = "Синхронизация продолжится в фоне"
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is BackendException && e.code == "catalog_transition_required") {
              mutableCatalogConflict.value = true
              mutableConflict.value = true
            }
            if (e is BackendException && e.code == "revision_conflict") mutableConflict.value = true
            mutableStatus.value =
                e.message ?: "Не удалось синхронизировать. Повторим при подключении"
            throw e
          }
        }
      }

  private suspend fun send(user: String, push: CloudPush) {
    assertOwner(user)
    var sent = push
    suspend fun post(value: CloudPush) =
        api.json.decodeFromJsonElement<CloudAck>(
            api.authorized("POST", "/sync", api.json.encodeToJsonElement(value))
        )
    val ack =
        try {
          post(sent)
        } catch (e: BackendException) {
          if (e.code != "catalog_stale") throw e
          catalog.refresh()
          sent =
              push.copy(
                  operationId = UUID.randomUUID().toString(),
                  catalogRevision = catalog.revision(),
              )
          database.withTransaction {
            assertOwner(user)
            if (active())
                throw BackendException(
                    409,
                    "workout_active",
                    "Завершите тренировку перед синхронизацией",
                )
            db.execSQL(
                "UPDATE backend_outbox SET requestJson=? WHERE id=1",
                arrayOf(api.json.encodeToString(sent)),
            )
          }
          post(sent)
        }
    database.withTransaction {
      assertOwner(user)
      sent.changes.forEach {
        saveBaseline(CloudRecord(it.kind, it.id, ack.revision, it.deleted, it.payload))
      }
      db.execSQL("DELETE FROM backend_outbox WHERE id=1")
    }
  }
}
