package com.valerochka1337.valerochkagym.ui.calendarai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.ai.CalendarAiIntent
import com.valerochka1337.valerochkagym.data.ai.CalendarAiRepository
import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.db.LocalEquipmentCatalog
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.GymDao
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptDecision
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptKind
import com.valerochka1337.valerochkagym.domain.displayName
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.ui.profile.AiProfilePromptUi
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarAiChoice(val id: String, val label: String)

data class CalendarAiForm(
    val date: String,
    val time: String = "18:00",
    val timeZoneId: String,
    val gymIds: Set<String> = emptySet(),
    val excludedExerciseIds: Set<String> = emptySet(),
    val excludedEquipmentIds: Set<String> = emptySet(),
    val priorityMuscles: Set<String> = emptySet(),
    val includeNotes: Boolean = true,
    val availableDurationMinutes: String = "60",
    val currentState: String = "",
    val preferences: String = "",
)

data class CalendarAiUiState(
    val form: CalendarAiForm,
    val gyms: List<CalendarAiChoice> = emptyList(),
    val exercises: List<CalendarAiChoice> = emptyList(),
    val equipment: List<CalendarAiChoice> = emptyList(),
    val muscles: List<CalendarAiChoice> =
        Muscle.entries.map { CalendarAiChoice(it.name, it.displayName()) },
    val generating: Boolean = false,
    val error: String? = null,
    val profilePrompt: AiProfilePromptUi? = null,
)

@HiltViewModel
class CalendarAiViewModel
@Inject
constructor(
    private val repository: CalendarAiRepository,
    private val sessions: BackendSessionStore,
    private val sync: BackendSync,
    private val profileGate: AiProfilePromptGate,
    private val clock: WallClock,
    exercises: ExerciseDao,
    gyms: GymDao,
) : ViewModel() {
  private val zone = ZoneId.systemDefault()
  private val mutableState =
      MutableStateFlow(
          CalendarAiUiState(
              form =
                  CalendarAiForm(
                      date =
                          LocalDate.ofInstant(Instant.ofEpochMilli(clock.nowMillis()), zone)
                              .plusDays(1)
                              .toString(),
                      timeZoneId = zone.id,
                  )
          )
      )
  private val _openProposal = Channel<Pair<Long, String>>(Channel.BUFFERED)
  val openProposal =
      _openProposal.receiveAsFlow().filter { it.first == sessions.sessionEpoch }.map { it.second }
  private val _openProfile = Channel<Long>(Channel.BUFFERED)
  val openProfile = _openProfile.receiveAsFlow().filter { it == sessions.sessionEpoch }

  private var request: Job? = null
  private var generation = 0L
  private var sessionEpoch = sessions.sessionEpoch
  private var transferVersion: Any? = null

  val uiState: StateFlow<CalendarAiUiState> =
      combine(mutableState, exercises.getAll(), gyms.observeGyms(), LocalEquipmentCatalog.state) {
              state,
              exerciseRows,
              gymRows,
              equipmentRows,
            ->
            state.copy(
                gyms =
                    gymRows.filterNot { it.archived }.map { CalendarAiChoice(it.syncId, it.name) },
                exercises =
                    exerciseRows
                        .filterNot { it.archived }
                        .map { CalendarAiChoice(it.syncId, it.name) },
                equipment =
                    equipmentRows
                        .filterNot { it.archived }
                        .map { CalendarAiChoice(it.equipment.id, it.equipment.name) },
            )
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), mutableState.value)

  init {
    viewModelScope.launch {
      combine(sessions.session, sync.transfer) { _, transfer -> transfer }
          .collect { transfer ->
            val epochChanged = sessionEpoch != sessions.sessionEpoch
            val transferChanged = transferVersion != null && transferVersion != transfer
            sessionEpoch = sessions.sessionEpoch
            transferVersion = transfer
            if (epochChanged || transferChanged) invalidateForContextChange()
          }
    }
  }

  fun setDate(value: String) = updateForm { copy(date = value) }

  fun setTime(value: String) = updateForm { copy(time = value) }

  fun setTimeZone(value: String) = updateForm { copy(timeZoneId = value) }

  fun toggleGym(id: String) = updateForm { copy(gymIds = gymIds.toggle(id)) }

  fun toggleExcludedExercise(id: String) = updateForm {
    copy(excludedExerciseIds = excludedExerciseIds.toggle(id))
  }

  fun toggleExcludedEquipment(id: String) = updateForm {
    copy(excludedEquipmentIds = excludedEquipmentIds.toggle(id))
  }

  fun togglePriorityMuscle(id: String) = updateForm {
    copy(priorityMuscles = priorityMuscles.toggle(id))
  }

  fun setIncludeNotes(value: Boolean) = updateForm { copy(includeNotes = value) }

  fun setDuration(value: String) = updateForm { copy(availableDurationMinutes = value) }

  fun setCurrentState(value: String) = updateForm { copy(currentState = value) }

  fun setPreferences(value: String) = updateForm { copy(preferences = value) }

  fun generate() {
    if (mutableState.value.generating || mutableState.value.profilePrompt != null) return
    val intent = intentOrNull() ?: return showFormError()
    val token = ++generation
    val epoch = sessions.sessionEpoch
    request =
        viewModelScope.launch {
          when (val decision = profileGate.request(AiProfilePromptKind.CALENDAR)) {
            AiProfilePromptDecision.Proceed -> generateIntent(token, epoch, intent)
            AiProfilePromptDecision.Busy -> setError(token, "Подождите ответа на предложение")
            AiProfilePromptDecision.Stale ->
                setError(token, "Аккаунт изменился, заполните форму заново")
            is AiProfilePromptDecision.Show -> {
              if (token == generation && epoch == sessions.sessionEpoch) {
                mutableState.update {
                  it.copy(profilePrompt = AiProfilePromptUi(decision.token, decision.kind))
                }
              } else {
                profileGate.cancel(decision.token)
              }
            }
          }
        }
  }

  fun acknowledgeProfilePrompt(token: String) {
    viewModelScope.launch { profileGate.acknowledgeVisible(token) }
  }

  fun continueAfterProfilePrompt(token: String, disableFuturePrompts: Boolean = false) {
    val intent = intentOrNull() ?: return showFormError()
    val tokenGeneration = generation
    val epoch = sessions.sessionEpoch
    viewModelScope.launch {
      if (mutableState.value.profilePrompt?.token != token) return@launch
      val consumed = profileGate.consume(token, disableFuturePrompts)
      if (mutableState.value.profilePrompt?.token != token) return@launch
      mutableState.update { it.copy(profilePrompt = null) }
      if (consumed) generateIntent(tokenGeneration, epoch, intent)
    }
  }

  fun fillProfileFromPrompt(token: String) {
    viewModelScope.launch {
      if (mutableState.value.profilePrompt?.token != token) return@launch
      val consumed = profileGate.consume(token, disableFuturePrompts = false)
      if (mutableState.value.profilePrompt?.token != token) return@launch
      mutableState.update { it.copy(profilePrompt = null) }
      if (consumed) _openProfile.send(sessions.sessionEpoch)
    }
  }

  fun dismissProfilePrompt(token: String) {
    viewModelScope.launch {
      if (mutableState.value.profilePrompt?.token != token) return@launch
      profileGate.cancel(token)
      if (mutableState.value.profilePrompt?.token == token) {
        mutableState.update { it.copy(profilePrompt = null) }
      }
    }
  }

  private suspend fun generateIntent(token: Long, epoch: Long, intent: CalendarAiIntent) {
    if (token != generation || epoch != sessions.sessionEpoch) return
    mutableState.update { it.copy(generating = true, error = null) }
    try {
      val proposal = repository.generate(intent)
      if (token != generation || epoch != sessions.sessionEpoch) return
      mutableState.update { it.copy(generating = false) }
      _openProposal.send(epoch to proposal.proposalId)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      if (token == generation && epoch == sessions.sessionEpoch) {
        mutableState.update {
          it.copy(
              generating = false,
              error = "Не удалось подготовить предложение. Проверьте данные и синхронизацию",
          )
        }
      }
    }
  }

  private fun invalidateForContextChange() {
    generation++
    request?.cancel()
    mutableState.value.profilePrompt?.let { prompt ->
      viewModelScope.launch { profileGate.cancel(prompt.token) }
    }
    mutableState.update {
      it.copy(
          form =
              CalendarAiForm(
                  date =
                      LocalDate.ofInstant(Instant.ofEpochMilli(clock.nowMillis()), zone)
                          .plusDays(1)
                          .toString(),
                  timeZoneId = zone.id,
              ),
          generating = false,
          profilePrompt = null,
          error = "Аккаунт или синхронизация изменились. Проверьте форму ещё раз",
      )
    }
  }

  override fun onCleared() {
    mutableState.value.profilePrompt?.token?.let { token ->
      viewModelScope.launch(kotlinx.coroutines.NonCancellable) { profileGate.cancel(token) }
    }
    super.onCleared()
  }

  private fun intentOrNull(): CalendarAiIntent? {
    val form = mutableState.value.form
    val zone = runCatching { ZoneId.of(form.timeZoneId.trim()) }.getOrNull() ?: return null
    val date =
        runCatching { LocalDate.parse(form.date.trim(), dateFormatter) }.getOrNull() ?: return null
    val time =
        runCatching { LocalTime.parse(form.time.trim(), timeFormatter) }.getOrNull() ?: return null
    val offsets = zone.rules.getValidOffsets(LocalDateTime.of(date, time))
    if (offsets.isEmpty()) return null
    val duration = form.availableDurationMinutes.toIntOrNull() ?: return null
    val startsAtMillis =
        LocalDateTime.of(date, time).atOffset(offsets.first()).toInstant().toEpochMilli()
    return CalendarAiIntent(
            startsAtMillis = startsAtMillis,
            timeZoneId = zone.id,
            gymIds = form.gymIds.sorted(),
            excludedExerciseIds = form.excludedExerciseIds.sorted(),
            excludedEquipmentIds = form.excludedEquipmentIds.sorted(),
            priorityMuscles = form.priorityMuscles.sorted(),
            includeNotes = form.includeNotes,
            availableDurationMinutes = duration,
            currentState = form.currentState.trim().ifEmpty { null },
            preferences = form.preferences.trim().ifEmpty { null },
        )
        .takeIf { it.valid(clock.nowMillis()) }
  }

  private fun showFormError() {
    mutableState.update { it.copy(error = "Проверьте дату, время, часовой пояс и длительность") }
  }

  private fun updateForm(change: CalendarAiForm.() -> CalendarAiForm) {
    mutableState.update { state -> state.copy(form = state.form.change(), error = null) }
  }

  private fun setError(token: Long, message: String) {
    if (token == generation) mutableState.update { it.copy(error = message) }
  }

  private companion object {
    val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
  }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value
