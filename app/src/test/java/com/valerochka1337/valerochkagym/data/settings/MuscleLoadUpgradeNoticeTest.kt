package com.valerochka1337.valerochkagym.data.settings

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuscleLoadUpgradeNoticeTest : RoomDaoTest() {
    @Test
    fun `fresh database does not show notice even with active and completed workouts`() = runTest {
        val exerciseId = insertExercise()
        insertWorkout("active")
        val historyExercise = insertWorkoutExercise(insertWorkout("history", finishedAt = 2_000), exerciseId)
        insertSet(historyExercise, setIndex = 0, weightKg = 80.0, reps = 5, isCompleted = true)

        val notice = RoomMuscleLoadUpgradeNotice(db.muscleLoadUpgradeNoticeDao())

        assertTrue(db.workoutDao().hasCompletedHistory())
        assertFalse(notice.pendingIfNeeded(db.workoutDao().hasCompletedHistory()))
        assertFalse(db.muscleLoadUpgradeNoticeDao().isPending())
    }

    @Test
    fun `upgrade marker is suppressed and acknowledged when only active workout exists`() = runTest {
        val exerciseId = insertExercise()
        val activeExercise = insertWorkoutExercise(insertWorkout("active"), exerciseId)
        insertSet(activeExercise, setIndex = 0, weightKg = 80.0, reps = 5, isCompleted = false)
        insertUpgradeMarker()
        val notice = RoomMuscleLoadUpgradeNotice(db.muscleLoadUpgradeNoticeDao())

        assertFalse(db.workoutDao().hasCompletedHistory())
        assertFalse(notice.pendingIfNeeded(db.workoutDao().hasCompletedHistory()))
        assertFalse(db.muscleLoadUpgradeNoticeDao().isPending())
    }

    @Test
    fun `upgrade marker redelivers with completed history until acknowledgement`() = runTest {
        val exerciseId = insertExercise()
        val historyExercise = insertWorkoutExercise(insertWorkout("history", finishedAt = 2_000), exerciseId)
        insertSet(historyExercise, setIndex = 0, weightKg = 80.0, reps = 5, isCompleted = true)
        insertUpgradeMarker()
        val first = RoomMuscleLoadUpgradeNotice(db.muscleLoadUpgradeNoticeDao())

        assertTrue(first.pendingIfNeeded(db.workoutDao().hasCompletedHistory()))
        assertTrue(RoomMuscleLoadUpgradeNotice(db.muscleLoadUpgradeNoticeDao()).pendingIfNeeded(true))
        first.acknowledge()
        assertFalse(RoomMuscleLoadUpgradeNotice(db.muscleLoadUpgradeNoticeDao()).pendingIfNeeded(true))
        assertFalse(db.muscleLoadUpgradeNoticeDao().isPending())
    }

    private suspend fun insertExercise(): Long = db.exerciseDao().insert(
        ExerciseEntity(
            name = "Упражнение",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
            isCustom = true,
        ),
    )

    private fun insertUpgradeMarker() {
        db.openHelper.writableDatabase.execSQL("INSERT INTO muscle_load_upgrade_notice(id) VALUES(1)")
    }
}
