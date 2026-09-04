package com.valerochka1337.valerochkagym.domain

import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import kotlinx.coroutines.flow.Flow

/**
 * Управление активной (незавершённой) тренировкой. В любой момент времени может существовать не
 * более одной активной тренировки — методы [startFromRoutine]/[startEmpty] защищены от создания
 * второй и возвращают id уже существующей.
 */
interface ActiveWorkoutRepository {

  /**
   * Создаёт тренировку по программе [routineId]: имя = имя программы, упражнения по позициям,
   * подходы из plannedSets с предзаполнением значениями «прошлого раза» (по индексу подхода). Если
   * активная тренировка уже есть — возвращает её id без создания новой.
   */
  suspend fun startFromRoutine(routineId: Long): String

  /** То же, что [startFromRoutine], но без программы: имя «Тренировка», без упражнений. */
  suspend fun startEmpty(): String

  /** Активная тренировка с доменной сортировкой (упражнения по position, подходы по setIndex). */
  fun observeActive(): Flow<WorkoutFull?>

  /** Текущее состояние подхода из БД (для сериализованного read-modify-write степперов). */
  suspend fun getSet(setId: Long): WorkoutSetEntity?

  suspend fun updateSet(set: WorkoutSetEntity)

  suspend fun toggleSetCompleted(setId: Long, completed: Boolean)

  /**
   * Новый подход: setIndex = max + 1, значения — копия последнего подхода упражнения (или пустые).
   */
  suspend fun addSet(workoutExerciseId: Long)

  suspend fun deleteSet(setId: Long)

  /**
   * Добавляет упражнение в тренировку: позиция = max + 1, с одним подходом, предзаполненным первым
   * подходом «прошлого раза» (если есть). Возвращает id нового workout_exercise.
   */
  suspend fun addExercise(workoutId: String, exerciseId: Long): Long

  suspend fun deleteExercise(workoutExerciseId: Long)

  /**
   * Сохраняет полный порядок упражнений активной тренировки. [orderedWorkoutExerciseIds] должен
   * содержать ровно уникальный набор строк [workoutId]; позиции будут перенумерованы с нуля.
   */
  suspend fun reorderExercises(workoutId: String, orderedWorkoutExerciseIds: List<Long>)

  /**
   * Завершает тренировку: удаляет пустые невыполненные подходы, затем упражнения без подходов,
   * затем проставляет finishedAt.
   */
  suspend fun finish(workoutId: String)

  /** Удаляет тренировку целиком (каскад по упражнениям и подходам). */
  suspend fun discard(workoutId: String)
}

/** Старт/добавление блокируется, пока упражнения не появятся во всех выбранных залах. */
class RoutineGymConflictException(
    val exerciseNames: List<String>,
) :
    IllegalStateException(
        "Упражнения недоступны во всех выбранных залах: ${exerciseNames.joinToString()}",
    )

/** Отложенное действие не должно менять уже завершённую или удалённую тренировку. */
class ActiveWorkoutUnavailableException : IllegalStateException("Активная тренировка уже завершена")
