package com.valerochka1337.valerochkagym.ui

import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ScheduledWorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.AnalyticsSetRow
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithCount
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.ScheduledWithRoutine
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.google.CalendarRepository
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.schedule.DayRule
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRecoveryResult
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.ui.calendar.CalendarViewModel
import com.valerochka1337.valerochkagym.ui.calendar.DotStyle
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Unit tests for [CalendarViewModel]. All Room/Google boundaries are hand-written fakes backed by
 * [MutableStateFlow], so state flows emit synchronously under an [UnconfinedTestDispatcher] with no
 * Android framework. `today` is the real system date; date-dependent tests are anchored to `today`
 * (planned) or to a fixed far-past month (completed, which is today-independent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // region grid

    @Test
    fun `grid marks a completed day with a filled dot`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val completedDay = LocalDate.of(2000, 1, 10)
            val vm = viewModel(finished = listOf(finishedWorkout("w1", "Ноги", noon(completedDay))))
            collect(vm)
            vm.showMonth(YearMonth.of(2000, 1))

            val cell = vm.monthUi.value.cells.first { it.date == completedDay }
            assertEquals(DotStyle.Completed, cell.dot)
            assertTrue(cell.inMonth)
        }

    @Test
    fun `grid marks a future ad-hoc day planned and flags today`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val today = LocalDate.now(zone)
            val vm = viewModel(
                adHoc = listOf(scheduled(1L, routineId = 5L, "Грудь", noon(today))),
            )
            collect(vm)
            vm.showMonth(YearMonth.from(today))

            val cell = vm.monthUi.value.cells.first { it.date == today }
            assertTrue(cell.isToday)
            assertEquals(DotStyle.Planned, cell.dot)
        }

    // endregion

    // region day sheet

    @Test
    fun `day sheet lists completed workouts of the selected day`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val day = LocalDate.of(2000, 1, 10)
            val vm = viewModel(finished = listOf(finishedWorkout("w1", "Ноги", noon(day))))
            collect(vm)
            vm.onDaySelected(day)

            val sheet = vm.daySheet.value!!
            assertEquals(1, sheet.completed.size)
            assertEquals("Ноги", sheet.completed.single().name)
            assertEquals("w1", sheet.completed.single().id)
        }

    @Test
    fun `day sheet surfaces a recurring rule with the routine name`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val today = LocalDate.now(zone)
            val vm = viewModel(
                routines = listOf(routineWithCount(7L, "Спина")),
                weekly = WeeklySchedule(listOf(DayRule(isoDay = today.dayOfWeek.value, routineId = 7L, hour = 8, minute = 30))),
            )
            collect(vm)
            vm.onDaySelected(today)

            val recurring = vm.daySheet.value!!.recurring!!
            assertEquals("Спина", recurring.routineName)
            assertEquals("08:30", recurring.timeLabel)
            assertTrue(recurring.canStart) // today
        }

    // endregion

    // region month navigation

    @Test
    fun `next and prev shift the displayed month`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val vm = viewModel()
            collect(vm)
            vm.showMonth(YearMonth.of(2026, 8))

            vm.nextMonth()
            assertEquals(YearMonth.of(2026, 9), vm.monthUi.value.yearMonth)
            vm.prevMonth()
            vm.prevMonth()
            assertEquals(YearMonth.of(2026, 7), vm.monthUi.value.yearMonth)
        }

    // endregion

    // region scheduling

    @Test
    fun `schedule in the past emits the guard message and never calls the repository`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val calendar = FakeCalendarRepository()
            val vm = viewModel(calendar = calendar)
            collect(vm)

            vm.schedule(routineId = 1L, dateTimeMillis = 1_000L)

            assertEquals("Время уже прошло — выберите будущий момент", vm.events.first())
            assertEquals(0, calendar.scheduleCalls)
        }

    @Test
    fun `startAdHoc starts the routine then cancels its event`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val active = FakeActiveWorkoutRepository()
            val calendar = FakeCalendarRepository()
            val vm = viewModel(active = active, calendar = calendar)
            collect(vm)

            vm.startAdHoc(
                com.valerochka1337.valerochkagym.ui.calendar.AdHocUi(
                    scheduledId = 3L, routineId = 5L, routineName = "Ноги", timeLabel = "18:00", canStart = true,
                ),
            )

            assertEquals(1, active.startFromRoutineCalls)
            assertEquals(listOf(3L), calendar.cancelledIds)
        }

    @Test
    fun `startRecurring starts the routine without cancelling anything`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val active = FakeActiveWorkoutRepository()
            val calendar = FakeCalendarRepository()
            val vm = viewModel(active = active, calendar = calendar)
            collect(vm)

            vm.startRecurring(routineId = 5L)

            assertEquals(1, active.startFromRoutineCalls)
            assertTrue(calendar.cancelledIds.isEmpty())
        }

    @Test
    fun `saveSchedule delegates and surfaces the success message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val weekly = FakeWeeklyScheduleRepository()
            val vm = viewModel(weeklyRepo = weekly)
            collect(vm)

            val schedule = WeeklySchedule(listOf(DayRule(isoDay = 1, routineId = 2L, hour = 18, minute = 0)))
            vm.saveSchedule(schedule)

            assertEquals("Расписание сохранено", vm.events.first())
            assertEquals(schedule, weekly.saved)
        }

    @Test
    fun `clearSchedule surfaces a NeedsConsent message`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val weekly = FakeWeeklyScheduleRepository(clearResult = ScheduleResult.NeedsConsent)
            val vm = viewModel(weeklyRepo = weekly)
            collect(vm)

            vm.clearSchedule()

            assertEquals("Настройте доступ к Google в настройках", vm.events.first())
            assertTrue(weekly.cleared)
        }

    @Test
    fun `weeklySchedule exposes the persisted template`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val schedule = WeeklySchedule(listOf(DayRule(isoDay = 4, routineId = 9L, hour = 7, minute = 15)))
            val vm = viewModel(weeklyRepo = FakeWeeklyScheduleRepository(initial = schedule))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.weeklySchedule.collect {} }

            assertEquals(schedule, vm.weeklySchedule.value)
        }

    @Test
    fun `rapid save and clear share one busy gate`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val weekly = FakeWeeklyScheduleRepository(suspendSave = true)
            val vm = viewModel(weeklyRepo = weekly)

            vm.saveSchedule(WeeklySchedule(listOf(DayRule(1, 2, 18, 0))))
            runCurrent()
            assertTrue(vm.isScheduleBusy.value)
            vm.clearSchedule()
            runCurrent()
            assertEquals(1, weekly.saveCalls)
            assertEquals(0, weekly.clearCalls)

            weekly.releaseSave.complete(Unit)
            runCurrent()
            assertTrue(!vm.isScheduleBusy.value)
        }

    @Test
    fun `rapid save and save invoke repository once`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val weekly = FakeWeeklyScheduleRepository(suspendSave = true)
            val vm = viewModel(weeklyRepo = weekly)
            val schedule = WeeklySchedule(listOf(DayRule(1, 2, 18, 0)))

            vm.saveSchedule(schedule)
            vm.saveSchedule(schedule)
            runCurrent()

            assertEquals(1, weekly.saveCalls)
            weekly.releaseSave.complete(Unit)
            runCurrent()
            assertFalse(vm.isScheduleBusy.value)
        }

    @Test
    fun `rapid clear and clear invoke repository once`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val weekly = FakeWeeklyScheduleRepository(suspendClear = true)
            val vm = viewModel(weeklyRepo = weekly)

            vm.clearSchedule()
            vm.clearSchedule()
            runCurrent()

            assertEquals(1, weekly.clearCalls)
            weekly.releaseClear.complete(Unit)
            runCurrent()
            assertFalse(vm.isScheduleBusy.value)
        }

    @Test
    fun `schedule busy resets after repository failure and cancellation`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val failureRepo = FakeWeeklyScheduleRepository(
                saveResult = ScheduleResult.Failure("Старое расписание сохранено"),
            )
            val failureVm = viewModel(weeklyRepo = failureRepo)
            failureVm.saveSchedule(WeeklySchedule(listOf(DayRule(1, 2, 18, 0))))
            assertEquals("Старое расписание сохранено", failureVm.events.first())
            assertFalse(failureVm.isScheduleBusy.value)

            val cancelledRepo = FakeWeeklyScheduleRepository(
                saveThrowable = CancellationException("cancelled"),
            )
            val cancelledVm = viewModel(weeklyRepo = cancelledRepo)
            cancelledVm.saveSchedule(WeeklySchedule(listOf(DayRule(1, 2, 18, 0))))
            runCurrent()
            assertFalse(cancelledVm.isScheduleBusy.value)
        }

    // endregion

    // region harness

    private fun viewModel(
        finished: List<WorkoutEntity> = emptyList(),
        adHoc: List<ScheduledWithRoutine> = emptyList(),
        routines: List<RoutineWithCount> = emptyList(),
        weekly: WeeklySchedule = WeeklySchedule(),
        calendar: FakeCalendarRepository = FakeCalendarRepository(),
        active: FakeActiveWorkoutRepository = FakeActiveWorkoutRepository(),
        weeklyRepo: FakeWeeklyScheduleRepository = FakeWeeklyScheduleRepository(initial = weekly),
    ): CalendarViewModel = CalendarViewModel(
        workoutDao = FakeWorkoutDao(finished),
        scheduledWorkoutDao = FakeScheduledWorkoutDao(adHoc),
        routineDao = FakeRoutineDao(routines),
        calendarRepository = calendar,
        weeklyScheduleRepository = weeklyRepo,
        activeWorkoutRepository = active,
    )

    /** Keep every `WhileSubscribed` state flow hot so `.value` reflects the latest emission. */
    private fun TestScope.collect(vm: CalendarViewModel) {
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.monthUi.collect {} }
        backgroundScope.launch(d) { vm.daySheet.collect {} }
        backgroundScope.launch(d) { vm.routines.collect {} }
    }

    private fun finishedWorkout(id: String, name: String, startedAt: Long) =
        WorkoutEntity(id = id, name = name, startedAt = startedAt, finishedAt = startedAt + 3_600_000)

    private fun scheduled(id: Long, routineId: Long, name: String, millis: Long) =
        ScheduledWithRoutine(
            scheduled = ScheduledWorkoutEntity(id = id, routineId = routineId, dateTimeMillis = millis, calendarEventId = "e$id"),
            routineName = name,
        )

    private fun routineWithCount(id: Long, name: String) =
        RoutineWithCount(routine = RoutineEntity(id = id, name = name), exerciseCount = 0)

    private class FakeWorkoutDao(private val finished: List<WorkoutEntity>) : WorkoutDao {
        override fun observeFinishedExerciseHistory() = flowOf(emptyList<com.valerochka1337.valerochkagym.data.db.relation.ExerciseWorkoutHistoryRow>())
        override fun observeFinishedWorkouts(): Flow<List<WorkoutEntity>> = MutableStateFlow(finished)
        override suspend fun insertWorkout(workout: WorkoutEntity) = Unit
        override suspend fun insertWorkoutExercise(workoutExercise: WorkoutExerciseEntity): Long = 0
        override suspend fun insertSet(set: WorkoutSetEntity): Long = 0
        override suspend fun insertSets(sets: List<WorkoutSetEntity>): List<Long> = emptyList()
        override suspend fun updateSet(set: WorkoutSetEntity) = Unit
        override suspend fun updateWorkoutExercises(exercises: List<WorkoutExerciseEntity>) = Unit
        override suspend fun setSetCompleted(setId: Long, completed: Boolean, completedAt: Long?) = Unit
        override suspend fun getSet(setId: Long): WorkoutSetEntity? = null
        override suspend fun getSetsForWorkoutExercise(workoutExerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExerciseEntity> = emptyList()
        override suspend fun setFinishedAt(id: String, finishedAt: Long) = Unit
        override fun observeActiveWorkout(): Flow<WorkoutFull?> = flowOf(null)
        override suspend fun getActiveWorkoutId(): String? = null
        override fun observeCompletedSets(): Flow<List<AnalyticsSetRow>> = flowOf(emptyList())
        override suspend fun getWorkoutFull(id: String): WorkoutFull? = null
        override suspend fun lastCompletedSetsForExercise(exerciseId: Long): List<WorkoutSetEntity> = emptyList()
        override suspend fun maxCompletedWeight(exerciseId: Long, excludeWorkoutId: String): Double? = null
        override suspend fun setUploadStatus(workoutId: String, status: UploadStatus, error: String?) = Unit
        override fun observeWorkout(id: String): Flow<WorkoutEntity?> = flowOf(null)
        override suspend fun getFinishedNotUploaded(): List<String> = emptyList()
        override suspend fun getExistingWorkoutIds(): List<String> = emptyList()
        override suspend fun deleteWorkout(id: String) = Unit
        override suspend fun deleteSet(id: Long) = Unit
        override suspend fun deleteWorkoutExercise(id: Long) = Unit
    }

    private class FakeScheduledWorkoutDao(private val all: List<ScheduledWithRoutine>) : ScheduledWorkoutDao {
        override fun observeAll(): Flow<List<ScheduledWithRoutine>> = MutableStateFlow(all)
        override suspend fun insert(scheduled: ScheduledWorkoutEntity): Long = 0
        override suspend fun delete(id: Long) = Unit
        override suspend fun getById(id: Long): ScheduledWorkoutEntity? = null
    }

    private class FakeRoutineDao(private val list: List<RoutineWithCount>) : RoutineDao {
        override fun observeRoutinesWithCount(): Flow<List<RoutineWithCount>> = MutableStateFlow(list)
        override fun observeRoutinesFull(): Flow<List<RoutineWithExercises>> = flowOf(emptyList())
        override suspend fun getRoutineWithExercises(id: Long): RoutineWithExercises? = null
        override suspend fun getRoutineName(id: Long): String? = list.find { it.routine.id == id }?.routine?.name
        override suspend fun upsertRoutine(routine: RoutineEntity): Long = 0
        override suspend fun deleteRoutine(id: Long) = Unit
        override suspend fun insertRoutineExercises(routineExercises: List<RoutineExerciseEntity>): List<Long> = emptyList()
        override suspend fun deleteRoutineExercises(routineId: Long) = Unit
    }

    private class FakeCalendarRepository : CalendarRepository {
        var scheduleCalls = 0
            private set
        val cancelledIds = mutableListOf<Long>()
        override suspend fun schedule(routineId: Long, dateTimeMillis: Long): ScheduleResult {
            scheduleCalls++
            return ScheduleResult.Success
        }
        override suspend fun cancel(scheduledId: Long): ScheduleResult {
            cancelledIds += scheduledId
            return ScheduleResult.Success
        }
    }

    private class FakeWeeklyScheduleRepository(
        initial: WeeklySchedule = WeeklySchedule(),
        private val saveResult: ScheduleResult = ScheduleResult.Success,
        private val clearResult: ScheduleResult = ScheduleResult.Success,
        private val suspendSave: Boolean = false,
        private val suspendClear: Boolean = false,
        private val saveThrowable: Throwable? = null,
    ) : WeeklyScheduleRepository {
        private val state = MutableStateFlow(initial)
        var saved: WeeklySchedule? = null
            private set
        var cleared = false
            private set
        var saveCalls = 0
        var clearCalls = 0
        val releaseSave = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        override fun observe(): Flow<WeeklySchedule> = state
        override suspend fun save(schedule: WeeklySchedule): ScheduleResult {
            saveCalls++
            saved = schedule
            if (suspendSave) releaseSave.await()
            saveThrowable?.let { throw it }
            state.value = schedule
            return saveResult
        }
        override suspend fun clear(): ScheduleResult {
            clearCalls++
            cleared = true
            if (suspendClear) releaseClear.await()
            state.value = WeeklySchedule()
            return clearResult
        }
        override suspend fun resumePendingOperation(): WeeklyScheduleRecoveryResult =
            WeeklyScheduleRecoveryResult.NothingPending
    }

    private class FakeActiveWorkoutRepository : ActiveWorkoutRepository {
        var startFromRoutineCalls = 0
            private set
        override suspend fun startFromRoutine(routineId: Long): String {
            startFromRoutineCalls++
            return "workout"
        }
        override suspend fun startEmpty(): String = "workout"
        override fun observeActive(): Flow<WorkoutFull?> = flowOf(null)
        override suspend fun getSet(setId: Long): WorkoutSetEntity? = null
        override suspend fun updateSet(set: WorkoutSetEntity) = Unit
        override suspend fun toggleSetCompleted(setId: Long, completed: Boolean) = Unit
        override suspend fun addSet(workoutExerciseId: Long) = Unit
        override suspend fun deleteSet(setId: Long) = Unit
        override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = 0
        override suspend fun deleteExercise(workoutExerciseId: Long) = Unit
        override suspend fun reorderExercises(workoutId: String, orderedWorkoutExerciseIds: List<Long>) = Unit
        override suspend fun finish(workoutId: String) = Unit
        override suspend fun discard(workoutId: String) = Unit
    }

    // endregion
}
