package com.valerochka1337.valerochkagym.ui

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementsScreen
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementsViewModel
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Рендер-смоук пустого и заполненного экрана замеров; снимки лежат рядом с analysis-render. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w420dp-h4000dp-xhdpi")
class MeasurementsRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `empty measurements screen renders`() {
        render(emptyList(), "measurements-empty.png")
    }

    @Test
    fun `filled measurements screen renders charts and history`() {
        val now = System.currentTimeMillis()
        render(
            listOf(
                BodyMeasurementEntity(
                    id = "latest",
                    measuredAt = now,
                    weightKg = 70.0,
                    skeletalMuscleMassKg = 28.0,
                    bodyFatPercentage = 25.0,
                    visceralFatLevel = 8,
                    waistCm = 72.0,
                    chestCm = 95.0,
                    hipsCm = 96.0,
                    rightRelaxedArmCm = 31.0,
                    rightThighCm = 56.0,
                    uploadStatus = UploadStatus.UPLOADED,
                ),
                BodyMeasurementEntity(
                    id = "previous",
                    measuredAt = now - 20 * DAY,
                    weightKg = 72.0,
                    skeletalMuscleMassKg = 27.5,
                    bodyFatPercentage = 27.0,
                    visceralFatLevel = 9,
                    waistCm = 74.0,
                    chestCm = 96.0,
                    hipsCm = 97.0,
                    rightRelaxedArmCm = 30.5,
                    rightThighCm = 57.0,
                ),
            ),
            "measurements-filled.png",
        )
    }

    private fun render(measurements: List<BodyMeasurementEntity>, fileName: String) {
        val viewModel = MeasurementsViewModel(
            FakeBodyMeasurementDao(measurements),
            FakeMeasurementUploadScheduler(),
            Dispatchers.Unconfined,
        )
        composeRule.setContent {
            GymTheme {
                MeasurementsScreen(
                    onBack = {},
                    onCreateMeasurement = {},
                    onEditMeasurement = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val dir = File("build/reports/analysis-render").apply { mkdirs() }
        FileOutputStream(File(dir, fileName)).use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private class FakeBodyMeasurementDao(initial: List<BodyMeasurementEntity>) : BodyMeasurementDao {
        private val measurements = MutableStateFlow(initial)
        override suspend fun insert(measurement: BodyMeasurementEntity) = Unit
        override suspend fun update(measurement: BodyMeasurementEntity) = Unit
        override fun observeAll(): Flow<List<BodyMeasurementEntity>> = measurements
        override suspend fun getById(id: String): BodyMeasurementEntity? = measurements.value.find { it.id == id }
        override suspend fun setUploadStatus(measurementId: String, status: UploadStatus, error: String?) = Unit
        override suspend fun getNotUploaded(): List<String> = emptyList()
        override suspend fun delete(id: String) = Unit
    }

    private class FakeMeasurementUploadScheduler : MeasurementUploadScheduler {
        override fun schedule(measurementId: String) = Unit
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending(): Int = 0
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1_000L
    }
}
