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
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutVolume
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity)

    @Update
    suspend fun updateWorkout(workout: WorkoutEntity)

    @Insert
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long

    @Update
    suspend fun updateWorkoutExercise(workoutExercise: WorkoutExerciseEntity)

    @Insert
    suspend fun insertSet(set: WorkoutSetEntity): Long

    @Insert
    suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long>

    @Update
    suspend fun updateSet(set: WorkoutSetEntity)

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
     * Суммарный тоннаж (Σ weightKg × reps по выполненным силовым подходам) по каждой тренировке.
     * Один агрегатный запрос для списка истории — без загрузки полных деревьев тренировок.
     * Тренировки без силового объёма в результат не попадают.
     */
    @Query(
        """
        SELECT we.workoutId AS workoutId, SUM(ws.weightKg * ws.reps) AS volume
        FROM workout_sets ws
        JOIN workout_exercises we ON we.id = ws.workoutExerciseId
        JOIN exercises e ON e.id = we.exerciseId
        WHERE ws.isCompleted = 1
          AND e.type = 'STRENGTH'
          AND ws.weightKg IS NOT NULL
          AND ws.reps IS NOT NULL
        GROUP BY we.workoutId
        """,
    )
    fun observeWorkoutVolumes(): Flow<List<WorkoutVolume>>

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

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: String)

    @Query("DELETE FROM workout_sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: Long)
}
