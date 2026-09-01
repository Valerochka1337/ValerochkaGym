package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymEntity
import com.valerochka1337.valerochkagym.data.db.entity.GymExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineGymEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutGymEntity
import com.valerochka1337.valerochkagym.data.db.relation.GymWithExercises
import kotlinx.coroutines.flow.Flow

@Dao
interface GymDao {

    @Query("SELECT * FROM gyms ORDER BY name COLLATE NOCASE ASC")
    fun observeGyms(): Flow<List<GymEntity>>

    @Transaction
    @Query("SELECT * FROM gyms WHERE id = :id")
    fun observeGymWithExercises(id: Long): Flow<GymWithExercises?>

    @Query("SELECT * FROM gyms ORDER BY name COLLATE NOCASE ASC")
    suspend fun getGyms(): List<GymEntity>

    @Query("SELECT * FROM gyms WHERE id = :id")
    suspend fun getGym(id: Long): GymEntity?

    @Query("SELECT * FROM gyms WHERE syncId = :syncId")
    suspend fun getGymBySyncId(syncId: String): GymEntity?

    @Transaction
    @Query("SELECT * FROM gyms WHERE id = :id")
    suspend fun getGymWithExercises(id: Long): GymWithExercises?

    @Insert
    suspend fun insertGym(gym: GymEntity): Long

    @Update
    suspend fun updateGym(gym: GymEntity)

    /** Returns the number of deleted rows; foreign keys may reject a linked routine. */
    @Query("DELETE FROM gyms WHERE id = :id")
    suspend fun deleteGym(id: Long): Int

    @Query("SELECT exerciseId FROM gym_exercises WHERE gymId = :gymId ORDER BY exerciseId")
    suspend fun getGymExerciseIds(gymId: Long): List<Long>

    @Query(
        "SELECT * FROM gym_exercises " +
            "WHERE gymId IN (:gymIds) ORDER BY gymId ASC, exerciseId ASC",
    )
    fun observeGymExerciseIds(gymIds: List<Long>): Flow<List<GymExerciseEntity>>

    @Insert
    suspend fun insertGymExercises(links: List<GymExerciseEntity>)

    @Query("DELETE FROM gym_exercises WHERE gymId = :gymId")
    suspend fun deleteGymExercises(gymId: Long)

    @Transaction
    suspend fun replaceGymExercises(gymId: Long, exerciseIds: List<Long>) {
        deleteGymExercises(gymId)
        val links = exerciseIds.distinct().map { exerciseId -> GymExerciseEntity(gymId, exerciseId) }
        if (links.isNotEmpty()) insertGymExercises(links)
    }

    /**
     * Exercises present in every selected gym. Callers handle an empty [gymIds] list as the
     * unrestricted global catalogue and pass the number of distinct selected IDs as [gymCount].
     */
    @Query(
        """
        SELECT e.*
        FROM exercises e
        INNER JOIN gym_exercises ge ON ge.exerciseId = e.id
        WHERE ge.gymId IN (:gymIds)
        GROUP BY e.id
        HAVING COUNT(DISTINCT ge.gymId) = :gymCount
        ORDER BY e.name COLLATE NOCASE ASC
        """,
    )
    suspend fun getAvailableExercises(gymIds: List<Long>, gymCount: Int): List<ExerciseEntity>

    @Query(
        """
        SELECT g.* FROM gyms g
        INNER JOIN routine_gyms rg ON rg.gymId = g.id
        WHERE rg.routineId = :routineId
        ORDER BY g.name COLLATE NOCASE ASC
        """,
    )
    suspend fun getGymsForRoutine(routineId: Long): List<GymEntity>

    @Insert
    suspend fun insertRoutineGyms(links: List<RoutineGymEntity>)

    @Query("DELETE FROM routine_gyms WHERE routineId = :routineId")
    suspend fun deleteRoutineGyms(routineId: Long)

    @Transaction
    suspend fun replaceRoutineGyms(routineId: Long, gymIds: List<Long>) {
        deleteRoutineGyms(routineId)
        val links = gymIds.distinct().map { gymId -> RoutineGymEntity(routineId, gymId) }
        if (links.isNotEmpty()) insertRoutineGyms(links)
    }

    @Query(
        """
        SELECT g.* FROM gyms g
        INNER JOIN workout_gyms wg ON wg.gymId = g.id
        WHERE wg.workoutId = :workoutId
        ORDER BY g.name COLLATE NOCASE ASC
        """,
    )
    suspend fun getGymsForWorkout(workoutId: String): List<GymEntity>

    @Insert
    suspend fun insertWorkoutGyms(links: List<WorkoutGymEntity>)

    @Query("DELETE FROM workout_gyms WHERE workoutId = :workoutId")
    suspend fun deleteWorkoutGyms(workoutId: String)

    @Transaction
    suspend fun replaceWorkoutGyms(workoutId: String, gymIds: List<Long>) {
        deleteWorkoutGyms(workoutId)
        val links = gymIds.distinct().map { gymId -> WorkoutGymEntity(workoutId, gymId) }
        if (links.isNotEmpty()) insertWorkoutGyms(links)
    }

    /** Routines that must be resolved before deleting this gym. */
    @Query(
        """
        SELECT r.* FROM routines r
        INNER JOIN routine_gyms rg ON rg.routineId = r.id
        WHERE rg.gymId = :gymId
        ORDER BY r.name COLLATE NOCASE ASC
        """,
    )
    suspend fun getLinkedRoutines(gymId: Long): List<RoutineEntity>

    /** Active workouts whose stable gym snapshot currently depends on this gym. */
    @Query(
        """
        SELECT w.* FROM workouts w
        INNER JOIN workout_gyms wg ON wg.workoutId = w.id
        WHERE wg.gymId = :gymId AND w.finishedAt IS NULL
        ORDER BY w.startedAt DESC
        """,
    )
    suspend fun getLinkedActiveWorkouts(gymId: Long): List<WorkoutEntity>
}
