package com.valerochka1337.valerochkagym.data.coachrelation

import com.valerochka1337.valerochkagym.data.trainingproposal.ApprovalDraft
import kotlinx.serialization.Serializable

@Serializable data class Operation(val operationId: String)

@Serializable
data class InvitationAcceptRequest(
    val operationId: String,
    val token: String,
    val calendar: Boolean,
    val completedWorkouts: Boolean,
)

@Serializable
data class InvitationCreated(val inviteId: String, val token: String, val expiresAtMillis: Long)

@Serializable
data class Relation(
    val relationId: String,
    val counterpartyId: String,
    val state: String,
    val calendar: Boolean,
    val completedWorkouts: Boolean,
    val createdAtMillis: Long,
    val revokedAtMillis: Long?,
)

@Serializable
data class DirectoryPage(
    val items: List<Relation>,
    val nextCursor: String?,
    val directoryRevision: Long,
)

@Serializable
data class ProjectionSet(
    val weightKg: Double?,
    val reps: Long?,
    val durationSec: Long?,
    val speedKmh: Double?,
    val inclinePct: Double?,
)

@Serializable
data class CalendarProjectionExercise(val name: String, val type: String, val plannedSetCount: Long)

@Serializable
data class CalendarProjectionItem(
    val calendarPlanId: String,
    val routineId: String,
    val startsAtMillis: Long,
    val timeZoneId: String,
    val title: String,
    val exercises: List<CalendarProjectionExercise>,
)

@Serializable
data class CompletedWorkoutExercise(
    val exerciseId: String,
    val name: String,
    val sets: List<ProjectionSet>,
)

@Serializable
data class CompletedWorkoutProjectionItem(
    val workoutId: String,
    val finishedAtMillis: Long,
    val exercises: List<CompletedWorkoutExercise>,
)

@Serializable
data class CalendarProjectionPage(
    val items: List<CalendarProjectionItem>,
    val nextCursor: String?,
    val recipientSyncRevision: Long,
)

@Serializable
data class CompletedWorkoutProjectionPage(
    val items: List<CompletedWorkoutProjectionItem>,
    val nextCursor: String?,
    val recipientSyncRevision: Long,
)

@Serializable
data class CoachProposalCreateRequest(
    val operationId: String,
    val expectedOwnerRevision: Long,
    val expectedCatalogRevision: Long,
    val draft: ApprovalDraft,
)

@Serializable
data class CoachProposalReviseRequest(
    val operationId: String,
    val expectedVersion: Int,
    val draft: ApprovalDraft,
)

@Serializable
data class CoachProposalRevokeRequest(val operationId: String, val expectedVersion: Int)
