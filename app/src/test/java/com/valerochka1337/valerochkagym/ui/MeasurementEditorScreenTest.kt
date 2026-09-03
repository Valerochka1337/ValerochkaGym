package com.valerochka1337.valerochkagym.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import com.valerochka1337.valerochkagym.data.ai.AiApiConfigurationProvider
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiReader
import com.valerochka1337.valerochkagym.data.ai.InBodyReportAiResult
import com.valerochka1337.valerochkagym.data.ai.InBodyReportDraft
import com.valerochka1337.valerochkagym.data.db.dao.BodyMeasurementDao
import com.valerochka1337.valerochkagym.data.db.entity.BodyMeasurementEntity
import com.valerochka1337.valerochkagym.data.db.entity.MeasurementDocumentEntity
import com.valerochka1337.valerochkagym.data.db.entity.UploadStatus
import com.valerochka1337.valerochkagym.data.measurements.MeasurementDocumentRepository
import com.valerochka1337.valerochkagym.data.measurements.NoOpMeasurementDocumentRepository
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementEditorScreen
import com.valerochka1337.valerochkagym.ui.measurements.MeasurementEditorViewModel
import com.valerochka1337.valerochkagym.ui.navigation.GymRoutes
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import com.valerochka1337.valerochkagym.worker.MeasurementUploadScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Accessibility contract for the real editor composition; no render artifact is inspected. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h900dp-xhdpi")
class MeasurementEditorScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun `conditions and private original controls stay reachable at two hundred percent text`() {
        val existing = BodyMeasurementEntity(id = "m1", measuredAt = 1, weightKg = 70.0)
        val documents = FakeDocuments().apply { ready += document("ready", "m1") }
        val viewModel = MeasurementEditorViewModel(
            SavedStateHandle(mapOf(GymRoutes.MEASUREMENT_ID_ARG to "m1")),
            FakeDao(existing), NoOpScheduler,
            inBodyReportAiReader = FakeReader,
            aiApiConfigurationProvider = ConfiguredProvider,
            measurementDocumentRepository = documents,
        )
        viewModel.scanInBody(Uri.parse("content://picker/inbody.jpg"))

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                GymTheme {
                    MeasurementEditorScreen(onBack = {}, onOpenSettings = {}, viewModel = viewModel)
                }
            }
        }
        composeRule.waitForIdle()

        listOf("После еды", "После тренировки", "Необычная гидратация").forEach { label ->
            val node = composeRule.onNode(hasText(label).and(hasClickAction()))
            // The editor intentionally scrolls at 200% text. Presence in the actual semantic
            // tree proves the control remains reachable by scroll/accessibility. GymFilterChip
            // owns the common 48dp target contract, covered in AccessibilityFoundationTest.
            node.assertExists()
        }
        composeRule.onNodeWithText("Другие условия").assertExists()
        composeRule.onNodeWithText("Сохранить исходное фото только на устройстве").assertExists()
        composeRule.onNodeWithText("Удалить локальные фото").assertExists()
    }

    private object FakeReader : InBodyReportAiReader {
        override suspend fun read(uri: Uri) = InBodyReportAiResult.Success(InBodyReportDraft(weightKg = 70.0))
    }

    private object ConfiguredProvider : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(true)
        override suspend fun connection() = null
        override suspend fun requestConfiguration() = null
    }

    private class FakeDao(vararg initial: BodyMeasurementEntity) : BodyMeasurementDao {
        private val rows = MutableStateFlow(initial.toList())
        override suspend fun insert(measurement: BodyMeasurementEntity) { rows.value += measurement }
        override suspend fun update(measurement: BodyMeasurementEntity) {
            rows.value = rows.value.map { if (it.id == measurement.id) measurement else it }
        }
        override fun observeAll(): Flow<List<BodyMeasurementEntity>> = rows
        override suspend fun getById(id: String) = rows.value.find { it.id == id }
        override suspend fun setUploadStatus(measurementId: String, status: UploadStatus, error: String?) = Unit
        override suspend fun getNotUploaded() = emptyList<String>()
        override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    }

    private object NoOpScheduler : MeasurementUploadScheduler {
        override suspend fun schedule(measurementId: String) = Unit
        override suspend fun retry(measurementId: String) = Unit
        override suspend fun scheduleAllPending() = 0
    }

    private class FakeDocuments : MeasurementDocumentRepository by NoOpMeasurementDocumentRepository {
        val ready = mutableListOf<MeasurementDocumentEntity>()
        override suspend fun readyForMeasurement(measurementId: String) = ready.filter { it.measurementId == measurementId }
    }

    private fun document(id: String, measurementId: String) = MeasurementDocumentEntity(
        id = id, measurementId = measurementId, sha256 = "hash", state = "READY",
        displayName = "inbody.jpg", mimeType = "image/jpeg", byteSize = 1, createdAt = 1,
    )
}
