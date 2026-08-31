package com.valerochka1337.valerochkagym.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.dao.WorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.relation.ScheduledWithRoutine
import com.valerochka1337.valerochkagym.data.google.CalendarRepository
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.schedule.WeeklySchedule
import com.valerochka1337.valerochkagym.data.schedule.WeeklyScheduleRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.RoutineGymConflictException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/** Стиль точки в ячейке дня: без точки, залитая (тренировка была) или контурная (запланировано). */
enum class DotStyle { None, Completed, Planned }

/** Одна ячейка сетки месяца. [inMonth] == false — день соседнего месяца (заглушка). */
data class DayCellUi(
    val date: LocalDate,
    val inMonth: Boolean,
    val isToday: Boolean,
    val dot: DotStyle,
)

/** Состояние отображаемого месяца: заголовок «Август 2026» и 42 ячейки (Пн-первый). */
data class MonthUi(
    val yearMonth: YearMonth,
    val title: String,
    val cells: List<DayCellUi>,
)

/** Завершённая тренировка в шторке дня; тап открывает детали по [id]. */
data class CompletedWorkoutUi(val id: String, val name: String, val timeLabel: String)

/** Ad-hoc запланированная тренировка в шторке; [canStart] == true, когда время уже наступило. */
data class AdHocUi(
    val scheduledId: Long,
    val routineId: Long,
    val routineName: String,
    val timeLabel: String,
    val canStart: Boolean,
)

/** Правило недельного расписания на выбранный день; [canStart] == true только сегодня. */
data class RecurringUi(
    val routineId: Long,
    val routineName: String,
    val timeLabel: String,
    val canStart: Boolean,
)

/** Содержимое нижней шторки выбранного дня — агрегат всех секций (может быть несколько сразу). */
data class DaySheetUi(
    val date: LocalDate,
    val title: String,
    val completed: List<CompletedWorkoutUi>,
    val adHoc: List<AdHocUi>,
    val recurring: RecurringUi?,
    val allowPlan: Boolean,
)

/** Программа для пикера при планировании ad-hoc и в редакторе расписания. */
data class RoutinePickUi(val id: Long, val name: String)

/** Снимок реактивных данных календаря на один тик — из него строятся сетка и шторка. */
private data class CalendarData(
    val finished: List<WorkoutEntity>,
    val adHoc: List<ScheduledWithRoutine>,
    val weekly: WeeklySchedule,
    val routineNames: Map<Long, String>,
    val nowMillis: Long,
)

/**
 * Бэкенд вкладки «Календарь»: месячная сетка (что сделано + что запланировано), нижняя шторка дня и
 * планирование — ad-hoc через [CalendarRepository] и недельное расписание через
 * [WeeklyScheduleRepository]. Завершённые дни группируются в памяти из [WorkoutDao.observeFinishedWorkouts]
 * (как это делала «История»); ad-hoc — из [ScheduledWorkoutDao.observeAll]; повторяющиеся — из расписания.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val workoutDao: WorkoutDao,
    private val scheduledWorkoutDao: ScheduledWorkoutDao,
    private val routineDao: RoutineDao,
    private val calendarRepository: CalendarRepository,
    private val weeklyScheduleRepository: WeeklyScheduleRepository,
    private val activeWorkoutRepository: ActiveWorkoutRepository,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val displayedMonth = MutableStateFlow(YearMonth.now(zone))
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    private var startInFlight = false

    private val _startEvents = Channel<Unit>(Channel.BUFFERED)

    /** «Тренировка создана» — экран навигирует на активную тренировку. */
    val startEvents = _startEvents.receiveAsFlow()

    private val _events = Channel<String>(Channel.BUFFERED)

    /** Текст результата планирования/расписания — экран показывает в снекбаре. */
    val events = _events.receiveAsFlow()

    /** Тикающий раз в минуту снимок данных: `isToday`/`canStart`/точки обновляются вживую. */
    private val calendarData: StateFlow<CalendarData> =
        combine(
            workoutDao.observeFinishedWorkouts(),
            scheduledWorkoutDao.observeAll(),
            weeklyScheduleRepository.observe(),
            routineDao.observeRoutinesWithCount().map { list -> list.associate { it.routine.id to it.routine.name } },
            minuteTick(),
        ) { finished, adHoc, weekly, names, now ->
            CalendarData(finished, adHoc, weekly, names, now)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarData(emptyList(), emptyList(), WeeklySchedule(), emptyMap(), 0L))

    /** Сетка отображаемого месяца. */
    val monthUi: StateFlow<MonthUi> =
        combine(displayedMonth, calendarData) { month, data -> buildMonth(month, data) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildMonth(displayedMonth.value, calendarData.value))

    /** Содержимое шторки выбранного дня; null — шторка закрыта. */
    val daySheet: StateFlow<DaySheetUi?> =
        combine(selectedDate, calendarData) { date, data -> date?.let { buildSheet(it, data) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Список программ для пикеров (планирование + редактор расписания). */
    val routines: StateFlow<List<RoutinePickUi>> =
        routineDao.observeRoutinesWithCount()
            .map { list -> list.map { RoutinePickUi(it.routine.id, it.routine.name) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Текущий сохранённый шаблон недельного расписания — начальное состояние редактора. */
    val weeklySchedule: StateFlow<WeeklySchedule> =
        weeklyScheduleRepository.observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklySchedule())

    fun nextMonth() {
        displayedMonth.value = displayedMonth.value.plusMonths(1)
    }

    fun prevMonth() {
        displayedMonth.value = displayedMonth.value.minusMonths(1)
    }

    fun showMonth(yearMonth: YearMonth) {
        displayedMonth.value = yearMonth
    }

    fun onDaySelected(date: LocalDate) {
        selectedDate.value = date
    }

    fun onSheetDismissed() {
        selectedDate.value = null
    }

    /** Планирует ad-hoc тренировку; прошедшее время отклоняется без обращения к календарю. */
    fun schedule(routineId: Long, dateTimeMillis: Long) {
        viewModelScope.launch {
            if (dateTimeMillis < System.currentTimeMillis()) {
                _events.send(PAST_TIME_MESSAGE)
                return@launch
            }
            _events.send(scheduleResultMessage(calendarRepository.schedule(routineId, dateTimeMillis), success = "Запланировано"))
        }
    }

    /** Отмена ad-hoc тренировки: успех тихий (сетка сама обновится), ошибку — в снекбар. */
    fun cancelAdHoc(scheduledId: Long) {
        viewModelScope.launch {
            when (val result = calendarRepository.cancel(scheduledId)) {
                ScheduleResult.Success -> Unit
                ScheduleResult.NeedsConsent -> _events.send(NEEDS_CONSENT_MESSAGE)
                is ScheduleResult.Failure -> _events.send(result.message)
            }
        }
    }

    /** Старт наступившей ad-hoc тренировки: создаёт активную и удаляет запись+событие. */
    fun startAdHoc(item: AdHocUi) = launchStart {
        try {
            activeWorkoutRepository.startFromRoutine(item.routineId)
            _startEvents.send(Unit)
            calendarRepository.cancel(item.scheduledId)
        } catch (conflict: RoutineGymConflictException) {
            _events.send("Нельзя начать: недоступно во всех залах — ${conflict.exerciseNames.joinToString()}")
        }
    }

    /** Старт по правилу расписания: только создаёт активную тренировку (серию НЕ трогаем). */
    fun startRecurring(routineId: Long) = launchStart {
        try {
            activeWorkoutRepository.startFromRoutine(routineId)
            _startEvents.send(Unit)
        } catch (conflict: RoutineGymConflictException) {
            _events.send("Нельзя начать: недоступно во всех залах — ${conflict.exerciseNames.joinToString()}")
        }
    }

    /** Сохраняет недельное расписание (замена серии). */
    fun saveSchedule(schedule: WeeklySchedule) {
        viewModelScope.launch {
            _events.send(scheduleResultMessage(weeklyScheduleRepository.save(schedule), success = "Расписание сохранено"))
        }
    }

    /** Очищает недельное расписание (удаляет серию). */
    fun clearSchedule() {
        viewModelScope.launch {
            _events.send(scheduleResultMessage(weeklyScheduleRepository.clear(), success = "Расписание очищено"))
        }
    }

    private fun scheduleResultMessage(result: ScheduleResult, success: String): String = when (result) {
        ScheduleResult.Success -> success
        ScheduleResult.NeedsConsent -> NEEDS_CONSENT_MESSAGE
        is ScheduleResult.Failure -> result.message
    }

    private inline fun launchStart(crossinline block: suspend () -> Unit) {
        if (startInFlight) return
        startInFlight = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                startInFlight = false
            }
        }
    }

    private fun buildMonth(month: YearMonth, data: CalendarData): MonthUi {
        val today = LocalDate.now(zone)
        val completedDays = data.finished.mapTo(mutableSetOf()) { it.startedAt.toLocalDate() }
        val adHocDays = data.adHoc.mapTo(mutableSetOf()) { it.scheduled.dateTimeMillis.toLocalDate() }
        val weeklyIsoDays = data.weekly.rules.mapTo(mutableSetOf()) { it.isoDay }
        return MonthUi(
            yearMonth = month,
            title = monthTitle(month),
            cells = buildMonthCells(month, today, completedDays, adHocDays, weeklyIsoDays),
        )
    }

    private fun buildSheet(date: LocalDate, data: CalendarData): DaySheetUi {
        val today = LocalDate.now(zone)
        val completed = data.finished
            .filter { it.startedAt.toLocalDate() == date }
            .map { CompletedWorkoutUi(it.id, it.name, timeLabel(it.startedAt, zone)) }
        val adHoc = data.adHoc
            .filter { it.scheduled.dateTimeMillis.toLocalDate() == date }
            .map {
                AdHocUi(
                    scheduledId = it.scheduled.id,
                    routineId = it.scheduled.routineId,
                    routineName = it.routineName,
                    timeLabel = timeLabel(it.scheduled.dateTimeMillis, zone),
                    canStart = it.scheduled.dateTimeMillis <= data.nowMillis,
                )
            }
        val recurring = data.weekly.rules
            .firstOrNull { it.isoDay == date.dayOfWeek.value }
            ?.let { rule ->
                val name = data.routineNames[rule.routineId] ?: return@let null
                RecurringUi(
                    routineId = rule.routineId,
                    routineName = name,
                    timeLabel = "%02d:%02d".format(rule.hour, rule.minute),
                    canStart = date == today,
                )
            }
        return DaySheetUi(
            date = date,
            title = dayTitle(date),
            completed = completed,
            adHoc = adHoc,
            recurring = recurring,
            allowPlan = !date.isBefore(today),
        )
    }

    private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private fun minuteTick() = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    private companion object {
        const val NEEDS_CONSENT_MESSAGE = "Настройте доступ к Google в настройках"
        const val PAST_TIME_MESSAGE = "Время уже прошло — выберите будущий момент"
    }
}
