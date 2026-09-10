package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureRepository
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureResult
import com.valerochka1337.valerochkagym.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HealthUiState(
    val loading: Boolean = true,
    val records: List<HealthCurrentRecord> = emptyList(),
    val metrics: List<HealthMetricIdentity> = emptyList(),
    val analysis: HealthAnalysisSnapshot? = null,
    val consent: HealthConsentSnapshot = HealthConsentSnapshot(),
    val bodyMeasurementIds: List<String> = emptyList(),
    val measurements: List<BodyMeasurementEntity> = emptyList(),
    val error: String? = null,
)

data class HealthEditorState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
    val requiresAcknowledgement: Boolean = false,
)

@HiltViewModel
class HealthViewModel
@Inject
constructor(
    private val repository: HealthRepository,
    private val consentStore: HealthConsentStore,
    private val savedStateHandle: SavedStateHandle,
    private val disclosure: HealthAiDisclosureRepository? = null,
) : ViewModel() {
  private val refresh = MutableStateFlow(0)
  private val error = MutableStateFlow<String?>(null)
  val uiState: StateFlow<HealthUiState> =
      refresh
          .flatMapLatest {
            val analysis =
                repository.observeAnalysis().flatMapLatest { snapshot ->
                  repository
                      .observeBodyMeasurementReferences(snapshot.bodyMeasurementIds.toSet())
                      .map { rows ->
                        snapshot to
                            rows
                                .distinctBy { it.id }
                                .filter { it.id in snapshot.bodyMeasurementIds }
                      }
                }
            combine(
                    repository.observeCurrent(),
                    repository.observeMetrics(),
                    analysis,
                    consentStore.observe(),
                    error,
                ) { records, metrics, values, consent, message ->
                  HealthUiState(
                      false,
                      records,
                      metrics,
                      values.first,
                      consent,
                      values.first.bodyMeasurementIds.distinct(),
                      values.second,
                      message,
                  )
                }
                .catch { failure ->
                  if (failure is CancellationException) throw failure
                  emit(
                      HealthUiState(
                          loading = false,
                          error = "Не удалось загрузить записи. Повторите попытку.",
                      )
                  )
                }
          }
          .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

  private val mutableEditor = MutableStateFlow<HealthEditorSnapshot?>(null)
  val editor = mutableEditor.asStateFlow()
  private val mutableEditorState = MutableStateFlow(HealthEditorState())
  val editorState = mutableEditorState.asStateFlow()
  private val mutableHistory = MutableStateFlow<HealthHistory?>(null)
  val history = mutableHistory.asStateFlow()
  private val mutableHistoryLoading = MutableStateFlow(true)
  val historyLoading = mutableHistoryLoading.asStateFlow()
  private var generation = 0L
  private var historyGeneration = 0L
  private var openJob: Job? = null
  private var historyJob: Job? = null
  private var validityJob: Job? = null
  private var mutationJob: Job? = null

  fun retry() {
    error.value = null
    refresh.value++
  }

  fun openCreate(kind: HealthRecordKind) = open(null, kind)

  fun openEdit(logicalId: String) = open(logicalId, null)

  private fun open(logicalId: String?, kind: HealthRecordKind?) {
    val request = ++generation
    openJob?.cancel()
    validityJob?.cancel()
    mutationJob?.cancel()
    mutableEditor.value = null
    mutableEditorState.value = HealthEditorState(loading = true)
    openJob =
        viewModelScope.launch {
          try {
            val snapshot =
                if (logicalId == null) repository.openCreate(requireNotNull(kind))
                else repository.openEdit(logicalId)
            if (request != generation) return@launch
            if (snapshot == null) {
              mutableEditorState.value = HealthEditorState(error = "Запись больше недоступна")
              clearSavedDraft()
            } else {
              val restored =
                  if (
                      snapshot.draft is HealthEditorDraft.Restriction &&
                          savedStateHandle.get<String>("healthDraftScope") ==
                              snapshot.target.scope &&
                          savedStateHandle.get<Long>("healthDraftEpoch") ==
                              snapshot.target.sessionEpoch &&
                          savedStateHandle.get<String>("healthDraftRecord") ==
                              snapshot.target.logicalId &&
                          savedStateHandle.get<String>("healthDraftVersion") ==
                              snapshot.target.currentVersionId
                  ) {
                    savedStateHandle.get<String>("healthRestrictionDraft")?.let {
                      snapshot.copy(draft = HealthEditorDraft.Restriction(it))
                    } ?: snapshot
                  } else snapshot
              clearSavedDraft()
              mutableEditor.value = restored
              mutableEditorState.value = HealthEditorState()
              if (restored.draft is HealthEditorDraft.Restriction) updateDraft(restored.draft)
              watchTarget(restored.target, request)
            }
          } catch (failure: CancellationException) {
            throw failure
          } catch (_: Exception) {
            if (request == generation)
                mutableEditorState.value =
                    HealthEditorState(error = "Не удалось открыть редактор. Повторите попытку.")
          }
        }
  }

  private fun watchTarget(target: HealthEditTarget, request: Long) {
    validityJob =
        viewModelScope.launch {
          repository.observeTargetValidity(target).collect { valid ->
            if (!valid && request == generation) {
              generation++
              mutationJob?.cancel()
              mutableEditor.value = null
              clearSavedDraft()
              mutableEditorState.value =
                  HealthEditorState(error = "Редактор закрыт после изменения аккаунта или сессии")
            }
          }
        }
  }

  fun openHistory(logicalId: String) {
    val request = ++historyGeneration
    savedStateHandle["healthRecord"] = logicalId
    historyJob?.cancel()
    mutableHistory.value = null
    mutableHistoryLoading.value = true
    historyJob =
        viewModelScope.launch {
          try {
            repository.observeHistory(logicalId).collect {
              if (request == historyGeneration) {
                mutableHistory.value = it
                mutableHistoryLoading.value = false
              }
            }
          } catch (failure: CancellationException) {
            throw failure
          } catch (_: Exception) {
            if (request == historyGeneration) {
              mutableHistoryLoading.value = false
              error.value = "Не удалось загрузить историю. Повторите попытку."
            }
          }
        }
  }

  fun updateDraft(draft: HealthEditorDraft) {
    val snapshot = mutableEditor.value ?: return
    if (mutableEditorState.value.saving) return
    mutableEditor.value = snapshot.copy(draft = draft)
    if (draft is HealthEditorDraft.Restriction) {
      savedStateHandle["healthDraftScope"] = snapshot.target.scope
      savedStateHandle["healthDraftEpoch"] = snapshot.target.sessionEpoch
      savedStateHandle["healthDraftRecord"] = snapshot.target.logicalId
      savedStateHandle["healthDraftVersion"] = snapshot.target.currentVersionId
      if (draft.textOriginal.length <= 5000)
          savedStateHandle["healthRestrictionDraft"] = draft.textOriginal
      else savedStateHandle.remove<String>("healthRestrictionDraft")
    }
  }

  fun closeEditor() {
    generation++
    openJob?.cancel()
    mutationJob?.cancel()
    validityJob?.cancel()
    mutableEditor.value = null
    clearSavedDraft()
  }

  private fun clearSavedDraft() {
    listOf(
            "healthDraftScope",
            "healthDraftEpoch",
            "healthDraftRecord",
            "healthDraftVersion",
            "healthRestrictionDraft",
        )
        .forEach { savedStateHandle.remove<Any>(it) }
  }

  fun acknowledgeStorageNotice() =
      viewModelScope.launch {
        try {
          consentStore.acknowledgeCurrentStorageNotice()
          mutableEditorState.value =
              mutableEditorState.value.copy(requiresAcknowledgement = false, error = null)
        } catch (failure: CancellationException) {
          throw failure
        } catch (_: Exception) {
          error.value = "Не удалось сохранить согласие"
        }
      }

  fun setBackendSync(enabled: Boolean) =
      viewModelScope.launch {
        try {
          consentStore.setBackendSyncEnabled(enabled)
        } catch (failure: CancellationException) {
          throw failure
        } catch (_: Exception) {
          error.value = "Не удалось изменить синхронизацию"
        }
      }

  fun setAiDisclosure(enabled: Boolean) =
      viewModelScope.launch {
        try {
          when (disclosure?.setEnabled(enabled)) {
            is HealthAiDisclosureResult.Updated -> error.value = null
            else -> error.value = "Не удалось подтвердить согласие. Проверьте аккаунт и соединение."
          }
        } catch (failure: CancellationException) {
          throw failure
        } catch (_: Exception) {
          error.value = "Не удалось подтвердить настройку обработки AI"
        }
      }

  fun createMetric(name: String) {
    val snapshot = mutableEditor.value ?: return
    val request = generation
    viewModelScope.launch {
      try {
        val result = repository.createMetric(snapshot.target, name)
        if (request != generation || mutableEditor.value?.target != snapshot.target) return@launch
        when (result) {
          is HealthMetricMutationResult.Created ->
              (mutableEditor.value?.draft as? HealthEditorDraft.Observation)?.let {
                updateDraft(
                    it.copy(
                        metricIdentityId = result.identity.id,
                        metricNameOriginal = result.identity.nameOriginal,
                    )
                )
              }
          HealthMetricMutationResult.Invalid ->
              mutableEditorState.value =
                  mutableEditorState.value.copy(error = "Введите название метрики")
          HealthMetricMutationResult.StaleTarget -> closeEditor()
        }
      } catch (failure: CancellationException) {
        throw failure
      } catch (_: Exception) {
        if (request == generation)
            mutableEditorState.value =
                mutableEditorState.value.copy(error = "Не удалось создать метрику")
      }
    }
  }

  fun confirm(draft: HealthEditorDraft? = null) {
    val value = draft ?: mutableEditor.value?.draft ?: return
    mutate { target -> repository.confirm(target, value) }
  }

  fun tombstone() = mutate(repository::tombstone)

  private fun mutate(action: suspend (HealthEditTarget) -> HealthMutationResult) {
    val snapshot = mutableEditor.value ?: return
    if (mutableEditorState.value.saving) return
    val request = generation
    mutableEditorState.value = mutableEditorState.value.copy(saving = true, error = null)
    mutationJob =
        viewModelScope.launch {
          try {
            val result = action(snapshot.target)
            if (request != generation || mutableEditor.value?.target != snapshot.target)
                return@launch
            mutableEditorState.value =
                when (result) {
                  is HealthMutationResult.Saved -> {
                    mutableEditor.value = null
                    clearSavedDraft()
                    HealthEditorState(finished = true)
                  }
                  HealthMutationResult.StorageAcknowledgementRequired ->
                      HealthEditorState(
                          requiresAcknowledgement = true,
                          error = "Подтвердите локальное хранение данных здоровья",
                      )
                  HealthMutationResult.Invalid ->
                      HealthEditorState(error = "Проверьте введённые данные")
                  HealthMutationResult.StaleTarget,
                  HealthMutationResult.MissingOrDeleted -> {
                    mutableEditor.value = null
                    clearSavedDraft()
                    HealthEditorState(error = "Запись больше недоступна")
                  }
                }
          } catch (failure: CancellationException) {
            throw failure
          } catch (_: Exception) {
            if (request == generation)
                mutableEditorState.value =
                    HealthEditorState(error = "Не удалось сохранить запись. Повторите попытку.")
          } finally {
            if (request == generation && mutableEditorState.value.saving)
                mutableEditorState.value = mutableEditorState.value.copy(saving = false)
          }
        }
  }
}
