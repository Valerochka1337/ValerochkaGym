package com.valerochka1337.valerochkagym.integration

import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.RoomDaoTest
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementRepository
import com.valerochka1337.valerochkagym.data.settings.NoOpMuscleLoadUpgradeNotice
import com.valerochka1337.valerochkagym.domain.analysis.AnalyticsEngine
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowMapper
import com.valerochka1337.valerochkagym.domain.measurements.BodyMeasurementRowParser
import com.valerochka1337.valerochkagym.ui.analysis.AnalysisViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Primary `Measurements` import reaches Analysis without creating derived health Sheet rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrimaryImportAnalysisIntegrationTest : RoomDaoTest() {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `imported measurement updates Analysis and retains only primary managed fields`() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val source = BodyMeasurementEntity("inbody", 1_700_000_000_000, weightKg = 74.2, bodyFatPercentage = 18.0)
            val row = BodyMeasurementRowMapper.versionedRow(source, 1, source.measuredAt, false, "hash", "inbody:1")
                .map { it?.toString().orEmpty() }
            val parsed = BodyMeasurementRowParser.parse(listOf(BodyMeasurementRowMapper.HEADER_ROW, row))
            assertEquals(0, parsed.skippedRows)

            MeasurementRepository(db).applyImported(parsed.snapshots.single())
            val viewModel = AnalysisViewModel(
                workoutDao = db.workoutDao(),
                exerciseMuscleDao = db.exerciseMuscleDao(),
                bodyMeasurementDao = db.bodyMeasurementDao(),
                healthDao = db.healthDao(),
                engine = AnalyticsEngine(),
                computeDispatcher = mainDispatcherRule.testDispatcher,
                upgradeNotice = NoOpMuscleLoadUpgradeNotice,
                savedStateHandle = SavedStateHandle(),
            )
            val collector = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.health.latestMeasurement?.id == "inbody" }

            assertEquals(74.2, state.health.latestMeasurement!!.weightKg!!, 0.0)
            assertTrue(BodyMeasurementRowMapper.HEADER_ROW.none { it.contains("derived", ignoreCase = true) })
            assertEquals(UploadStatus.UPLOADED, db.bodyMeasurementDao().getById("inbody")!!.uploadStatus)
            collector.cancel()
        }
}
