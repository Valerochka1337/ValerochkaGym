package com.valerochka1337.valerochkagym.ui.routine

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.PlannedSet
import com.valerochka1337.valerochkagym.data.db.dao.RoutineDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.relation.RoutineWithExercises
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class RoutineDetailUiState(
    val loading: Boolean = true,
    val routine: RoutineDetailRoutine? = null,
    val loadError: Boolean = false,
)

/** Immutable snapshot rendered by the routine detail and the defensive standard-editor body. */
data class RoutineDetailRoutine(
    val id: Long,
    val syncId: String,
    val origin: String,
    val name: String,
    val note: String,
    val gymNames: List<String>,
    val exercises: List<RoutineDetailExercise>,
)

data class RoutineDetailExercise(
    val id: Long,
    val name: String,
    val type: ExerciseType,
    val restSeconds: Int?,
    val plannedSets: List<PlannedSet>,
)

/** Reactive, read-only detail state sourced only from Room's complete routine observation. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoutineDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val routineDao: RoutineDao,
) : ViewModel() {

  private val routineId = savedStateHandle.get<Long>(GymRoutes.ROUTINE_ID_ARG)
  private val refreshes = MutableStateFlow(0)

  val uiState: StateFlow<RoutineDetailUiState> =
      refreshes
          .flatMapLatest {
            val detail =
                if (routineId == null) {
                  flowOf(RoutineDetailUiState(loading = false))
                } else {
                  routineDao.observeRoutinesFull().map { routines ->
                    RoutineDetailUiState(
                        loading = false,
                        routine =
                            routines.firstOrNull { it.routine.id == routineId }?.toDetailRoutine(),
                    )
                  }
                }
            // Catch inside each refresh branch: retry installs a fresh Room collection after an
            // error.
            detail
                .onStart { emit(RoutineDetailUiState()) }
                .catch { throwable ->
                  if (throwable is CancellationException) throw throwable
                  emit(RoutineDetailUiState(loading = false, loadError = true))
                }
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = RoutineDetailUiState(),
          )

  fun retry() {
    refreshes.value++
  }
}

internal fun RoutineWithExercises.toDetailRoutine(): RoutineDetailRoutine =
    RoutineDetailRoutine(
        id = routine.id,
        syncId = routine.syncId,
        origin = routine.origin,
        name = routine.name,
        note = routine.note,
        gymNames = gyms.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER),
        exercises =
            exercises
                .sortedBy { it.routineExercise.position }
                .map { item ->
                  RoutineDetailExercise(
                      id = item.exercise.id,
                      name = item.exercise.name,
                      type = item.exercise.type,
                      restSeconds = item.routineExercise.restSeconds,
                      plannedSets = item.routineExercise.plannedSets,
                  )
                },
    )

internal fun RoutineEditorUiState.toDetailRoutine(): RoutineDetailRoutine =
    RoutineDetailRoutine(
        id = 0,
        syncId = syncId,
        origin = origin,
        name = name,
        note = note,
        gymNames =
            gyms
                .filter { it.id in selectedGymIds }
                .map { it.name }
                .sortedWith(String.CASE_INSENSITIVE_ORDER),
        exercises =
            exercises.map { exercise ->
              RoutineDetailExercise(
                  id = exercise.exerciseId,
                  name = exercise.exerciseName,
                  type = exercise.exerciseType,
                  restSeconds = exercise.restSeconds,
                  plannedSets = exercise.plannedSets,
              )
            },
    )
