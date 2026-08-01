package com.valerochka1337.valerochkagym.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.dao.ScheduledWorkoutDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.db.relation.ScheduledWithRoutine
import com.valerochka1337.valerochkagym.data.google.CalendarRepository
import com.valerochka1337.valerochkagym.data.google.ScheduleResult
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

/** Приблизительная длительность одного подхода без учёта отдыха, сек. */
private const val WORK_SECONDS_PER_SET = 45

/** «18:00» — время начала запланированной тренировки в зоне устройства. */
private val UPCOMING_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** «3 августа» — дата для тренировок дальше, чем завтра (родительный падеж, ru). */
private val UPCOMING_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))

/** Одна карточка программы в списке. [estimatedMinutes] — грубая оценка длительности. */
data class RoutineCardUi(
    val id: Long,
    val name: String,
    val exerciseCount: Int,
    val estimatedMinutes: Int,
)

/**
 * Состояние вкладки «Тренировки». [routines] == null означает «ещё не загружено»
 * (в отличие от загруженного пустого списка), чтобы не мигало пустое состояние.
 * [selectedRoutineId] — выбранная карточка для кнопки старта.
 */
data class WorkoutsUiState(
    val routines: List<RoutineCardUi>? = null,
    val selectedRoutineId: Long? = null,
) {
    val isEmpty: Boolean get() = routines?.isEmpty() == true
}

/**
 * Карточка запланированной тренировки в блоке «Ближайшие». [whenLabel] — человекочитаемое
 * «сегодня 18:00» / «завтра 18:00» / «3 августа 18:00». [isDue] == true, когда время начала
 * уже наступило (в пределах grace-окна из [ScheduledWorkoutDao]); такие карточки подсвечены и
 * тап по ним запускает тренировку.
 */
data class UpcomingUi(
    val id: Long,
    val routineId: Long,
    val routineName: String,
    val whenLabel: String,
    val isDue: Boolean,
)

/**
 * Бэкенд вкладки «Тренировки»: список программ с оценкой длительности, выбор программы,
 * дублирование и удаление. Оценка считается в памяти, т.к. plannedSets лежат в JSON.
 */
@HiltViewModel
class WorkoutsViewModel @Inject constructor(
    private val routineDao: RoutineDao,
    private val scheduledWorkoutDao: ScheduledWorkoutDao,
    private val calendarRepository: CalendarRepository,
    private val settingsRepository: SettingsRepository,
    private val activeWorkoutRepository: ActiveWorkoutRepository,
) : ViewModel() {

    private val selectedRoutineId = MutableStateFlow<Long?>(null)

    /**
     * Одиночный полёт старта: пока создание тренировки в процессе, повторные запросы (в т.ч.
     * двойной тап) игнорируются — гард репозитория «проверить-и-вставить» сам по себе гонку
     * не закрывает полностью.
     */
    private var startInFlight = false

    private val _startEvents = Channel<Unit>(Channel.BUFFERED)

    /** Событие «тренировка создана» — экран навигирует на активную тренировку. */
    val startEvents = _startEvents.receiveAsFlow()

    private val _scheduleEvents = Channel<String>(Channel.BUFFERED)

    /** Текст результата планирования/отмены — экран показывает его в снекбаре. */
    val scheduleEvents = _scheduleEvents.receiveAsFlow()

    /**
     * Ближайшие запланированные тренировки. `nowMillis` тикает раз в минуту, поэтому
     * [UpcomingUi.isDue] переключается вживую, а 6h-grace-окно [ScheduledWorkoutDao] истекает
     * без переподписки. null — «ещё не загружено».
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val upcoming: StateFlow<List<UpcomingUi>?> =
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000)
            }
        }.flatMapLatest { now ->
            scheduledWorkoutDao.observeUpcoming(now).map { list ->
                list.map { it.toUpcomingUi(now) }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private val defaultRestSeconds = settingsRepository.settings.map { it.defaultRestSeconds }

    val uiState: StateFlow<WorkoutsUiState> =
        combine(
            routineDao.observeRoutinesFull(),
            defaultRestSeconds,
            selectedRoutineId,
        ) { routines, defaultRest, selected ->
            val cards = routines.map { it.toCardUi(defaultRest) }
            // Сбрасываем выбор, если выбранная программа исчезла (удалена).
            val validSelected = selected?.takeIf { id -> cards.any { it.id == id } }
            WorkoutsUiState(routines = cards, selectedRoutineId = validSelected)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = WorkoutsUiState(),
        )

    /** Выбор программы: повторный тап по выбранной снимает выбор. */
    fun onRoutineSelected(id: Long) {
        selectedRoutineId.value = if (selectedRoutineId.value == id) null else id
    }

    /** Старт тренировки по программе. Событие [startEvents] шлётся только при успешном создании. */
    fun startFromRoutine(routineId: Long) = launchStart {
        activeWorkoutRepository.startFromRoutine(routineId)
        _startEvents.send(Unit)
    }

    /** Старт пустой тренировки. */
    fun startEmpty() = launchStart {
        activeWorkoutRepository.startEmpty()
        _startEvents.send(Unit)
    }

    /**
     * Единый single-flight старта: пока [block] выполняется, повторные запросы (двойной тап,
     * тап по наступившей карточке во время старта) игнорируются. Общий для [startFromRoutine],
     * [startEmpty] и [startScheduled].
     */
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

    /**
     * Планирует тренировку в календарь; результат уходит текстом в [scheduleEvents]. Если собранный
     * момент уже в прошлом (например, пользователь выбрал сегодняшнюю дату и время раньше текущего),
     * планирование не выполняется — сразу сообщение об ошибке.
     */
    fun schedule(routineId: Long, dateTimeMillis: Long) {
        viewModelScope.launch {
            if (dateTimeMillis < System.currentTimeMillis()) {
                _scheduleEvents.send(PAST_TIME_MESSAGE)
                return@launch
            }
            val message = when (val result = calendarRepository.schedule(routineId, dateTimeMillis)) {
                ScheduleResult.Success -> "Запланировано"
                ScheduleResult.NeedsConsent -> NEEDS_CONSENT_MESSAGE
                is ScheduleResult.Failure -> result.message
            }
            _scheduleEvents.send(message)
        }
    }

    /** Отмена запланированной тренировки. Успех тихий (список сам обновится), ошибку — в снекбар. */
    fun cancelScheduled(id: Long) {
        viewModelScope.launch {
            when (val result = calendarRepository.cancel(id)) {
                ScheduleResult.Success -> Unit
                ScheduleResult.NeedsConsent -> _scheduleEvents.send(NEEDS_CONSENT_MESSAGE)
                is ScheduleResult.Failure -> _scheduleEvents.send(result.message)
            }
        }
    }

    /**
     * Запуск наступившей запланированной тренировки: создаёт активную тренировку по программе
     * (тот же single-flight, что и [startFromRoutine]) и затем удаляет запись+событие календаря.
     * Ошибка отмены не мешает старту — запись останется, её можно отменить вручную.
     */
    fun startScheduled(item: UpcomingUi) = launchStart {
        activeWorkoutRepository.startFromRoutine(item.routineId)
        _startEvents.send(Unit)
        calendarRepository.cancel(item.id)
    }

    fun duplicate(id: Long) {
        viewModelScope.launch {
            val source = routineDao.getRoutineWithExercises(id) ?: return@launch
            val newId = routineDao.upsertRoutine(
                RoutineEntity(name = "${source.routine.name} (копия)", note = source.routine.note),
            )
            val copiedExercises = source.exercises.mapIndexed { index, item ->
                RoutineExerciseEntity(
                    routineId = newId,
                    exerciseId = item.routineExercise.exerciseId,
                    position = index,
                    restSeconds = item.routineExercise.restSeconds,
                    plannedSets = item.routineExercise.plannedSets,
                )
            }
            routineDao.replaceRoutineExercises(newId, copiedExercises)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            routineDao.deleteRoutine(id)
        }
    }

    private companion object {
        const val NEEDS_CONSENT_MESSAGE = "Настройте доступ к Google в настройках"
        const val PAST_TIME_MESSAGE = "Время уже прошло — выберите будущий момент"
    }
}

/**
 * Формат «Ближайших»: «сегодня 18:00» / «завтра 18:00» / «3 августа 18:00» в зоне устройства.
 * [isDue] сравнивает время начала с переданным `now`.
 */
private fun ScheduledWithRoutine.toUpcomingUi(now: Long): UpcomingUi {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(scheduled.dateTimeMillis).atZone(zone)
    val today = LocalDate.now(zone)
    val dayLabel = when (dateTime.toLocalDate()) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        else -> dateTime.format(UPCOMING_DATE_FORMAT)
    }
    return UpcomingUi(
        id = scheduled.id,
        routineId = scheduled.routineId,
        routineName = routineName,
        whenLabel = "$dayLabel ${dateTime.format(UPCOMING_TIME_FORMAT)}",
        isDue = scheduled.dateTimeMillis <= now,
    )
}

/** Оценка длительности: Σ по упражнениям (кол-во подходов × работа + кол-во подходов × отдых). */
private fun RoutineWithExercises.toCardUi(defaultRestSeconds: Int): RoutineCardUi {
    val totalSeconds = exercises.sumOf { item ->
        val sets = item.routineExercise.plannedSets.size
        val rest = item.routineExercise.restSeconds ?: defaultRestSeconds
        sets * WORK_SECONDS_PER_SET + sets * rest
    }
    return RoutineCardUi(
        id = routine.id,
        name = routine.name,
        exerciseCount = exercises.size,
        estimatedMinutes = (totalSeconds / 60.0).roundToInt(),
    )
}
