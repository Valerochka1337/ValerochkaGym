package com.valerochka1337.valerochkagym.ui.health

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncConflictEntity
import com.valerochka1337.valerochkagym.data.health.ConflictChoice
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import com.valerochka1337.valerochkagym.data.health.SyncConflictResolver
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthConflictViewModelTest : RoomDaoTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `choosing local appends one successor and emits back only after success`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertMeasurementConflict("local", localWeight = 70.0, remoteWeight = 80.0)
            val vm = viewModel("local")
            val collector = launch { vm.uiState.collect() }
            vm.uiState.first { it.canResolve }
            val completed = async { vm.events.first() }

            vm.choose(ConflictChoice.LOCAL)
            vm.choose(ConflictChoice.LOCAL)
            assertEquals(HealthConflictEvent.Resolved, completed.await())

            assertEquals(70.0, db.bodyMeasurementDao().getById("local")!!.weightKg)
            assertEquals(listOf(2L), db.healthDao().measurementSnapshots("local").map { it.version })
            assertEquals(1, tableCount("health_sync_outbox"))
            assertNull(db.healthDao().conflict(HealthSyncCategory.MEASUREMENTS, "local", 1))
            collector.cancel()
        }

    @Test
    fun `choosing remote uses the route identity and preserves no raw payload in state`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            insertMeasurementConflict("remote", localWeight = 70.0, remoteWeight = 80.0)
            val vm = viewModel("remote")
            val collector = launch { vm.uiState.collect() }
            val initial = vm.uiState.first { it.canResolve }
            val conflict = requireNotNull(initial.conflict)
            assertEquals("remote", conflict.syncId)
            assertEquals(HealthSyncCategory.MEASUREMENTS, conflict.category)
            assertEquals(1L, conflict.version)
            assertFalse(initial.remote!!.rows.any { it.contains("v1|id=") })
            val completed = async { vm.events.first() }

            vm.choose(ConflictChoice.REMOTE)
            assertEquals(HealthConflictEvent.Resolved, completed.await())

            assertEquals(80.0, db.bodyMeasurementDao().getById("remote")!!.weightKg)
            assertNull(db.healthDao().conflict(HealthSyncCategory.MEASUREMENTS, "remote", 1))
            collector.cancel()
        }

    @Test
    fun `invalid selected aggregate leaves its durable conflict visible`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val report = HealthReportEntity("report", 1, 1, false, "CONFIRMED", "LAB", 1, "Без наблюдений")
            val payload = HealthSyncPayloadCodec.report(report, emptyList())
            db.healthDao().upsertConflict(
                HealthSyncConflictEntity(
                    HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS,
                    "report",
                    1,
                    payload,
                    hash(payload),
                    payload,
                    hash(payload),
                    1,
                ),
            )
            val vm = HealthConflictViewModel(
                handle(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", 1),
                db.healthDao(),
                SyncConflictResolver(db),
            )
            val collector = launch { vm.uiState.collect() }
            vm.uiState.first { it.canResolve }

            vm.choose(ConflictChoice.LOCAL)
            val failed = vm.uiState.first { it.error?.contains("Конфликт сохранён") == true }
            assertTrue(failed.conflict != null)
            assertTrue(db.healthDao().conflict(HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS, "report", 1) != null)
            assertEquals(0, tableCount("health_sync_outbox"))
            collector.cancel()
        }

    private suspend fun insertMeasurementConflict(id: String, localWeight: Double, remoteWeight: Double) {
        val local = BodyMeasurementEntity(id, 1_700_000_000_000, weightKg = localWeight)
        val remote = local.copy(weightKg = remoteWeight)
        val localPayload = MeasurementRepository.canonicalPayload(local)
        val remotePayload = MeasurementRepository.canonicalPayload(remote)
        db.healthDao().upsertConflict(
            HealthSyncConflictEntity(
                HealthSyncCategory.MEASUREMENTS,
                id,
                1,
                localPayload,
                hash(localPayload),
                remotePayload,
                hash(remotePayload),
                1,
            ),
        )
    }

    private fun viewModel(id: String) = HealthConflictViewModel(
        handle(HealthSyncCategory.MEASUREMENTS, id, 1),
        db.healthDao(),
        SyncConflictResolver(db),
    )

    private fun handle(category: String, id: String, version: Long) = SavedStateHandle(
        mapOf(
            GymRoutes.HEALTH_CONFLICT_CATEGORY_ARG to category,
            GymRoutes.HEALTH_CONFLICT_SYNC_ID_ARG to id,
            GymRoutes.HEALTH_CONFLICT_VERSION_ARG to version,
        ),
    )

    private fun hash(payload: String) = MeasurementRepository.sha256(payload)
}
