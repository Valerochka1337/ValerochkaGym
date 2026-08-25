package com.valerochka1337.valerochkagym.ui.routine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.data.db.entity.RoutineExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.withNextUpdatedAt
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.worker.NoOpRoutineUploadScheduler
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Количество пустых подходов по умолчанию у только что добавленного силового упражнения. */
private const val DEFAULT_STRENGTH_SETS = 3

/** Одно упражнение в редакторе программы. */
data class EditorExercise(
    /** Стабильный id строки только для редактора: одно упражнение может повторяться в программе. */
    val editorId: String = UUID.randomUUID().toString(),
    val exerciseId: Long,
    val exerciseName: String,
    val exerciseType: ExerciseType,
    val restSeconds: Int?,
    val plannedSets: List<PlannedSet>,
)

/**
 * Состояние редактора программы. [isNew] отличает создание от редактирования (для заголовка);
 * [isValid] — можно ли сохранять (непустое имя и хотя бы одно упражнение).
 */
data class RoutineEditorUiState(
    val isNew: Boolean = true,
    // Пока true, тело редактора не рисуется: иначе для существующей программы кадр показывает
    // пустую «готовую» форму, которую тут же подменяет загруженная — это и есть моргание.
    val isLoading: Boolean = false,
    /** Невидимый UI ключ: сохраняет cloud-идентичность при редактировании существующей программы. */
    val syncId: String = UUID.randomUUID().toString(),
    /** Последняя версия снимка; следующее сохранение делает её строго больше. */
    val updatedAt: Long = 0L,
    val name: String = "",
    val note: String = "",
    val exercises: List<EditorExercise> = emptyList(),
) {
    val isValid: Boolean get() = name.trim().isNotEmpty() && exercises.isNotEmpty()
}

/**
 * Бэкенд редактора программы. Загружает существующую программу по routineId из
 * [SavedStateHandle] (null — новая). Все правки идут в память; при [save] пишет
 * routine + упражнения (позиции по индексу) и шлёт событие [saved] для popBackStack.
 */
@HiltViewModel
class RoutineEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val routineUploadScheduler: RoutineUploadScheduler = NoOpRoutineUploadScheduler,
) : ViewModel() {

    private val routineId: Long? =
        savedStateHandle.get<String>(GymRoutes.ROUTINE_ID_ARG)?.toLongOrNull()

    private val _uiState = MutableStateFlow(
        RoutineEditorUiState(isNew = routineId == null, isLoading = routineId != null),
    )
    val uiState: StateFlow<RoutineEditorUiState> = _uiState.asStateFlow()

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init {
        if (routineId != null) {
            viewModelScope.launch { load(routineId) }
        }
    }

    private suspend fun load(id: Long) {
        val full = routineDao.getRoutineWithExercises(id)
        if (full == null) {
            // Программа исчезла (например, удалена) — снимаем загрузку, чтобы не залипнуть.
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        _uiState.value = RoutineEditorUiState(
            isNew = false,
            syncId = full.routine.syncId,
            updatedAt = full.routine.updatedAt,
            name = full.routine.name,
            note = full.routine.note,
            exercises = full.exercises
                .sortedBy { it.routineExercise.position }
                .map { item ->
                    EditorExercise(
                        exerciseId = item.exercise.id,
                        exerciseName = item.exercise.name,
                        exerciseType = item.exercise.type,
                        restSeconds = item.routineExercise.restSeconds,
                        plannedSets = item.routineExercise.plannedSets,
                    )
                },
        )
    }

    fun setName(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    /** Добавляет упражнение по id (после выбора в библиотеке). */
    fun addExerciseById(exerciseId: Long) {
        viewModelScope.launch {
            val exercise = exerciseDao.getById(exerciseId) ?: return@launch
            addExercise(exercise)
        }
    }

    fun addExercise(exercise: ExerciseEntity) {
        val setCount = if (exercise.type == ExerciseType.STRENGTH) DEFAULT_STRENGTH_SETS else 1
        val editorExercise = EditorExercise(
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            exerciseType = exercise.type,
            restSeconds = null,
            plannedSets = List(setCount) { PlannedSet() },
        )
        _uiState.update { it.copy(exercises = it.exercises + editorExercise) }
    }

    fun removeExercise(index: Int) {
        _uiState.update { state ->
            if (index !in state.exercises.indices) return@update state
            state.copy(exercises = state.exercises.toMutableList().apply { removeAt(index) })
        }
    }

    /** Синхронно переставляет упражнение для drag-and-drop и действий TalkBack. */
    fun moveExercise(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (
                fromIndex !in state.exercises.indices ||
                toIndex !in state.exercises.indices ||
                fromIndex == toIndex
            ) {
                return@update state
            }
            state.copy(
                exercises = state.exercises.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                },
            )
        }
    }

    fun setRest(exIndex: Int, restSeconds: Int?) {
        updateExercise(exIndex) { it.copy(restSeconds = restSeconds) }
    }

    /** Добавляет подход как копию последнего (или пустой, если подходов ещё нет). */
    fun addPlannedSet(exIndex: Int) {
        updateExercise(exIndex) { exercise ->
            val newSet = exercise.plannedSets.lastOrNull() ?: PlannedSet()
            exercise.copy(plannedSets = exercise.plannedSets + newSet)
        }
    }

    fun removePlannedSet(exIndex: Int, setIndex: Int) {
        updateExercise(exIndex) { exercise ->
            if (setIndex !in exercise.plannedSets.indices) return@updateExercise exercise
            exercise.copy(
                plannedSets = exercise.plannedSets.toMutableList().apply { removeAt(setIndex) },
            )
        }
    }

    fun updatePlannedSet(exIndex: Int, setIndex: Int, plannedSet: PlannedSet) {
        updateExercise(exIndex) { exercise ->
            if (setIndex !in exercise.plannedSets.indices) return@updateExercise exercise
            exercise.copy(
                plannedSets = exercise.plannedSets.toMutableList().apply { this[setIndex] = plannedSet },
            )
        }
    }

    private inline fun updateExercise(index: Int, transform: (EditorExercise) -> EditorExercise) {
        _uiState.update { state ->
            if (index !in state.exercises.indices) return@update state
            state.copy(
                exercises = state.exercises.toMutableList().apply { this[index] = transform(this[index]) },
            )
        }
    }

    fun save() {
        val state = _uiState.value
        if (!state.isValid) return
        viewModelScope.launch {
            // @Upsert возвращает -1 при обновлении существующей строки, поэтому для правки
            // берём уже известный routineId, а результат upsert используем только для новой.
            val routine = RoutineEntity(
                id = routineId ?: 0,
                syncId = state.syncId,
                updatedAt = state.updatedAt,
                name = state.name.trim(),
                note = state.note,
            ).withNextUpdatedAt()
            val insertedId = routineDao.upsertRoutine(routine)
            val id = routineId ?: insertedId
            val entities = state.exercises.mapIndexed { index, exercise ->
                RoutineExerciseEntity(
                    routineId = id,
                    exerciseId = exercise.exerciseId,
                    position = index,
                    restSeconds = exercise.restSeconds,
                    plannedSets = exercise.plannedSets,
                )
            }
            routineDao.replaceRoutineExercises(id, entities)
            routineUploadScheduler.schedule(routine.syncId)
            _saved.send(Unit)
        }
    }
}
