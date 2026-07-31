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

    @Transaction
    @Query("SELECT * FROM workouts WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveWorkout(): Flow<WorkoutFull?>

    @Query("SELECT id FROM workouts WHERE finishedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveWorkoutId(): String?

    @Query("SELECT * FROM workouts WHERE finishedAt IS NOT NULL ORDER BY startedAt DESC")
    fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>>

    @Transaction
    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getWorkoutFull(id: String): WorkoutFull?

    /**
     * Completed sets of [exerciseId] taken from the most recent finished workout that contains it.
     * The subquery selects the workout_exercises row of that exercise belonging to the finished
     * workout with the latest finishedAt; sets are then filtered to completed and ordered by setIndex.
     */
    @Query(
        """
        SELECT ws.* FROM workout_sets ws
        WHERE ws.workoutExerciseId = (
            SELECT we.id FROM workout_exercises we
            JOIN workouts w ON w.id = we.workoutId
            WHERE we.exerciseId = :exerciseId AND w.finishedAt IS NOT NULL
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

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: String)

    @Query("DELETE FROM workout_sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("DELETE FROM workout_exercises WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: Long)
}
