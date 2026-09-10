package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.backend.*
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.domain.*
import com.valerochka1337.valerochkagym.service.WallClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HealthRepositoryTest : RoomDaoTest() {
  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    private var epoch = 0L
    override val sessionEpoch
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }
  }

  private object Offline : BackendTransport {
    override val json = Json

    override suspend fun public(method: String, path: String, body: JsonElement?): JsonElement =
        error("local edits cannot call network")

    override suspend fun authorized(method: String, path: String, body: JsonElement?): JsonElement =
        error("local edits cannot call network")
  }

  private class Consent(version: Int = 1) : HealthConsentStore {
    val state = MutableStateFlow(HealthConsentSnapshot(version))

    override fun observe() = state

    override suspend fun acknowledgeCurrentStorageNotice() {
      state.value = state.value.copy(localStorageAcknowledgedVersion = 1)
    }

    override suspend fun setBackendSyncEnabled(enabled: Boolean) {
      state.value = state.value.copy(backendSyncEnabled = enabled)
    }
  }

  private fun repository(
      store: Store = Store(),
      consent: Consent = Consent(),
  ): Pair<HealthRepositoryImpl, BackendSync> {
    val sync = BackendSync(db, Offline, store)
    return HealthRepositoryImpl(
        db,
        db.healthDao(),
        db.healthSyncDao(),
        db.bodyMeasurementDao(),
        sync,
        store,
        consent,
        WallClock { 1_800_000_000_000 },
        Dispatchers.Unconfined,
    ) to sync
  }

  private suspend fun HealthRepository.create(
      draft: HealthEditorDraft,
      kind: HealthRecordKind,
  ): HealthMutationResult.Saved =
      confirm(requireNotNull(openCreate(kind)).target, draft) as HealthMutationResult.Saved

  private fun report(title: String = "Отчёт") =
      HealthEditorDraft.Report(
          title,
          " исходный текст ",
          "2026-09-10T11:22:33.123+03:00",
          HealthObservedPrecision.DATETIME,
      )

  @Test
  fun `guest opening does not persist unconfirmed records and offline report survives repository recreation`() =
      runTest {
        val store = Store()
        val (repo, _) = repository(store)
        repo.openCreate(HealthRecordKind.REPORT)
        assertEquals(0, tableCount("health_logical_records"))
        assertEquals(0, tableCount("health_sync_outbox"))
        val saved = repo.create(report("  Отчёт  "), HealthRecordKind.REPORT)
        val (restored, _) = repository(store)
        val history = requireNotNull(restored.observeHistory(saved.logicalId).first())
        val payload = history.current.payload as HealthPayload.Report
        assertEquals("Отчёт", payload.title)
        assertEquals(" исходный текст ", payload.sourceText)
        assertEquals("2026-09-10T11:22:33.123+03:00", payload.observedAt)
        assertNull(history.versions.single().serverSequence)
        assertNull(history.versions.single().healthRevision)
        assertTrue(history.headHistory.isEmpty())
      }

  @Test
  fun `correction and tombstone append versions while preserving the first captured bytes`() =
      runTest {
        val (repo, _) = repository()
        val first =
            repo.create(
                HealthEditorDraft.Restriction("Исходное ограничение"),
                HealthRecordKind.RESTRICTION,
            )
        val bytes = requireNotNull(db.healthSyncDao().outbox("GUEST")).requestBytes.copyOf()
        val edit = requireNotNull(repo.openEdit(first.logicalId))
        val corrected =
            repo.confirm(edit.target, HealthEditorDraft.Restriction("Уточнение"))
                as HealthMutationResult.Saved
        assertEquals(first.logicalId, corrected.logicalId)
        val remove = requireNotNull(repo.openEdit(first.logicalId))
        repo.tombstone(remove.target)
        val history = requireNotNull(repo.observeHistory(first.logicalId).first())
        assertTrue(history.current.deleted)
        assertNull(history.current.payload)
        assertEquals(3, history.versions.size)
        val original = history.versions.single { it.versionId == first.versionId }
        assertEquals(
            "Исходное ограничение",
            (original.payload as HealthPayload.Restriction).textOriginal,
        )
        assertEquals(
            first.versionId,
            history.versions.single { it.versionId == corrected.versionId }.parentVersionId,
        )
        assertEquals(
            corrected.versionId,
            history.versions.single { it.state == HealthVersionState.TOMBSTONE }.parentVersionId,
        )
        assertTrue(history.headHistory.isEmpty())
        assertArrayEquals(bytes, requireNotNull(db.healthSyncDao().outbox("GUEST")).requestBytes)
        assertNull(repo.openEdit(first.logicalId))
      }

  @Test
  fun `missing current acknowledgement blocks only new confirmation and leaves history readable`() =
      runTest {
        val consent = Consent()
        val (repo, _) = repository(consent = consent)
        val saved = repo.create(report(), HealthRecordKind.REPORT)
        val before = requireNotNull(db.healthSyncDao().outbox("GUEST")).requestBytes.copyOf()
        consent.state.value = consent.state.value.copy(localStorageAcknowledgedVersion = 0)
        val target = requireNotNull(repo.openCreate(HealthRecordKind.RESTRICTION)).target
        assertEquals(
            HealthMutationResult.StorageAcknowledgementRequired,
            repo.confirm(target, HealthEditorDraft.Restriction("Черновик")),
        )
        assertNotNull(repo.observeHistory(saved.logicalId).first())
        assertEquals(1, tableCount("health_record_versions"))
        assertArrayEquals(before, requireNotNull(db.healthSyncDao().outbox("GUEST")).requestBytes)
      }

  @Test
  fun `explicit metric identities and signed observation originals survive without measurement copies`() =
      runTest {
        val (repo, _) = repository()
        val savedReport = repo.create(report(), HealthRecordKind.REPORT)
        val target = requireNotNull(repo.openCreate(HealthRecordKind.OBSERVATION)).target
        val metric =
            (repo.createMetric(target, "Показатель") as HealthMetricMutationResult.Created).identity
        val other =
            (repo.createMetric(target, "Показатель") as HealthMetricMutationResult.Created).identity
        assertNotEquals(metric.id, other.id)
        val draft =
            HealthEditorDraft.Observation(
                savedReport.logicalId,
                metric.id,
                " исходное название ",
                HealthValueKind.RANGE,
                "от -2,5 до 10",
                null,
                "-2.5",
                "10",
                null,
                " ед. ",
                " метод ",
                " образец ",
                " источник ",
                " референс ",
                "2026-09-10",
                HealthObservedPrecision.DATE,
            )
        val saved = repo.confirm(target, draft) as HealthMutationResult.Saved
        val payload =
            requireNotNull(repo.observeHistory(saved.logicalId).first()).current.payload
                as HealthPayload.Observation
        assertEquals(draft.metricNameOriginal, payload.metricNameOriginal)
        assertEquals(draft.unitOriginal, payload.unitOriginal)
        assertEquals(draft.referenceOriginal, payload.referenceOriginal)
        assertEquals("-2.5", payload.rangeLow)
        assertEquals("10", payload.rangeHigh)
        assertEquals(1_800_000_000_000, payload.enteredAtEpochMs)
        assertEquals(0, tableCount("body_measurements"))
      }

  @Test
  fun `tombstoned report denies a new observation without deleting historical observation`() =
      runTest {
        val (repo, _) = repository()
        val savedReport = repo.create(report(), HealthRecordKind.REPORT)
        val target = requireNotNull(repo.openCreate(HealthRecordKind.OBSERVATION)).target
        val metric =
            (repo.createMetric(target, "Показатель") as HealthMetricMutationResult.Created).identity
        val draft =
            HealthEditorDraft.Observation(
                savedReport.logicalId,
                metric.id,
                "Показатель",
                HealthValueKind.NUMBER,
                "0",
                "0",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-09-10",
                HealthObservedPrecision.DATE,
            )
        val old = repo.confirm(target, draft) as HealthMutationResult.Saved
        repo.tombstone(requireNotNull(repo.openEdit(savedReport.logicalId)).target)
        assertEquals(
            HealthMutationResult.Invalid,
            repo.confirm(
                requireNotNull(repo.openCreate(HealthRecordKind.OBSERVATION)).target,
                draft,
            ),
        )
        assertNotNull(repo.observeHistory(old.logicalId).first())
        assertEquals(3, tableCount("health_record_versions"))
      }

  @Test
  fun `guest claim preserves literal bytes and invalidates the old editor`() = runTest {
    val store = Store()
    val (repo, sync) = repository(store)
    val guest = requireNotNull(repo.openCreate(HealthRecordKind.RESTRICTION))
    repo.confirm(guest.target, HealthEditorDraft.Restriction("Гостевая запись"))
    val before = requireNotNull(db.healthSyncDao().outbox("GUEST")).requestBytes.copyOf()
    val owner = "10000000-0000-4000-8000-000000000001"
    sync.claim(owner)
    assertArrayEquals(before, requireNotNull(db.healthSyncDao().outbox(owner)).requestBytes)
    assertNull(db.healthSyncDao().outbox("GUEST"))
    store.save(BackendTokens(owner, "a@example.com", "access", "refresh"))
    assertFalse(repo.observeTargetValidity(guest.target).first())
    assertEquals(
        HealthMutationResult.StaleTarget,
        repo.confirm(guest.target, HealthEditorDraft.Restriction("Не сохранять")),
    )
    assertEquals(1, tableCount("health_record_versions"))
    assertEquals(owner, requireNotNull(repo.openCreate(HealthRecordKind.REPORT)).target.ownerId)
  }

  @Test
  fun `analysis reads measurement ids and references without creating a health record or journal`() =
      runTest {
        val (repo, _) = repository()
        db.bodyMeasurementDao().insert(BodyMeasurementEntity("existing", 1, weightKg = 70.0))
        val analysis = repo.observeAnalysis().first { it.bodyMeasurementIds.isNotEmpty() }
        assertEquals(listOf("existing"), analysis.bodyMeasurementIds)
        val rows =
            repo.observeBodyMeasurementReferences(analysis.bodyMeasurementIds.toSet()).first()
        assertEquals(70.0, rows.single().weightKg!!, 0.0)
        assertEquals(0, tableCount("health_logical_records"))
        assertEquals(0, tableCount("health_sync_baseline"))
        assertEquals(0, tableCount("health_sync_outbox"))
      }
}
