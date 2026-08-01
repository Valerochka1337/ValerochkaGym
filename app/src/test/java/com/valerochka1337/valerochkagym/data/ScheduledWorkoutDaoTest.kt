package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for [com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao.observeAll]. */
class ScheduledWorkoutDaoTest : RoomDaoTest() {

    @Test
    fun `observeAll returns past and future scheduled workouts ordered by time`() = runTest {
        val dao = db.scheduledWorkoutDao()
        val routineId = db.routineDao().upsertRoutine(RoutineEntity(name = "Ноги"))
        dao.insert(ScheduledWorkoutEntity(routineId = routineId, dateTimeMillis = 2_000, calendarEventId = "future"))
        dao.insert(ScheduledWorkoutEntity(routineId = routineId, dateTimeMillis = 1_000, calendarEventId = "past"))

        val all = dao.observeAll().first()

        assertEquals(2, all.size)
        assertEquals(listOf("past", "future"), all.map { it.scheduled.calendarEventId })
        assertEquals("Ноги", all.first().routineName)
    }

    @Test
    fun `observeAll is empty when nothing is scheduled`() = runTest {
        assertEquals(emptyList<Any>(), db.scheduledWorkoutDao().observeAll().first())
    }
}
