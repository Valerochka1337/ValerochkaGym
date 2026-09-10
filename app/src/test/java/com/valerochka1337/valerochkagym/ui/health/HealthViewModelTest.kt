package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HealthViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun `storage acknowledgement error leaves draft unconfirmed and prompts again for current notice`() =
      runTest(main.testDispatcher.scheduler) {
        val repo =
            FakeHealthRepository().apply {
              result = HealthMutationResult.StorageAcknowledgementRequired
            }
        val vm =
            HealthViewModel(
                repo,
                FakeConsent(HealthConsentSnapshot(localStorageAcknowledgedVersion = 1)),
                SavedStateHandle(),
            )
        vm.openCreate(HealthRecordKind.RESTRICTION)
        advanceUntilIdle()
        vm.confirm(HealthEditorDraft.Restriction("исходная формулировка"))
        advanceUntilIdle()
        assertNotNull(vm.editor.value)
        assertTrue(vm.editorState.value.requiresAcknowledgement)
        assertTrue(vm.editorState.value.error!!.contains("Подтвердите"))
      }

  @Test
  fun `analysis resolves each measurement id once and filters unrelated rows`() =
      runTest(main.testDispatcher.scheduler) {
        val repo =
            FakeHealthRepository().apply {
              analysis.value = HealthAnalysisSnapshot(emptyList(), emptyList(), listOf("m1", "m1"))
              measurements.value =
                  listOf(
                      BodyMeasurementEntity("m1", 0, weightKg = 80.0),
                      BodyMeasurementEntity("m1", 0),
                      BodyMeasurementEntity("other", 1),
                  )
            }
        val vm = HealthViewModel(repo, FakeConsent(), SavedStateHandle())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(setOf("m1"), repo.requestedIds)
        assertEquals(listOf("m1"), vm.uiState.value.measurements.map { it.id })
        assertEquals(80.0, vm.uiState.value.measurements.single().weightKg!!, 0.0)
        assertEquals(0, repo.confirmations)
      }

  @Test
  fun `older suspended editor cannot replace a newer selection`() =
      runTest(main.testDispatcher.scheduler) {
        val first = CompletableDeferred<HealthEditorSnapshot?>()
        val repo =
            FakeHealthRepository().apply {
              opening = { id ->
                if (id == "old") withContext(NonCancellable) { first.await() } else snapshot(id)
              }
            }
        val vm = HealthViewModel(repo, FakeConsent(), SavedStateHandle())
        vm.openEdit("old")
        advanceUntilIdle()
        vm.openEdit("new")
        advanceUntilIdle()
        first.complete(repo.snapshot("old"))
        advanceUntilIdle()
        assertEquals("new", vm.editor.value!!.target.logicalId)
      }

  @Test
  fun `duplicate confirm while pending invokes repository only once`() =
      runTest(main.testDispatcher.scheduler) {
        val result = CompletableDeferred<HealthMutationResult>()
        val repo = FakeHealthRepository().apply { confirming = { result.await() } }
        val vm = HealthViewModel(repo, FakeConsent(), SavedStateHandle())
        vm.openCreate(HealthRecordKind.RESTRICTION)
        advanceUntilIdle()
        vm.confirm()
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, repo.confirmations)
        assertTrue(vm.editorState.value.saving)
        result.complete(HealthMutationResult.Saved("r", "v"))
        advanceUntilIdle()
        assertTrue(vm.editorState.value.finished)
        assertNull(vm.editor.value)
      }

  @Test
  fun `late confirmation does not close the next editor`() =
      runTest(main.testDispatcher.scheduler) {
        val result = CompletableDeferred<HealthMutationResult>()
        val repo =
            FakeHealthRepository().apply {
              confirming = { withContext(NonCancellable) { result.await() } }
            }
        val vm = HealthViewModel(repo, FakeConsent(), SavedStateHandle())
        vm.openEdit("old")
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.openEdit("new")
        advanceUntilIdle()
        result.complete(HealthMutationResult.Saved("old", "version"))
        advanceUntilIdle()
        assertEquals("new", vm.editor.value!!.target.logicalId)
        assertFalse(vm.editorState.value.finished)
      }

  @Test
  fun `owner invalidation removes draft and saved restriction without confirming`() =
      runTest(main.testDispatcher.scheduler) {
        val saved = SavedStateHandle()
        val repo = FakeHealthRepository()
        val vm = HealthViewModel(repo, FakeConsent(), saved)
        vm.openCreate(HealthRecordKind.RESTRICTION)
        advanceUntilIdle()
        vm.updateDraft(HealthEditorDraft.Restriction("личный текст"))
        repo.valid.value = false
        advanceUntilIdle()
        assertNull(vm.editor.value)
        assertFalse(saved.contains("healthRestrictionDraft"))
        vm.confirm()
        assertEquals(0, repo.confirmations)
      }

  @Test
  fun `restriction draft restores only for the same scope epoch and version`() =
      runTest(main.testDispatcher.scheduler) {
        fun saved(scope: String) =
            SavedStateHandle(
                mapOf(
                    "healthDraftScope" to scope,
                    "healthDraftEpoch" to 1L,
                    "healthRestrictionDraft" to "несохранённый текст",
                )
            )
        val repo = FakeHealthRepository()
        val same = HealthViewModel(repo, FakeConsent(), saved("guest"))
        same.openCreate(HealthRecordKind.RESTRICTION)
        advanceUntilIdle()
        assertEquals(
            "несохранённый текст",
            (same.editor.value!!.draft as HealthEditorDraft.Restriction).textOriginal,
        )
        val other = HealthViewModel(repo, FakeConsent(), saved("other"))
        other.openCreate(HealthRecordKind.RESTRICTION)
        advanceUntilIdle()
        assertEquals("", (other.editor.value!!.draft as HealthEditorDraft.Restriction).textOriginal)
        assertEquals(0, repo.confirmations)
      }

  @Test
  fun `explicit metric creation updates only the current observation draft`() =
      runTest(main.testDispatcher.scheduler) {
        val repo = FakeHealthRepository()
        val vm = HealthViewModel(repo, FakeConsent(), SavedStateHandle())
        vm.openCreate(HealthRecordKind.OBSERVATION)
        advanceUntilIdle()
        vm.createMetric("Показатель")
        advanceUntilIdle()
        val draft = vm.editor.value!!.draft as HealthEditorDraft.Observation
        assertEquals("metric", draft.metricIdentityId)
        assertEquals("Показатель", draft.metricNameOriginal)
        assertEquals(0, repo.confirmations)
      }

  @Test
  fun `changing value kind clears incompatible parsed fields but retains originals`() {
    val range =
        healthObservationDraft()
            .copy(
                valueKind = HealthValueKind.RANGE,
                rangeLow = "-2.5",
                rangeHigh = "10",
                numberValue = null,
                valueOriginal = "от -2,5 до 10",
            )
    val comparison = range.withValueKind(HealthValueKind.COMPARATOR)
    assertNull(comparison.rangeLow)
    assertNull(comparison.rangeHigh)
    assertEquals(HealthOperator.LT, comparison.operator)
    assertEquals(range.valueOriginal, comparison.valueOriginal)
    val text = comparison.copy(numberValue = "0").withValueKind(HealthValueKind.TEXT)
    assertNull(text.operator)
    assertNull(text.numberValue)
    assertEquals(range.valueOriginal, text.valueOriginal)
  }

  @Test
  fun `save failure remains retryable and does not discard the draft`() =
      runTest(main.testDispatcher.scheduler) {
        val repo =
            FakeHealthRepository().apply {
              confirming = { throw IllegalStateException("private body") }
            }
        val vm = HealthViewModel(repo, FakeConsent(), SavedStateHandle())
        vm.openCreate(HealthRecordKind.RESTRICTION)
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertFalse(vm.editorState.value.saving)
        assertNotNull(vm.editor.value)
        assertFalse(vm.editorState.value.error!!.contains("private body"))
        repo.confirming = null
        vm.confirm()
        advanceUntilIdle()
        assertTrue(vm.editorState.value.finished)
      }
}

internal fun healthObservationDraft() =
    HealthEditorDraft.Observation(
        "report",
        "metric",
        "Показатель",
        HealthValueKind.NUMBER,
        "0",
        "0",
        null,
        null,
        null,
        "ед.",
        "метод",
        "образец",
        "источник",
        "исходный референс",
        "2026-09-10",
        HealthObservedPrecision.DATE,
    )

private class FakeConsent(initial: HealthConsentSnapshot = HealthConsentSnapshot()) :
    HealthConsentStore {
  private val state = MutableStateFlow(initial)

  override fun observe() = state

  override suspend fun acknowledgeCurrentStorageNotice() {
    state.value = state.value.copy(localStorageAcknowledgedVersion = 2)
  }

  override suspend fun setBackendSyncEnabled(enabled: Boolean) {
    state.value = state.value.copy(backendSyncEnabled = enabled)
  }
}

private class FakeHealthRepository : HealthRepository {
  val valid = MutableStateFlow(true)
  val analysis = MutableStateFlow(HealthAnalysisSnapshot(emptyList(), emptyList(), emptyList()))
  val measurements = MutableStateFlow(emptyList<BodyMeasurementEntity>())
  var requestedIds = emptySet<String>()
  var result: HealthMutationResult = HealthMutationResult.Saved("r", "v")
  var confirming: (suspend () -> HealthMutationResult)? = null
  var opening: (suspend (String) -> HealthEditorSnapshot?)? = null
  var confirmations = 0
  private val target = HealthEditTarget("guest", null, 1, null, null, 0)

  fun snapshot(id: String?) =
      HealthEditorSnapshot(target.copy(logicalId = id), HealthEditorDraft.Restriction(""))

  override fun observeCurrent() = flowOf(emptyList<HealthCurrentRecord>())

  override fun observeHistory(logicalId: String) = flowOf<HealthHistory?>(null)

  override fun observeMetrics() = flowOf(emptyList<HealthMetricIdentity>())

  override fun observeAnalysis() = analysis

  override fun observeTargetValidity(target: HealthEditTarget) = valid

  override fun observeBodyMeasurementReferences(
      ids: Set<String>
  ): Flow<List<BodyMeasurementEntity>> {
    requestedIds = ids
    return measurements
  }

  override suspend fun openCreate(kind: HealthRecordKind) =
      snapshot(null).let {
        if (kind == HealthRecordKind.OBSERVATION) it.copy(draft = healthObservationDraft()) else it
      }

  override suspend fun openEdit(logicalId: String) =
      opening?.invoke(logicalId) ?: snapshot(logicalId)

  override suspend fun createMetric(target: HealthEditTarget, nameOriginal: String) =
      HealthMetricMutationResult.Created(HealthMetricIdentity("metric", nameOriginal, 1))

  override suspend fun confirm(
      target: HealthEditTarget,
      draft: HealthEditorDraft,
  ): HealthMutationResult {
    confirmations++
    return confirming?.invoke() ?: result
  }

  override suspend fun tombstone(target: HealthEditTarget) = result
}
