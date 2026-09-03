package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    /**
     * Read-only catalog history. A row represents a base exercise once per finished workout,
     * and requires at least one completed set so abandoned exercise rows cannot affect ranking.
     */
    @Query(
        """
        SELECT DISTINCT we.exerciseId AS exerciseId, w.id AS workoutId, w.finishedAt AS finishedAt
        FROM workout_exercises we
        JOIN workouts w ON w.id = we.workoutId
        JOIN workout_sets ws ON ws.workoutExerciseId = we.id
        WHERE w.finishedAt IS NOT NULL AND ws.isCompleted = 1
        """,
    )
    fun observeFinishedExerciseHistory(): Flow<List<ExerciseWorkoutHistoryRow>>

    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity)

    @Insert
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long

    @Insert
    suspend fun insertSet(set: WorkoutSetEntity): Long

    @Insert
    suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long>

    @Update
    suspend fun updateSet(set: WorkoutSetEntity)

    /** Обновляет несколько строк упражнений одним вызовом Room. Транзакцию задаёт репозиторий. */
    @Update
    suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>)

    @Query("UPDATE workout_sets SET isCompleted = :completed, completedAt = :completedAt WHERE id = :setId")
    suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?)

    @Query("SELECT * FROM workout_sets WHERE id = :setId")
    suspend fun getSet(setId: Long): WorkoutSetEntity?

    @Query("SELECT * FROM workout_sets WHERE workoutExerciseId = :workoutExerciseId ORDER BY setIndex ASC")
    suspend fun getSetsForWorkoutExercise(workoutExerciseId: Long): List<WorkoutSetEntity>

    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY position ASC")
    suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity>

    @Query("UPDATE workouts SET finishedAt = :finishedAt WHERE id = :id")
    suspend fun setFinishedAt(id: String, finishedAt: Long)

    @Transaction
    @Query("SELECT * FROM workouts WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveWorkout(): Flow<WorkoutFull?>

    @Query("SELECT id FROM workouts WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveWorkoutId(): String?

    @Query("SELECT * FROM workouts WHERE finishedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>>

    /**
     * Все выполненные подходы завершённых тренировок одной плоской проекцией — вход вкладки
     * «Анализы» (см. [AnalyticsSetRow]). Незавершённая тренировка в аналитику не попадает:
     * её объём ещё меняется. Порядок по времени отметки, чтобы скользящие окна считались
     * без досортировки.
     */
    @Query(
        """
        SELECT w.id AS workoutId,
               we.exerciseId AS exerciseId,
               e.name AS exerciseName,
               e.type AS exerciseType,
               ws.weightKg AS weightKg,
               ws.reps AS reps,
               ws.durationSec AS durationSec,
               ws.speedKmh AS speedKmh,
               ws.inclinePct AS inclinePct,
               COALESCE(ws.completedAt, w.startedAt) AS completedAt
        FROM workout_sets ws
        JOIN workout_exercises we ON we.id = ws.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        JOIN exercises e ON e.id = we.exerciseId
        WHERE ws.isCompleted = 1 AND w.finishedAt IS NOT NULL
        ORDER BY completedAt ASC
        """,
    )
    fun observeCompletedSets(): Flow<List<AnalyticsSetRow>>

    @Transaction
    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getWorkoutFull(id: String): WorkoutFull?

    /**
     * Completed sets of [exerciseId] taken from the most recent finished workout that contains it
     * with at least one completed set. The EXISTS clause skips finished workouts where the exercise
     * was added but never completed, so the fallback is the latest workout with real data. Sets are
     * then filtered to completed and ordered by setIndex.
     */
    @Query(
        """
        SELECT ws.* FROM workout_sets ws
        WHERE ws.workoutExerciseId = (
            SELECT we.id FROM workout_exercises we
            JOIN workouts w ON w.id = we.workoutId
            WHERE we.exerciseId = :exerciseId AND w.finishedAt IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM workout_sets s
                  WHERE s.workoutExerciseId = we.id AND s.isCompleted = 1
              )
            ORDER BY w.finishedAt DESC
            LIMIT 1
        )
        AND ws.isCompleted = 1
        ORDER BY ws.setIndex ASC
        """,
    )
    suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity>

    /**
     * Maximum lifted weight over completed sets of [exerciseId] across all finished workouts,
     * excluding [excludeWorkoutId]. Returns null when there is no such set.
     */
    @Query(
        """
        SELECT MAX(ws.weightKg) FROM workout_sets ws
        JOIN workout_exercises we ON we.id = ws.workoutExerciseId
        JOIN workouts w ON w.id = we.workoutId
        WHERE we.exerciseId = :exerciseId
          AND w.finishedAt IS NOT NULL
          AND w.id != :excludeWorkoutId
          AND ws.isCompleted = 1
        """,
    )
    suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double?

    @Query("UPDATE workouts SET uploadStatus = :status, uploadError = :error WHERE id = :workoutId")
    suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?)

    /** Реактивно отдаёт саму запись тренировки — деталям нужен живой статус выгрузки. */
    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeWorkout(id: String): Flow<WorkoutEntity?>

    /**
     * Id завершённых тренировок, ещё не выгруженных ([UploadStatus.PENDING]) или упавших
     * ([UploadStatus.FAILED]) — для массовой повторной выгрузки «Выгрузить всё».
     */
    @Query(
        """
        SELECT id FROM workouts
        WHERE finishedAt IS NOT NULL AND uploadStatus IN ('PENDING', 'FAILED')
        ORDER BY startedAt DESC
        """,
    )
    suspend fun getFinishedNotUploaded(): List<String>

    /** Id всех тренировок (для дедупликации импорта по `workout_id`). */
    @Query("SELECT id FROM workouts")
    suspend fun getExistingWorkoutIds(): List<String>

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: String)

    @Query("DELETE FROM workout_sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: Long)
}
