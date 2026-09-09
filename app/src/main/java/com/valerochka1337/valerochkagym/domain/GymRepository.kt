package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.EquipmentRequirementState
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import kotlinx.coroutines.flow.Flow

/**
 * Сохранённая конфигурация одного зала. [id] — стабильный UUID, пригодный для синхронизации;
 * локальные Room id наружу из репозитория не выходят.
 */
data class GymConfiguration(
    val id: String,
    val name: String,
    val exercises: List<ExerciseEntity>,
    val equipmentIds: Set<String> = emptySet(),
    val inventoryConfigured: Boolean = false,
    val origin: String = "PERSONAL",
    val archived: Boolean = false,
)

sealed interface ExerciseEquipmentRequirements {
  data object UnknownLegacy : ExerciseEquipmentRequirements

  data object ExplicitNone : ExerciseEquipmentRequirements

  data class Required(val equipmentIds: Set<String>) : ExerciseEquipmentRequirements
}

/** Программа, которая не позволяет удалить зал или сузить его каталог без явного решения. */
data class GymRoutineReference(
    val id: Long,
    val name: String,
)

/**
 * Несовместимое изменение состава зала: перечислены затронутые программы и упражнения, которые
 * перестанут быть доступными хотя бы в одном из привязанных залов.
 */
data class GymConfigurationConflict(
    val routines: List<GymRoutineReference>,
    val exercises: List<ExerciseEntity>,
    val missingEquipmentIds: Set<String> = emptySet(),
)

sealed interface SaveGymResult {
  data class Saved(val gymId: String) : SaveGymResult

  data class Conflict(val details: GymConfigurationConflict) : SaveGymResult

  data object NameAlreadyExists : SaveGymResult

  data object NotFound : SaveGymResult

  data object Failure : SaveGymResult
}

sealed interface DeleteGymResult {
  data object Deleted : DeleteGymResult

  data class InUse(val routines: List<GymRoutineReference>) : DeleteGymResult

  data object NotFound : DeleteGymResult

  data object Failure : DeleteGymResult
}

/** Полный несохранённый снимок программы, который записывается одной Room-транзакцией. */
data class RoutineConfigurationDraft(
    val routine: RoutineEntity,
    val exercises: List<RoutineExerciseEntity>,
    val gymIds: Set<String>,
)

sealed interface SaveRoutineConfigurationResult {
  data class Saved(
      val routineId: Long,
      val routine: RoutineEntity,
  ) : SaveRoutineConfigurationResult

  data class Conflict(val exercises: List<ExerciseEntity>) : SaveRoutineConfigurationResult

  data object GymNotFound : SaveRoutineConfigurationResult

  data object Failure : SaveRoutineConfigurationResult
}

/**
 * Immutable command for saving a completed workout as a routine.
 *
 * A replacement carries both the source snapshot and its intended target snapshot. The database
 * checks the fingerprints again inside its transaction, so an open confirmation dialog can never
 * overwrite an editor change that happened after it was shown.
 */
sealed interface CompletedWorkoutRoutineCommand {
  data class Create(val draft: RoutineConfigurationDraft) : CompletedWorkoutRoutineCommand

  data class Replace(
      val sourceRoutineId: Long,
      val sourceRoutineSyncId: String,
      val expectedUpdatedAt: Long,
      val expectedSourceFingerprint: String,
      val predictedTargetFingerprint: String,
      val replacementExercises: List<RoutineExerciseEntity>,
      /** Kept by the UI for its transient confirmation context; it is not a durable journal. */
      val operationUuid: String,
  ) : CompletedWorkoutRoutineCommand
}

sealed interface CompletedWorkoutRoutineResult {
  data class Saved(
      val routine: RoutineEntity,
      val replayedWithoutWrite: Boolean,
  ) : CompletedWorkoutRoutineResult

  data class AvailabilityConflict(val exercises: List<ExerciseEntity>) :
      CompletedWorkoutRoutineResult

  data object ReadOnly : CompletedWorkoutRoutineResult

  data object NotFound : CompletedWorkoutRoutineResult

  data object Conflict : CompletedWorkoutRoutineResult

  data object Failure : CompletedWorkoutRoutineResult
}

/** Canonical aggregate identity used by optimistic replacement and replay checks. */
fun RoutineWithExercises.completedWorkoutFingerprint(
    replacementExercises: List<RoutineExerciseEntity> = exercises.map { it.routineExercise },
): String = buildString {
  fun part(value: Any?) {
    val text = value?.toString() ?: "<null>"
    append(text.length).append(':').append(text).append('|')
  }
  part(routine.id)
  part(routine.syncId)
  part(routine.name)
  part(routine.note)
  part(routine.origin)
  part(routine.archived)
  gyms.map { it.syncId }.sorted().forEach(::part)
  append('#')
  replacementExercises
      .sortedWith(compareBy<RoutineExerciseEntity> { it.position }.thenBy { it.exerciseId })
      .forEach { exercise ->
        part(exercise.exerciseId)
        part(exercise.position)
        part(exercise.restSeconds)
        exercise.plannedSets.forEach { set ->
          part(set.weightKg)
          part(set.reps)
          part(set.durationSec)
          part(set.speedKmh)
          part(set.inclinePct)
        }
        append(';')
      }
}

/** Данные новой записи каталога вместе с полной картой мышц. */
data class NewExerciseConfiguration(
    val exercise: ExerciseEntity,
    val muscles: List<ExerciseMuscleEntity>,
    /** null keeps an existing definition for compatibility; explicit none is a known empty set. */
    val requirements: ExerciseEquipmentRequirements? = null,
)

sealed interface SaveExerciseConfigurationResult {
  data class Saved(val exercise: ExerciseEntity) : SaveExerciseConfigurationResult

  data class Conflict(val details: GymConfigurationConflict) : SaveExerciseConfigurationResult

  data object Failure : SaveExerciseConfigurationResult
}

data class RoutineDeletion(
    val syncId: String,
    val updatedAt: Long,
)

/**
 * Доменная граница управления залами. UI не зависит от Room или текущего транспорта синхронизации:
 * локальную реализацию можно позднее заменить серверной без изменения экранов.
 */
interface GymRepository {
  fun observeGyms(): Flow<List<GymConfiguration>>

  fun observeExerciseCatalog(): Flow<List<ExerciseEntity>>

  suspend fun getGym(id: String): GymConfiguration?

  suspend fun saveGym(
      id: String?,
      name: String,
      exerciseIds: Set<Long>,
  ): SaveGymResult

  /** Explicit inventory save turns a legacy gym into a configured equipment inventory. */
  suspend fun saveGymInventory(
      id: String?,
      name: String,
      equipmentIds: Set<String>,
  ): SaveGymResult = saveGym(id, name, emptySet())

  suspend fun requirementsFor(exercise: ExerciseEntity): ExerciseEquipmentRequirements =
      if (exercise.equipmentRequirementState == EquipmentRequirementState.UNKNOWN) {
        ExerciseEquipmentRequirements.UnknownLegacy
      } else {
        ExerciseEquipmentRequirements.ExplicitNone
      }

  /** Creates or edits one exercise and validates all requested configured gyms atomically. */
  suspend fun saveExerciseConfiguration(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
      workoutId: String? = null,
  ): SaveExerciseConfigurationResult = SaveExerciseConfigurationResult.Failure

  suspend fun deleteGym(id: String): DeleteGymResult

  /** Полный каталог при пустом наборе, иначе пересечение упражнений указанных залов. */
  fun observeAvailableExercises(gymIds: Set<String>): Flow<List<ExerciseEntity>> =
      observeExerciseCatalog()

  /** Возвращает упражнения, отсутствующие хотя бы в одном из указанных залов. */
  suspend fun unavailableExercises(
      gymIds: Set<String>,
      exerciseIds: Set<Long>,
  ): List<ExerciseEntity> = emptyList()

  /** Каталог, мышцы и связи с залами создаются одной транзакцией. */
  suspend fun createExerciseAndAssign(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
  ): ExerciseEntity? = null

  /** То же создание плюс вставка в активную тренировку в рамках одной транзакции. */
  suspend fun createExerciseAssignAndAddToWorkout(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
      workoutId: String,
  ): ExerciseEntity? = null

  /** Правка каталога/мышц и, при необходимости, добавление в залы — одна транзакция. */
  suspend fun updateExerciseAndAssign(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
  ): ExerciseEntity? = null

  /** Подтверждённая AI-запись обновляется, назначается залам и добавляется в workout атомарно. */
  suspend fun updateExerciseAssignAndAddToWorkout(
      configuration: NewExerciseConfiguration,
      gymIds: Set<String>,
      workoutId: String,
  ): ExerciseEntity? = null

  /** Явно включает уже существующую запись во все выбранные залы. */
  suspend fun assignExerciseToGyms(exerciseId: Long, gymIds: Set<String>): Boolean = false

  /** Программа, её упражнения и связи с залами сохраняются атомарно после валидации. */
  suspend fun saveRoutineConfiguration(
      draft: RoutineConfigurationDraft,
  ): SaveRoutineConfigurationResult = SaveRoutineConfigurationResult.Failure

  /** Atomically creates or conditionally replaces a routine from completed workout sets. */
  suspend fun saveCompletedWorkoutRoutine(
      command: CompletedWorkoutRoutineCommand,
  ): CompletedWorkoutRoutineResult =
      when (command) {
        is CompletedWorkoutRoutineCommand.Create ->
            when (val result = saveRoutineConfiguration(command.draft)) {
              is SaveRoutineConfigurationResult.Saved ->
                  CompletedWorkoutRoutineResult.Saved(result.routine, replayedWithoutWrite = false)
              is SaveRoutineConfigurationResult.Conflict ->
                  CompletedWorkoutRoutineResult.AvailabilityConflict(result.exercises)
              SaveRoutineConfigurationResult.GymNotFound -> CompletedWorkoutRoutineResult.NotFound
              SaveRoutineConfigurationResult.Failure -> CompletedWorkoutRoutineResult.Failure
            }
        is CompletedWorkoutRoutineCommand.Replace -> CompletedWorkoutRoutineResult.Failure
      }

  /** Создаёт полную копию программы, упражнений и залов одной транзакцией. */
  suspend fun duplicateRoutine(sourceRoutineId: Long, name: String? = null): RoutineEntity? = null

  /** Клонирует зал с его инвентарём и legacy-связями без изменения исходного зала. */
  suspend fun cloneGym(sourceGymId: String, name: String): SaveGymResult = SaveGymResult.Failure

  /** Удаляет программу и фиксирует durable tombstone одной транзакцией. */
  suspend fun deleteRoutine(routineId: Long): RoutineDeletion? = null
}

/** Беззаловый fallback сохраняет прежнее поведение прямых ViewModel unit-тестов. */
object NoOpGymRepository : GymRepository {
  override fun observeGyms(): Flow<List<GymConfiguration>> =
      kotlinx.coroutines.flow.flowOf(emptyList())

  override fun observeExerciseCatalog(): Flow<List<ExerciseEntity>> =
      kotlinx.coroutines.flow.flowOf(emptyList())

  override suspend fun getGym(id: String): GymConfiguration? = null

  override suspend fun saveGym(
      id: String?,
      name: String,
      exerciseIds: Set<Long>,
  ): SaveGymResult = SaveGymResult.Failure

  override suspend fun deleteGym(id: String): DeleteGymResult = DeleteGymResult.NotFound
}
