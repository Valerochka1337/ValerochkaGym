package com.valerochka1337.valerochkagym.data.backend

import androidx.room.withTransaction
import com.valerochka1337.valerochkagym.data.db.GymDatabase
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.json.*

/**
 * Called under BackendSync's account mutex, after the current workout aggregates are acknowledged.
 */
internal class CoachJournalSync(
    private val database: GymDatabase,
    private val api: BackendTransport,
    private val tokens: BackendSessionStore,
) {
  private val db
    get() = database.openHelper.writableDatabase

  private fun fail(code: String, message: String): Nothing =
      throw BackendException(409, code, message)

  private fun checkOwner(account: String, epoch: Long) {
    val owner =
        db.query("SELECT owner FROM backend_state WHERE id=1").use {
          if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    if (owner != account || tokens.session.value?.userId != account || tokens.sessionEpoch != epoch)
        fail("owner_changed", "Аккаунт изменился")
    if (
        db.query("SELECT 1 FROM workouts WHERE finishedAt IS NULL LIMIT 1").use { it.moveToFirst() }
    )
        fail("workout_active", "Синхронизация продолжится после тренировки")
  }

  private fun baseline(workoutId: String): CloudRecord? =
      db.query(
              "SELECT recordJson FROM backend_baseline WHERE `key`=?",
              arrayOf<Any>("workout:$workoutId"),
          )
          .use {
            if (it.moveToFirst()) api.json.decodeFromString<CloudRecord>(it.getString(0)) else null
          }

  private fun confirmed(workoutId: String, snapshot: Map<String, JsonObject>): Boolean {
    val record = baseline(workoutId) ?: return false
    val current = snapshot["workout:$workoutId"] ?: return false
    return !record.deleted &&
        current["finishedAt"] != JsonNull &&
        current["coachRevision"] != null &&
        record.payload == current
  }

  private fun valid(entry: CoachJournalEntry) {
    fun uuid(value: String) =
        runCatching { UUID.fromString(value).toString().equals(value, true) }.getOrDefault(false)
    if (
        !uuid(entry.id) ||
            !uuid(entry.workoutId) ||
            !uuid(entry.deviceId) ||
            entry.createdAt < 0 ||
            entry.payload.toString().toByteArray().size > 64 * 1024
    )
        fail("invalid_journal", "Не удалось проверить запись журнала тренера")
  }

  private suspend fun request(
      account: String,
      epoch: Long,
      method: String,
      path: String,
      body: JsonElement? = null,
  ): JsonElement {
    checkOwner(account, epoch)
    val dispatch = tokens.snapshot() ?: fail("owner_changed", "Аккаунт изменился")
    val response =
        api.authorizedRawResponse(
            method = method,
            path = path,
            rawBody = body?.toString()?.toByteArray() ?: ByteArray(0),
            expectedOwner = account,
            expectedSessionEpoch = dispatch.epoch,
            retryOnUnauthorized = false,
            maxResponseBytes = 600 * 1024,
        )
    if (
        response.owner != account ||
            response.sessionEpoch != dispatch.epoch ||
            tokens.snapshot() != dispatch
    )
        fail("owner_changed", "Аккаунт изменился")
    checkOwner(account, epoch)
    return response.body
  }

  suspend fun run(account: String) {
    val epoch = tokens.snapshot()?.epoch ?: fail("owner_changed", "Аккаунт изменился")
    database.withTransaction {
      checkOwner(account, epoch)
      db.execSQL(
          "INSERT OR IGNORE INTO coach_sync_state(accountId,deviceId,watermark) VALUES (?,?,0)",
          arrayOf<Any>(account, UUID.randomUUID().toString()),
      )
    }
    upload(account, epoch)
    download(account, epoch)
  }

  private suspend fun upload(account: String, epoch: Long) {
    repeat(2048) {
      val entries =
          database.withTransaction {
            checkOwner(account, epoch)
            val device =
                db.query(
                        "SELECT deviceId FROM coach_sync_state WHERE accountId=?",
                        arrayOf<Any>(account),
                    )
                    .use {
                      check(it.moveToFirst())
                      it.getString(0)
                    }
            db.execSQL(
                "UPDATE coach_journal SET deviceId=? WHERE accountId=? AND uploaded=0 AND deviceId IS NULL",
                arrayOf<Any>(device, account),
            )
            val snapshot = PortableData(db).snapshot()
            val pending =
                db.query(
                        "SELECT id,workoutId,deviceId,createdAt,payload FROM coach_journal WHERE accountId=? AND uploaded=0 ORDER BY createdAt,id LIMIT 100",
                        arrayOf<Any>(account),
                    )
                    .use { c ->
                      buildList {
                        while (c.moveToNext()) add(
                            CoachJournalEntry(
                                c.getString(0),
                                c.getString(1),
                                c.getString(2),
                                c.getLong(3),
                                api.json.parseToJsonElement(c.getString(4)).jsonObject,
                            )
                        )
                      }
                    }
            if (pending.any { !confirmed(it.workoutId, snapshot) })
                fail(
                    "workout_not_acknowledged",
                    "Журнал ожидает подтверждения актуальной тренировки",
                )
            buildList {
              for (entry in pending) {
                valid(entry)
                if (
                    api.json.encodeToString(CoachJournalPush(this + entry)).toByteArray().size >
                        512 * 1024
                )
                    break
                add(entry)
              }
            }
          }
      if (entries.isEmpty()) return
      val ack =
          api.json.decodeFromJsonElement<CoachJournalAck>(
              request(
                  account,
                  epoch,
                  "POST",
                  "/coach/journal",
                  api.json.encodeToJsonElement(CoachJournalPush(entries)),
              )
          )
      if (ack.accepted != entries.size)
          fail("journal_ack_invalid", "Сервер не подтвердил весь пакет журнала")
      database.withTransaction {
        checkOwner(account, epoch)
        val snapshot = PortableData(db).snapshot()
        if (entries.any { !confirmed(it.workoutId, snapshot) })
            fail("workout_not_acknowledged", "Тренировка изменилась во время отправки журнала")
        entries.forEach { entry ->
          // Event payloads are immutable. Matching the exact envelope also guards accidental edits.
          db.execSQL(
              "UPDATE coach_journal SET uploaded=1 WHERE id=? AND accountId=? AND workoutId=? AND deviceId=? AND createdAt=? AND payload=?",
              arrayOf<Any>(
                  entry.id,
                  account,
                  entry.workoutId,
                  entry.deviceId,
                  entry.createdAt,
                  entry.payload.toString(),
              ),
          )
        }
      }
    }
    fail("journal_limit", "Синхронизация журнала продолжится позже")
  }

  private suspend fun download(account: String, epoch: Long) {
    val after =
        database.withTransaction {
          checkOwner(account, epoch)
          db.query(
                  "SELECT watermark FROM coach_sync_state WHERE accountId=?",
                  arrayOf<Any>(account),
              )
              .use {
                check(it.moveToFirst())
                it.getLong(0)
              }
        }
    var cursor: String? = null
    var queryAfter = after
    var watermark: Long? = null
    val seen = mutableSetOf<String>()
    repeat(2048) {
      checkOwner(account, epoch)
      val path =
          "/coach/journal?after=$queryAfter&limit=100" +
              (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
      val raw =
          try {
            request(account, epoch, "GET", path)
          } catch (error: BackendException) {
            // Deleting the highest server sequence can make an otherwise valid saved watermark
            // exceed its current maximum. Re-read from zero once; existing immutable IDs
            // deduplicate.
            if (
                error.status == 400 &&
                    error.code == "invalid_request" &&
                    cursor == null &&
                    queryAfter > 0
            ) {
              queryAfter = 0
              return@repeat
            }
            throw error
          }
      val page = api.json.decodeFromJsonElement<CoachJournalPage>(raw)
      if (
          page.entries.size > 100 ||
              page.watermark < 0 ||
              (watermark != null && watermark != page.watermark) ||
              page.entries.sumOf { api.json.encodeToString(it).toByteArray().size } > 512 * 1024 ||
              (page.nextCursor != null && (page.entries.isEmpty() || !seen.add(page.nextCursor)))
      )
          fail("journal_page_invalid", "Не удалось проверить страницу журнала тренера")
      // A completed zero-based reread may reset a cursor after server deletions.
      watermark = page.watermark
      page.entries.forEach(::valid)
      database.withTransaction {
        checkOwner(account, epoch)
        page.entries.forEach { entry -> import(account, entry) }
        if (page.nextCursor == null)
            db.execSQL(
                "UPDATE coach_sync_state SET watermark=? WHERE accountId=?",
                arrayOf<Any>(
                    if (queryAfter == 0L) page.watermark else maxOf(after, page.watermark),
                    account,
                ),
            )
      }
      cursor = page.nextCursor
      if (cursor == null) return
    }
    fail("journal_page_limit", "Синхронизация журнала продолжится позже")
  }

  private fun import(account: String, entry: CoachJournalEntry) {
    val exists =
        db.query("SELECT 1 FROM workouts WHERE id=?", arrayOf<Any>(entry.workoutId)).use {
          it.moveToFirst()
        }
    if (!exists) {
      if (baseline(entry.workoutId)?.deleted == true) return
      fail("journal_workout_missing", "Сначала нужно загрузить тренировку для журнала")
    }
    val old =
        db.query(
                "SELECT accountId,workoutId,deviceId,createdAt,payload FROM coach_journal WHERE id=?",
                arrayOf<Any>(entry.id),
            )
            .use { c ->
              if (!c.moveToFirst()) null
              else {
                if (
                    c.getString(0) != account ||
                        c.getString(1) != entry.workoutId ||
                        (!c.isNull(2) && c.getString(2) != entry.deviceId) ||
                        c.getLong(3) != entry.createdAt ||
                        api.json.parseToJsonElement(c.getString(4)) != entry.payload
                )
                    fail("journal_event_conflict", "Содержимое записи журнала не совпадает")
                true
              }
            }
    if (old != null) return
    db.execSQL(
        "INSERT INTO coach_journal(id,accountId,workoutId,createdAt,payload,uploaded,deviceId) VALUES (?,?,?,?,?,1,?)",
        arrayOf<Any>(
            entry.id,
            account,
            entry.workoutId,
            entry.createdAt,
            entry.payload.toString(),
            entry.deviceId,
        ),
    )
    val text =
        (entry.payload["text"] as? JsonPrimitive)?.contentOrNull?.take(16000)
            ?: "Событие тренера с другого устройства"
    val role =
        (entry.payload["role"] as? JsonPrimitive)?.contentOrNull?.takeIf {
          it in setOf("user", "assistant", "system")
        } ?: "system"
    // Imported records are display-only; proposals, receipts and host authority are never
    // reconstructed.
    db.execSQL(
        "INSERT OR IGNORE INTO coach_messages(id,accountId,workoutId,role,text,createdAt,status) VALUES (?,?,?,?,?,?,'IMPORTED')",
        arrayOf<Any>(entry.id, account, entry.workoutId, role, text, entry.createdAt),
    )
  }
}
