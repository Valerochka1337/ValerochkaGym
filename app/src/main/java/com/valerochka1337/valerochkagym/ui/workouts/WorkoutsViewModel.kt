package com.valerochka1337.valerochkagym.ui.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Приблизительная длительность одного подхода без учёта отдыха, сек. */
private const val WORK_SECONDS_PER_SET = 45

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
 * Бэкенд вкладки «Тренировки»: список программ с оценкой длительности, выбор программы,
 * дублирование и удаление. Оценка считается в памяти, т.к. plannedSets лежат в JSON.
 */
@HiltViewModel
class WorkoutsViewModel @Inject constructor(
    private val routineDao: RoutineDao,
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
    fun startFromRoutine(routineId: Long) = start { activeWorkoutRepository.startFromRoutine(routineId) }

    /** Старт пустой тренировки. */
    fun startEmpty() = start { activeWorkoutRepository.startEmpty() }

    private inline fun start(crossinline create: suspend () -> String) {
        if (startInFlight) return
        startInFlight = true
        viewModelScope.launch {
            try {
                create()
                _startEvents.send(Unit)
            } finally {
                startInFlight = false
            }
        }
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
