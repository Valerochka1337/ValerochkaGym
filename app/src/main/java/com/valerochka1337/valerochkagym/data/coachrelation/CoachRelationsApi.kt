package com.valerochka1337.valerochkagym.data.coachrelation

import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.trainingproposal.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoachRelationsApi
@Inject
constructor(private val transport: BackendTransport, private val sessions: BackendSessionStore) {
  fun current(session: BackendSessionSnapshot): Boolean =
      sessions.snapshot()?.let {
        it.tokens.userId == session.tokens.userId && it.epoch == session.epoch
      } == true

  fun guard(session: BackendSessionSnapshot) {
    if (!current(session)) throw BackendException(401, "owner_changed", "Аккаунт изменился")
  }

  suspend fun raw(
      session: BackendSessionSnapshot,
      method: String,
      path: String,
      bytes: ByteArray = byteArrayOf(),
  ): ByteArray {
    guard(session)
    require(path.startsWith(ROOT) && bytes.size <= ProposalWire.REQUEST_LIMIT)
    val r =
        transport.authorizedRawResponse(
            method,
            path,
            bytes,
            expectedOwner = session.tokens.userId,
            expectedSessionEpoch = session.epoch,
            retryOnUnauthorized = false,
            maxResponseBytes = ProposalWire.RESPONSE_LIMIT,
        )
    guard(session)
    require(r.owner == session.tokens.userId && r.sessionEpoch == session.epoch)
    return r.rawBody
  }

  suspend fun directory(
      s: BackendSessionSnapshot,
      clients: Boolean,
      cursor: String?,
  ): DirectoryPage {
    val page =
        ProposalWire.decode<DirectoryPage>(
            raw(s, "GET", pagePath(if (clients) "clients" else "coaches", cursor))
        )
    require(
        page.directoryRevision >= 0 &&
            page.items.size <= 50 &&
            page.items.map { it.relationId }.distinct().size == page.items.size
    )
    require(page.items.all(::validRelation))
    checkCursor(page.nextCursor)
    return page
  }

  suspend fun calendar(
      s: BackendSessionSnapshot,
      relation: String,
      cursor: String?,
  ): CalendarProjectionPage {
    id(relation)
    val p =
        ProposalWire.decode<CalendarProjectionPage>(
            raw(s, "GET", pagePath("$relation/calendar", cursor))
        )
    require(p.recipientSyncRevision >= 0 && p.items.size <= 50)
    require(
        p.items.all { i ->
          ProposalWire.uuid(i.calendarPlanId) &&
              ProposalWire.uuid(i.routineId) &&
              i.startsAtMillis >= 0 &&
              i.title.length in 1..200 &&
              i.timeZoneId.length in 1..255 &&
              i.exercises.size <= 200 &&
              i.exercises.all {
                it.name.length in 1..200 &&
                    it.type in setOf("STRENGTH", "TIMED", "CARDIO") &&
                    it.plannedSetCount in 0..1000
              }
        }
    )
    checkCursor(p.nextCursor)
    return p
  }

  suspend fun completed(
      s: BackendSessionSnapshot,
      relation: String,
      cursor: String?,
  ): CompletedWorkoutProjectionPage {
    id(relation)
    val p =
        ProposalWire.decode<CompletedWorkoutProjectionPage>(
            raw(s, "GET", pagePath("$relation/completed-workouts", cursor))
        )
    require(p.recipientSyncRevision >= 0 && p.items.size <= 50)
    require(
        p.items.all { i ->
          ProposalWire.uuid(i.workoutId) &&
              i.finishedAtMillis >= 0 &&
              i.exercises.size <= 200 &&
              i.exercises.all { e ->
                ProposalWire.uuid(e.exerciseId) &&
                    e.name.length in 1..200 &&
                    e.sets.size <= 1000 &&
                    e.sets.all(::validSet)
              }
        }
    )
    checkCursor(p.nextCursor)
    return p
  }

  companion object {
    const val ROOT = "/coach-relations"

    fun id(value: String) {
      require(ProposalWire.uuid(value))
    }

    fun validRelation(r: Relation) =
        ProposalWire.uuid(r.relationId) &&
            ProposalWire.uuid(r.counterpartyId) &&
            r.state in setOf("ACTIVE", "REVOKED") &&
            r.createdAtMillis >= 0 &&
            (r.revokedAtMillis == null || r.revokedAtMillis >= 0)

    fun validSet(s: ProjectionSet) =
        listOf(s.weightKg, s.speedKmh).all { it == null || it.isFinite() && it in 0.0..1e6 } &&
            (s.inclinePct == null || s.inclinePct.isFinite() && s.inclinePct in -100.0..1e6) &&
            listOf(s.reps, s.durationSec).all { it == null || it in 0..1000000 }

    private fun checkCursor(c: String?) {
      require(c == null || c.length in 1..4096)
    }

    private fun pagePath(part: String, c: String?): String {
      checkCursor(c)
      return "$ROOT/$part?limit=50" + (c?.let { "&cursor=${URLEncoder.encode(it,"UTF-8")}" } ?: "")
    }
  }
}
