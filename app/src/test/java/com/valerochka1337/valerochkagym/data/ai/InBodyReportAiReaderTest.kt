package com.valerochka1337.valerochkagym.data.ai

import android.app.Application
import android.net.Uri
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
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InBodyReportAiReaderTest {

    @Test
    fun `reader sends an image multipart request to the selected model and returns a complete draft`() = runTest {
        val api = FakeAiApi(content = completeReportJson())
        val encoder = FakePhotoEncoder()
        val reader = reader(
            api = api,
            encoder = encoder,
            modelId = "dots-studio/dots-3-note-preview:free",
        )

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
        assertEquals("dots-studio/dots-3-note-preview:free", request.model)
        assertEquals(2_048, request.maxTokens)
        assertEquals("json_object", request.responseFormat.type)
        assertEquals(CHAT_COMPLETIONS_ENDPOINT, api.endpoint)
        assertEquals("Bearer secret", api.authorization)
        val systemContent = (request.messages.first().content as JsonPrimitive).content
        assertTrue(systemContent.contains("Используй минимум рассуждений"))
        assertTrue(systemContent.contains("<response_schema_json>"))
        assertTrue(systemContent.contains("LEFT_ARM"))
        assertTrue(systemContent.contains("Масса скелетной мускулатуры"))

        val userParts = request.messages.last().content as JsonArray
        assertEquals("text", (userParts[0] as kotlinx.serialization.json.JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", (userParts[1] as kotlinx.serialization.json.JsonObject)["type"]!!.jsonPrimitive.content)
        val imageUrl = ((userParts[1] as kotlinx.serialization.json.JsonObject)["image_url"]
            as kotlinx.serialization.json.JsonObject)["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,encoded", imageUrl)
        assertEquals(Uri.parse("content://picker/inbody.jpg"), encoder.uris.single())
    }

    @Test
    fun `reader reports API errors without accepting a partial draft`() = runTest {
        val reader = reader(api = FakeAiApi(error = httpException(429)))

        val result = reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertTrue(result is InBodyReportAiResult.Failure)
        assertTrue((result as InBodyReportAiResult.Failure).message.contains("Лимит"))
    }

    @Test
    fun `reader marks an unavailable selected model so the UI can open settings`() = runTest {
        val result = reader(api = FakeAiApi(error = httpException(503)))
            .read(Uri.parse("content://picker/inbody.jpg"))

        val failure = result as InBodyReportAiResult.Failure
        assertEquals(MODEL_UNAVAILABLE_MESSAGE, failure.message)
        assertTrue(failure.modelUnavailable)
    }

    @Test
    fun `reader reports when the model response times out`() = runTest {
        val reader = reader(api = FakeAiApi(error = SocketTimeoutException()))

        val result = reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertEquals(InBodyReportAiResult.Failure(AI_REQUEST_TIMEOUT_MESSAGE), result)
    }

    @Test
    fun `reader embeds the schema for portable json object mode`() = runTest {
        val api = FakeAiApi(content = completeReportJson())
        val reader = reader(
            api = api,
            modelId = "google/gemma-4-31b-it:free",
        )

        reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertEquals("json_object", api.request?.responseFormat?.type)
        val systemContent = (api.request?.messages?.first()?.content as JsonPrimitive).content
        assertTrue(systemContent.contains("<response_schema_json>"))
        assertTrue(systemContent.contains("LEFT_ARM"))
    }

    @Test
    fun `reader logs the raw API response before parsing it`() = runTest {
        val logger = RecordingAiResponseLogger()
        val reader = reader(
            api = FakeAiApi(content = completeReportJson()),
            responseLogger = logger,
        )

        reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertEquals(listOf(AiResponseSource.INBODY), logger.sources)
        assertEquals(listOf(MODEL_ID), logger.modelIds)
        val response = logger.responses.single()
        assertEquals(completeReportJson(), (response.choices.single().message?.content as JsonPrimitive).content)
    }

    @Test
    fun `reader logs the http status and API error body`() = runTest {
        val responseBody =
            """[{"kind":"failover","message":"upstream rejected the image","upstream_status_code":403}]"""
        val logger = RecordingAiResponseLogger()
        val reader = reader(
            api = FakeAiApi(error = httpException(502, responseBody)),
            responseLogger = logger,
        )

        reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertEquals(
            listOf(
                LoggedFailure(
                    source = AiResponseSource.INBODY,
                    modelId = MODEL_ID,
                    stage = "http",
                    httpCode = 502,
                    responseBody = responseBody,
                    throwableType = HttpException::class.java.name,
                ),
            ),
            logger.failures,
        )
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
            val result = reader(api = FakeAiApi(content = content))
                .read(Uri.parse("content://picker/inbody.jpg"))
            assertTrue("report must be rejected: $content", result is InBodyReportAiResult.Failure)
        }
    }

    @Test
    fun `reader explains when the model exhausts its completion limit`() = runTest {
        val reader = reader(
            api = FakeAiApi(
                content = null,
                finishReason = "length",
            ),
        )

        assertEquals(
            InBodyReportAiResult.Failure(
                "Модель исчерпала лимит ответа — попробуйте ещё раз или выберите другую",
            ),
            reader.read(Uri.parse("content://picker/inbody.jpg")),
        )
    }

    @Test
    fun `reader keeps a structurally complete report with an unread value for manual review`() = runTest {
        val incomplete = completeReportJson().replace(
            "\"recommendedCalorieIntakeKcal\": 2499",
            "\"recommendedCalorieIntakeKcal\": null",
        )

        val result = reader(api = FakeAiApi(content = incomplete))
            .read(Uri.parse("content://picker/inbody.jpg"))

        val success = result as InBodyReportAiResult.Success
        assertEquals(null, success.draft.recommendedCalorieIntakeKcal)
    }

    @Test
    fun `reader does not prepare or upload a photo without complete AI settings`() = runTest {
        val api = FakeAiApi(content = completeReportJson())
        val encoder = FakePhotoEncoder()
        val reader = reader(api = api, encoder = encoder, configured = false)

        val result = reader.read(Uri.parse("content://picker/inbody.jpg"))

        assertEquals(
            InBodyReportAiResult.Failure("Настройте нейросеть в настройках"),
            result,
        )
        assertTrue(api.requests.isEmpty())
        assertTrue(encoder.uris.isEmpty())
    }

    private fun reader(
        api: AiApi,
        encoder: InBodyPhotoEncoder = FakePhotoEncoder(),
        configured: Boolean = true,
        modelId: String = MODEL_ID,
        responseLogger: AiResponseLogger = NoOpAiResponseLogger,
    ): AiApiInBodyReportAiReader = AiApiInBodyReportAiReader(
        api = api,
        configurationProvider = FakeAiApiConfigurationProvider(configured, modelId),
        photoEncoder = encoder,
        json = Json { ignoreUnknownKeys = true },
        responseLogger = responseLogger,
        computeDispatcher = UnconfinedTestDispatcher(),
    )

    private class FakeAiApi(
        private val content: String? = null,
        private val error: Exception? = null,
        private val finishReason: String? = null,
    ) : AiApi {
        val requests = mutableListOf<AiApiChatRequest>()
        val request: AiApiChatRequest? get() = requests.singleOrNull()
        var endpoint: String? = null
            private set
        var authorization: String? = null
            private set

        override suspend fun createCompletion(
            endpoint: String,
            authorization: String,
            request: AiApiChatRequest,
        ): AiApiChatResponse {
            this.endpoint = endpoint
            this.authorization = authorization
            requests += request
            error?.let { throw it }
            return AiApiChatResponse(
                choices = listOf(
                    AiApiChoice(
                        message = AiApiResponseMessage(content = content?.let(::JsonPrimitive) ?: JsonNull),
                        finishReason = finishReason,
                    ),
                ),
            )
        }

        override suspend fun getModels(
            endpoint: String,
            authorization: String,
        ): AiModelsResponse = error("unused")
    }

    private class FakePhotoEncoder : InBodyPhotoEncoder {
        val uris = mutableListOf<Uri>()
        override suspend fun encode(uri: Uri): InBodyPhotoEncodingResult {
            uris += uri
            return InBodyPhotoEncodingResult.Success("data:image/jpeg;base64,encoded")
        }
    }

    private class FakeAiApiConfigurationProvider(
        configured: Boolean,
        private val modelId: String,
    ) : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(configured)
        private val configuration = configured.takeIf { it }?.let {
            AiApiRequestConfiguration(
                connection = AiApiConnection(BASE_URL, "secret"),
                modelId = modelId,
            )
        }

        override suspend fun connection(): AiApiConnection? = configuration?.connection

        override suspend fun requestConfiguration(): AiApiRequestConfiguration? = configuration
    }

    private object NoOpAiResponseLogger : AiResponseLogger {
        override fun log(
            source: AiResponseSource,
            requestedModelId: String,
            response: AiApiChatResponse,
        ) = Unit
    }

    private class RecordingAiResponseLogger : AiResponseLogger {
        val sources = mutableListOf<AiResponseSource>()
        val modelIds = mutableListOf<String>()
        val responses = mutableListOf<AiApiChatResponse>()
        val failures = mutableListOf<LoggedFailure>()

        override fun log(
            source: AiResponseSource,
            requestedModelId: String,
            response: AiApiChatResponse,
        ) {
            sources += source
            modelIds += requestedModelId
            responses += response
        }

        override fun logFailure(
            source: AiResponseSource,
            requestedModelId: String,
            stage: String,
            httpCode: Int?,
            responseBody: String?,
            throwable: Throwable?,
        ) {
            failures += LoggedFailure(
                source = source,
                modelId = requestedModelId,
                stage = stage,
                httpCode = httpCode,
                responseBody = responseBody,
                throwableType = throwable?.javaClass?.name,
            )
        }
    }

    private data class LoggedFailure(
        val source: AiResponseSource,
        val modelId: String,
        val stage: String,
        val httpCode: Int?,
        val responseBody: String?,
        val throwableType: String?,
    )

    private fun httpException(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Unit>(code, body.toResponseBody()))

    private companion object {
        const val BASE_URL = "https://ai.example.com/v1/"
        const val CHAT_COMPLETIONS_ENDPOINT = "https://ai.example.com/v1/chat/completions"
        const val MODEL_ID = "gpt-5.4"
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
