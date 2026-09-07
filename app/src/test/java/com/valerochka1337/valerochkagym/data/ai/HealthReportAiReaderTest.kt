package com.valerochka1337.valerochkagym.data.ai

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.InterruptedIOException
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HealthReportAiReaderTest {
    @Test
    fun `reader returns an editable typed draft from whitespace wrapped bare JSON`() = runTest {
        val result = reader("  \n${validPayload()}\t ").read(REPORT_URI)

        val success = result as HealthReportAiResult.Success
        assertEquals("CBC", success.draft.report.title)
        assertEquals(1, success.draft.sourcePages.single())
        assertEquals(125.0, (success.draft.report.observations.single().value as com.valerochka1337.valerochkagym.domain.health.HealthRawValue.Number).value, 0.0)
        assertEquals(
            LocalDate.of(2026, 1, 10).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            success.draft.report.reportedAt,
        )
    }

    @Test
    fun `reader accepts exactly one isolated json fence`() = runTest {
        val result = reader(" \n```json\n${validPayload()}\n```\t").read(REPORT_URI)

        assertTrue(result is HealthReportAiResult.Success)
    }

    @Test
    fun `reader rejects prose malformed fences multiple objects and trailing payload`() = runTest {
        val payload = validPayload()
        listOf(
            "Ответ: $payload",
            "```\n$payload\n```",
            "```JSON\n$payload\n```",
            "```json\n$payload\n```\n```json\n$payload\n```",
            "$payload$payload",
            "$payload\nпроверьте результат",
            "{not-json}",
        ).forEach { content ->
            assertTrue("payload must be rejected: $content", reader(content).read(REPORT_URI) is HealthReportAiResult.Failure)
        }
    }

    @Test
    fun `reader sends a report to a disclosed public HTTP recipient`() = runTest {
        val api = FakeApi(content = validPayload())
        val configuration = AiApiRequestConfiguration(
            connection = AiApiConnection("http://medical.example/v1/", "API_KEY_SENTINEL"),
            modelId = "frozen-model",
        )

        val result = reader(api).read(REPORT_URI, configuration, loopbackHttpConsent = false)

        assertTrue(result is HealthReportAiResult.Success)
        assertEquals(1, api.calls)
        assertEquals("frozen-model", api.request!!.model)
        assertEquals("http://medical.example/v1/chat/completions", api.endpoint)
    }

    @Test
    fun `reader rejects invalid and unconfirmed local recipients before rendering or sending`() = runTest {
        val api = FakeApi(content = validPayload())
        val renderer = Renderer()

        val invalid = reader(api, renderer).read(
            REPORT_URI,
            AiApiRequestConfiguration(AiApiConnection("medical.example", "key"), "model"),
            loopbackHttpConsent = false,
        )
        val local = reader(api, renderer).read(
            REPORT_URI,
            AiApiRequestConfiguration(AiApiConnection("http://localhost:11434/v1/", "key"), "model"),
            loopbackHttpConsent = false,
        )

        assertTrue(invalid is HealthReportAiResult.Failure)
        assertTrue(local is HealthReportAiResult.Failure)
        assertEquals(0, renderer.calls)
        assertEquals(0, api.calls)
    }

    @Test
    fun `reader sends complete structured schema untrusted document instruction and portable json object format`() = runTest {
        val api = FakeApi(content = validPayload())

        reader(api).read(REPORT_URI)

        val request = api.request!!
        assertEquals("json_object", request.responseFormat.type)
        val prompt = request.messages.first().content.jsonPrimitive.content
        assertTrue(prompt.contains("response_schema_json"))
        assertTrue(prompt.contains("недоверенные данные"))
        val schema = schemaFrom(prompt)
        assertEquals("object", schema.string("type"))
        assertTrue(schema.stringArray("required").containsAll(listOf("title", "provenance", "reportedAt", "observations")))
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
        val properties = schema["properties"]!!.jsonObject
        assertIsoDateSchema(properties["reportedAt"]!!.jsonObject)
        val observationSchema = properties["observations"]!!.jsonObject["items"]!!.jsonObject
        val observationProperties = observationSchema["properties"]!!.jsonObject
        assertTrue(observationSchema.stringArray("required").contains("valueType"))
        assertIsoDateSchema(observationProperties["observedAt"]!!.jsonObject)
        assertEquals(
            listOf("NUMBER", "NUMBER_WITH_OPERATOR", "RANGE", "CATEGORY", "CODE", "TEXT"),
            observationProperties["valueType"]!!.jsonObject.stringArray("enum"),
        )
    }

    @Test
    fun `reader rejects non ISO report and result dates`() = runTest {
        listOf(
            validPayload().replace("2026-01-10", "10.01.2026"),
            validPayload().replace("\"observedAt\":\"2026-01-10\"", "\"observedAt\":\"2026-1-10\""),
        ).forEach { content ->
            assertTrue(reader(content).read(REPORT_URI) is HealthReportAiResult.Failure)
        }
    }

    @Test
    fun `reader rejects blank report title and provenance`() = runTest {
        listOf(
            validPayload().replace("\"title\":\"CBC\"", "\"title\":\"  \""),
            validPayload().replace("\"provenance\":\"lab\"", "\"provenance\":\"\\t\""),
        ).forEach { content ->
            assertTrue(reader(content).read(REPORT_URI) is HealthReportAiResult.Failure)
        }
    }

    @Test
    fun `reader accepts absent and null optional metadata but rejects non string metadata`() = runTest {
        val absentMetadata = observation().replace(",\"unit\":\"g/L\"", "")
        val nullMetadata = observation().replace("\"unit\":\"g/L\"", "\"unit\":null,\"method\":null")
        assertTrue(reader(validPayload(observations = "[$absentMetadata]")).read(REPORT_URI) is HealthReportAiResult.Success)
        assertTrue(reader(validPayload(observations = "[$nullMetadata]")).read(REPORT_URI) is HealthReportAiResult.Success)
        listOf(
            observation().replace("\"unit\":\"g/L\"", "\"unit\":1"),
            observation().replace("\"unit\":\"g/L\"", "\"unit\":{}"),
        ).forEach { malformed ->
            assertTrue(reader(validPayload(observations = "[$malformed]")).read(REPORT_URI) is HealthReportAiResult.Failure)
        }
    }

    @Test
    fun `reader maps every supported typed value and rejects unknown fields`() = runTest {
        listOf(
            "NUMBER" to "125.0",
            "NUMBER_WITH_OPERATOR" to ">=2.0",
            "RANGE" to "1-2",
            "CATEGORY" to "positive",
            "CODE" to "A12",
            "TEXT" to "not detected",
        ).forEach { (type, raw) ->
            val payload = validPayload(
                observations = "[${observation(type = type, raw = raw)}]",
            )
            assertTrue("$type must be retained", reader(payload).read(REPORT_URI) is HealthReportAiResult.Success)
        }
        assertTrue(
            reader(validPayload().replace("\"observations\"", "\"unknown\":true,\"observations\""))
                .read(REPORT_URI) is HealthReportAiResult.Failure,
        )
    }

    @Test
    fun `top level error precedes choice error finish reason and payload`() = runTest {
        val result = reader(
            FakeApi(
                response = AiApiChatResponse(
                    choices = listOf(
                        AiApiChoice(
                            message = AiApiResponseMessage(content = JsonPrimitive("RAW_MEDICAL_BODY")),
                            finishReason = "length",
                            error = AiApiError(type = "insufficient_quota", message = "CHOICE_BODY"),
                        ),
                    ),
                    error = AiApiError(type = "authentication", message = "TOP_BODY"),
                ),
            ),
        ).read(REPORT_URI)

        assertFailureContains(result, "API key")
        assertNoSentinel(result)
    }

    @Test
    fun `choice error precedes finish reason and payload`() = runTest {
        val result = reader(
            FakeApi(
                response = AiApiChatResponse(
                    choices = listOf(
                        AiApiChoice(message = AiApiResponseMessage(content = JsonPrimitive(validPayload()))),
                        AiApiChoice(
                            message = AiApiResponseMessage(content = JsonPrimitive("RAW_MEDICAL_BODY")),
                            finishReason = "error",
                            error = AiApiError(type = "insufficient_quota", message = "CHOICE_BODY"),
                        ),
                    ),
                ),
            ),
        ).read(REPORT_URI)

        assertFailureContains(result, "лимит")
    }

    @Test
    fun `top level string error code precedes choice string error code`() = runTest {
        val result = reader(
            FakeApi(
                response = AiApiChatResponse(
                    choices = listOf(
                        AiApiChoice(
                            message = AiApiResponseMessage(content = JsonPrimitive("RAW_MEDICAL_BODY")),
                            finishReason = "length",
                            error = AiApiError(code = JsonPrimitive("model_unavailable")),
                        ),
                    ),
                    error = AiApiError(code = JsonPrimitive("rate_limit_exceeded")),
                ),
            ),
        ).read(REPORT_URI)

        assertFailureContains(result, "Лимит запросов")
    }

    @Test
    fun `choice string error code precedes selected payload`() = runTest {
        val result = reader(
            FakeApi(
                response = AiApiChatResponse(
                    choices = listOf(
                        AiApiChoice(message = AiApiResponseMessage(content = JsonPrimitive(validPayload()))),
                        AiApiChoice(error = AiApiError(code = JsonPrimitive("model_unavailable"))),
                    ),
                ),
            ),
        ).read(REPORT_URI)

        assertFailureContains(result, "Выбранная модель")
    }

    @Test
    fun `reader handles error and length finish reasons before payload parsing`() = runTest {
        val error = reader(FakeApi(content = "RAW_MEDICAL_BODY", finishReason = "error")).read(REPORT_URI)
        val length = reader(FakeApi(content = "RAW_MEDICAL_BODY", finishReason = "length")).read(REPORT_URI)

        assertFailureContains(error, "не завершил")
        assertFailureContains(length, "лимит ответа")
    }

    @Test
    fun `reader maps HTTP provider timeout and network failures without leaking sentinels`() = runTest {
        val failures = listOf(
            FakeApi(failure = httpException(401)) to "API key",
            FakeApi(failure = httpException(403)) to "API key",
            FakeApi(failure = httpException(402)) to "баланс",
            FakeApi(failure = httpException(429)) to "Лимит запросов",
            FakeApi(failure = httpException(503)) to "Выбранная модель",
            FakeApi(failure = InterruptedIOException("RAW_MEDICAL_BODY")) to "слишком долго",
            FakeApi(failure = IOException("RAW_MEDICAL_BODY")) to "связаться",
        )

        failures.forEach { (api, expected) ->
            val result = reader(api, renderer = Renderer(page = "data:image/jpeg;base64,DOCUMENT_BYTES_SENTINEL")).read(REPORT_URI)
            assertFailureContains(result, expected)
            assertNoSentinel(result)
        }
    }

    @Test
    fun `reader maps model unavailable type and does not leak provider error body`() = runTest {
        val result = reader(
            FakeApi(
                response = AiApiChatResponse(
                    error = AiApiError(type = "model_unavailable", message = "RAW_MEDICAL_BODY"),
                ),
            ),
            renderer = Renderer(page = "data:image/jpeg;base64,DOCUMENT_BYTES_SENTINEL"),
        ).read(REPORT_URI)

        assertFailureContains(result, "Выбранная модель")
        assertNoSentinel(result)
    }

    @Test
    fun `reader preserves page order bounds duplicate finite value and frozen configuration contracts`() = runTest {
        val api = FakeApi(content = validPayload(sourcePage = 2))
        val renderer = Renderer(pages = listOf("data:image/jpeg;base64,page1", "data:image/jpeg;base64,page2"))
        val frozen = AiApiRequestConfiguration(AiApiConnection("https://frozen.example/v1/", "API_KEY_SENTINEL"), "frozen-model")

        val result = reader(api, renderer).read(REPORT_URI, frozen, false)

        assertTrue(result is HealthReportAiResult.Success)
        assertEquals("frozen-model", api.request!!.model)
        val pageParts = api.request!!.messages.last().content.toString()
        assertTrue(pageParts.indexOf("Страница 1 из 2") < pageParts.indexOf("page1"))
        assertTrue(pageParts.indexOf("page1") < pageParts.indexOf("Страница 2 из 2"))
        assertTrue(reader(validPayload(sourcePage = 3), renderer).read(REPORT_URI) is HealthReportAiResult.Failure)
        assertTrue(reader(validPayload(raw = "NaN"), renderer).read(REPORT_URI) is HealthReportAiResult.Failure)
        assertTrue(reader(validPayload(observations = "[${observation()},${observation()}]"), renderer).read(REPORT_URI) is HealthReportAiResult.Failure)
    }

    @Test
    fun `renderer failures empty pages and cancellation avoid network and preserve cancellation`() = runTest {
        val api = FakeApi(content = validPayload())
        assertTrue(reader(api, Renderer(result = HealthDocumentRenderResult.Failure("RAW_MEDICAL_BODY"))).read(REPORT_URI) is HealthReportAiResult.Failure)
        assertTrue(reader(api, Renderer(pages = emptyList())).read(REPORT_URI) is HealthReportAiResult.Failure)
        assertEquals(0, api.calls)

        try {
            reader(api, Renderer(throwsCancellation = true)).read(REPORT_URI)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, api.calls)
        }
    }

    private fun assertFailureContains(result: HealthReportAiResult, expected: String) {
        val message = (result as HealthReportAiResult.Failure).message
        assertTrue("$message must contain $expected", message.contains(expected))
    }

    private fun assertNoSentinel(result: HealthReportAiResult) {
        val message = (result as HealthReportAiResult.Failure).message
        listOf("API_KEY_SENTINEL", "DOCUMENT_BYTES_SENTINEL", "RAW_MEDICAL_BODY", "TOP_BODY", "CHOICE_BODY").forEach {
            assertFalse("failure must not expose $it", message.contains(it))
        }
    }

    private fun schemaFrom(prompt: String): JsonObject = Json.parseToJsonElement(
        prompt.substringAfter("<response_schema_json>\n").substringBefore("</response_schema_json>").trim(),
    ).jsonObject

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.stringArray(name: String): List<String> =
        getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private fun assertIsoDateSchema(schema: JsonObject) {
        assertEquals("string", schema.string("type"))
        assertEquals("date", schema.string("format"))
        assertEquals("^\\d{4}-\\d{2}-\\d{2}$", schema.string("pattern"))
    }

    private fun reader(
        content: String,
        renderer: Renderer = Renderer(),
    ): AiApiHealthReportAiReader = reader(FakeApi(content = content), renderer)

    private fun reader(
        api: FakeApi,
        renderer: Renderer = Renderer(),
    ): AiApiHealthReportAiReader = AiApiHealthReportAiReader(
        api = api,
        configurationProvider = Config(),
        renderer = renderer,
        json = Json { ignoreUnknownKeys = true },
        dispatcher = UnconfinedTestDispatcher(),
    )

    private class Renderer(
        result: HealthDocumentRenderResult? = null,
        pages: List<String> = listOf("data:image/jpeg;base64,AA=="),
        private val throwsCancellation: Boolean = false,
    ) : HealthDocumentRenderer {
        private val result = result ?: HealthDocumentRenderResult.Success(pages)
        var calls = 0
        constructor(page: String) : this(pages = listOf(page))

        override suspend fun render(uri: Uri): HealthDocumentRenderResult {
            calls++
            if (throwsCancellation) throw CancellationException()
            return result
        }
    }

    private class Config : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(true)
        override suspend fun connection() = AiApiConnection("https://example.test/v1/", "API_KEY_SENTINEL")
        override suspend fun requestConfiguration() = AiApiRequestConfiguration(connection(), "model")
    }

    private class FakeApi(
        content: String? = null,
        finishReason: String? = null,
        private val failure: Throwable? = null,
        response: AiApiChatResponse? = null,
    ) : AiApi {
        var calls = 0
        var request: AiApiChatRequest? = null
        var endpoint: String? = null
        private val response = response ?: AiApiChatResponse(
            choices = listOf(AiApiChoice(AiApiResponseMessage(content = content?.let(::JsonPrimitive)), finishReason)),
        )

        override suspend fun createCompletion(
            endpoint: String,
            authorization: String,
            request: AiApiChatRequest,
        ): AiApiChatResponse {
            calls++
            this.request = request
            this.endpoint = endpoint
            failure?.let { throw it }
            return response
        }

        override suspend fun getModels(endpoint: String, authorization: String): AiModelsResponse = error("unused")
    }

    private fun validPayload(
        sourcePage: Int = 1,
        raw: String = "125.0",
        observations: String = "[${observation(sourcePage, raw)}]",
    ): String = """{"title":"CBC","provenance":"lab","reportedAt":"2026-01-10","observations":$observations}"""

    private fun observation(
        sourcePage: Int = 1,
        raw: String = "125.0",
        type: String = "NUMBER",
    ): String =
        """{"rawName":"Hb","valueType":"$type","rawValue":"$raw","observedAt":"2026-01-10","sourcePage":$sourcePage,"unit":"g/L"}"""

    private fun httpException(code: Int): HttpException =
        HttpException(Response.error<Unit>(code, "RAW_MEDICAL_BODY".toResponseBody()))

    private companion object {
        val REPORT_URI: Uri = Uri.parse("content://report")
    }
}
