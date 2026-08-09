package com.valerochka1337.valerochkagym.data.ai

import android.app.Application
import android.net.Uri
import com.valerochka1337.valerochkagym.data.settings.OpenRouterKeyStore
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InBodyReportAiReaderTest {

    @Test
    fun `reader sends an image multipart request to the fixed model and returns a complete draft`() = runTest {
        val api = FakeOpenRouterApi(content = completeReportJson())
        val encoder = FakePhotoEncoder()
        val reader = reader(api = api, encoder = encoder)

        val result = reader.read(Uri.parse("content://picker/inbody.jpg"))

        val success = result as InBodyReportAiResult.Success
        assertEquals(LocalDate.of(2026, 7, 24), success.draft.measuredDate)
        assertEquals(LocalTime.of(18, 6), success.draft.measuredTime)
        assertEquals(59.5, success.draft.weightKg!!, 1e-6)
        assertEquals(14.8, success.draft.bodyFatMassKg!!, 1e-6)
        assertEquals(74, success.draft.inBodyScore)
        assertEquals(7.43, success.draft.segments.getValue(InBodySegment.LEFT_LEG).leanMassKg!!, 1e-6)
        assertEquals(85.7, success.draft.segments.getValue(InBodySegment.LEFT_LEG).fatPercentage!!, 1e-6)

        val request = api.request!!
        assertEquals("google/gemma-4-26b-a4b-it:free", request.model)
        assertEquals(2_048, request.maxTokens)
        assertEquals(0.0, request.temperature!!, 0.0)
        assertTrue(request.provider.requireParameters)
        assertTrue(request.responseFormat.jsonSchema.strict)
        assertEquals("json_schema", request.responseFormat.type)
        assertTrue(request.responseFormat.jsonSchema.schema.toString().contains("LEFT_ARM"))
        assertTrue(request.responseFormat.jsonSchema.schema.toString().contains("Масса скелетной мускулатуры"))

        val userParts = request.messages.last().content as JsonArray
        assertEquals("text", (userParts[0] as kotlinx.serialization.json.JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", (userParts[1] as kotlinx.serialization.json.JsonObject)["type"]!!.jsonPrimitive.content)
        val imageUrl = ((userParts[1] as kotlinx.serialization.json.JsonObject)["image_url"]
            as kotlinx.serialization.json.JsonObject)["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,encoded", imageUrl)
        assertEquals(Uri.parse("content://picker/inbody.jpg"), encoder.uris.single())
    }

    @Test
    fun `reader reports OpenRouter errors without accepting a partial draft`() = runTest {
        val reader = reader(api = FakeOpenRouterApi(error = httpException(429)))

        val result = reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertTrue(result is InBodyReportAiResult.Failure)
        assertTrue((result as InBodyReportAiResult.Failure).message.contains("Лимит"))
    }

    @Test
    fun `reader rejects missing fields invalid date negative value and duplicate segments`() = runTest {
        val malformedReports = listOf(
            "{\"isInBodyReport\":true}",
            completeReportJson().replace("\"measuredDate\": \"2026-07-24\"", "\"measuredDate\": \"24.07.2026\""),
            completeReportJson().replace("\"weightKg\": 59.5", "\"weightKg\": -1"),
            completeReportJson().replace("\"segment\":\"RIGHT_ARM\"", "\"segment\":\"LEFT_ARM\""),
        )
        malformedReports.forEach { content ->
            val result = reader(api = FakeOpenRouterApi(content = content))
                .read(Uri.parse("content://picker/inbody.jpg"))
            assertTrue("report must be rejected: $content", result is InBodyReportAiResult.Failure)
        }
    }

    @Test
    fun `reader keeps a structurally complete report with an unread value for manual review`() = runTest {
        val incomplete = completeReportJson().replace(
            "\"recommendedCalorieIntakeKcal\": 2499",
            "\"recommendedCalorieIntakeKcal\": null",
        )

        val result = reader(api = FakeOpenRouterApi(content = incomplete))
            .read(Uri.parse("content://picker/inbody.jpg"))

        val success = result as InBodyReportAiResult.Success
        assertEquals(null, success.draft.recommendedCalorieIntakeKcal)
    }

    @Test
    fun `reader does not prepare or upload photo without an OpenRouter key`() = runTest {
        val api = FakeOpenRouterApi(content = completeReportJson())
        val encoder = FakePhotoEncoder()
        val reader = reader(api = api, encoder = encoder, key = null)

        val result = reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertEquals(InBodyReportAiResult.Failure("Укажите ключ OpenRouter в настройках"), result)
        assertTrue(api.requests.isEmpty())
        assertTrue(encoder.uris.isEmpty())
    }

    private fun reader(
        api: OpenRouterApi,
        encoder: InBodyPhotoEncoder = FakePhotoEncoder(),
        key: String? = "secret",
    ): OpenRouterInBodyReportAiReader = OpenRouterInBodyReportAiReader(
        api = api,
        keyStore = FakeOpenRouterKeyStore(key),
        photoEncoder = encoder,
        json = Json { ignoreUnknownKeys = true },
        computeDispatcher = UnconfinedTestDispatcher(),
    )

    private class FakeOpenRouterApi(
        private val content: String? = null,
        private val error: Exception? = null,
    ) : OpenRouterApi {
        val requests = mutableListOf<OpenRouterChatRequest>()
        val request: OpenRouterChatRequest? get() = requests.singleOrNull()

        override suspend fun createCompletion(
            authorization: String,
            request: OpenRouterChatRequest,
        ): OpenRouterChatResponse {
            requests += request
            error?.let { throw it }
            return OpenRouterChatResponse(
                choices = listOf(
                    OpenRouterChoice(
                        message = OpenRouterResponseMessage(content = content?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                ),
            )
        }
    }

    private class FakePhotoEncoder : InBodyPhotoEncoder {
        val uris = mutableListOf<Uri>()
        override suspend fun encode(uri: Uri): InBodyPhotoEncodingResult {
            uris += uri
            return InBodyPhotoEncodingResult.Success("data:image/jpeg;base64,encoded")
        }
    }

    private class FakeOpenRouterKeyStore(private val key: String?) : OpenRouterKeyStore {
        override val isConfigured: Flow<Boolean> = flowOf(key != null)
        override suspend fun save(value: String) = Unit
        override suspend fun read(): String? = key
        override suspend fun clear() = Unit
    }

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "".toResponseBody()))

    private companion object {
        fun completeReportJson(): String = """
            {
              "isInBodyReport": true,
              "measuredDate": "2026-07-24",
              "measuredTime": "18:06",
              "weightKg": 59.5,
              "skeletalMuscleMassKg": 24.2,
              "bodyFatPercentage": 24.9,
              "bodyFatMassKg": 14.8,
              "visceralFatLevel": 6,
              "waistHipRatio": 0.85,
              "inBodyScore": 74,
              "totalBodyWaterLiters": 32.7,
              "proteinKg": 8.7,
              "mineralsKg": 3.34,
              "bodyMassIndex": 19.4,
              "fatFreeMassKg": 44.7,
              "basalMetabolicRateKcal": 1335,
              "recommendedCalorieIntakeKcal": 2499,
              "segments": [
                {"segment":"LEFT_ARM","leanMassKg":1.99,"leanPercentage":91.7,"fatMassKg":1.0,"fatPercentage":88.8},
                {"segment":"RIGHT_ARM","leanMassKg":2.07,"leanPercentage":95.1,"fatMassKg":1.0,"fatPercentage":86.3},
                {"segment":"TRUNK","leanMassKg":19.1,"leanPercentage":91.4,"fatMassKg":7.1,"fatPercentage":92.9},
                {"segment":"LEFT_LEG","leanMassKg":7.43,"leanPercentage":108.0,"fatMassKg":2.4,"fatPercentage":85.7},
                {"segment":"RIGHT_LEG","leanMassKg":7.39,"leanPercentage":107.5,"fatMassKg":2.4,"fatPercentage":85.6}
              ]
            }
        """.trimIndent()
    }
}
