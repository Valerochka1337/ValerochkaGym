package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import kotlinx.coroutines.flow.Flow

/**
 * Сохранённая конфигурация одного зала. [id] — стабильный UUID, пригодный для синхронизации;
 * локальные Room id наружу из репозитория не выходят.
 */
data class GymConfiguration(
    val id: String,
    val name: String,
    val exercises: List<ExerciseEntity>,
)

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

/** Данные новой записи каталога вместе с полной картой мышц. */
data class NewExerciseConfiguration(
    val exercise: ExerciseEntity,
    val muscles: List<ExerciseMuscleEntity>,
)

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

  /** Создаёт полную копию программы, упражнений и залов одной транзакцией. */
  suspend fun duplicateRoutine(sourceRoutineId: Long): RoutineEntity? = null

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
