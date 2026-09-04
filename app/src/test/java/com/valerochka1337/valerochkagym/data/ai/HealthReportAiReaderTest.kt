package com.valerochka1337.valerochkagym.data.ai

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HealthReportAiReaderTest {
    @Test
    fun `reader returns a draft for strict typed response without persistence`() = runTest {
        val result = reader("""{"title":"CBC","provenance":"lab","reportedAt":"2026-01-10","observations":[{"rawName":"Hb","valueType":"NUMBER","rawValue":"125.0","observedAt":"2026-01-10","sourcePage":1,"unit":"g/L"}]}""").read(Uri.parse("content://report"))
        assertTrue(result is HealthReportAiResult.Success)
        assertEquals(1, (result as HealthReportAiResult.Success).draft.report.observations.single().sourcePage)
    }
    @Test
    fun `reader uses the exact disclosed endpoint and model without rereading configuration`() = runTest {
        val api = FakeApi("""{"title":"CBC","provenance":"lab","reportedAt":"2026-01-10","observations":[{"rawName":"Hb","valueType":"NUMBER","rawValue":"125","observedAt":"2026-01-10","sourcePage":1}]}""")
        val frozen = AiApiRequestConfiguration(AiApiConnection("https://frozen.example/v1/", "key"), "frozen-model")

        reader(api, Renderer()).read(Uri.parse("content://report"), frozen, false)

        assertEquals("frozen-model", api.request!!.model)
    }
    @Test
    fun `reader rejects duplicate and nonfinite values`() = runTest {
        val duplicate = """{"title":"CBC","provenance":"lab","reportedAt":"2026-01-10","observations":[{"rawName":"Hb","valueType":"NUMBER","rawValue":"NaN","observedAt":"2026-01-10","sourcePage":1}]}"""
        assertTrue(reader(duplicate).read(Uri.parse("content://report")) is HealthReportAiResult.Failure)
    }
    @Test
    fun `reader sends every rendered page in stable order and accepts page two provenance`() = runTest {
        val api=FakeApi("""{"title":"CBC","provenance":"lab","reportedAt":"2026-01-10","observations":[{"rawName":"Hb","valueType":"NUMBER","rawValue":"125","observedAt":"2026-01-10","sourcePage":2}]}""")
        val result=reader(api,Renderer(HealthDocumentRenderResult.Success(listOf("data:image/jpeg;base64,page1","data:image/jpeg;base64,page2")))).read(Uri.parse("content://report"))
        assertTrue(result is HealthReportAiResult.Success)
        assertEquals(2, (result as HealthReportAiResult.Success).draft.report.observations.single().sourcePage)
        val parts=api.request!!.messages.last().content as JsonArray
        val text=parts.joinToString()
        assertTrue(text.indexOf("Страница 1 из 2") < text.indexOf("page1"))
        assertTrue(text.indexOf("page1") < text.indexOf("Страница 2 из 2"))
        assertTrue(text.indexOf("Страница 2 из 2") < text.indexOf("page2"))
    }
    @Test
    fun `reader rejects source page outside rendered page count`() = runTest {
        val result=reader("""{"title":"CBC","provenance":"lab","reportedAt":"2026-01-10","observations":[{"rawName":"Hb","valueType":"NUMBER","rawValue":"125","observedAt":"2026-01-10","sourcePage":3}]}""",Renderer(HealthDocumentRenderResult.Success(listOf("data:image/jpeg;base64,1","data:image/jpeg;base64,2")))).read(Uri.parse("content://report"))
        assertTrue(result is HealthReportAiResult.Failure)
    }
    @Test
    fun `renderer failure and empty pages make no network request`() = runTest {
        val api=FakeApi("{}")
        assertTrue(reader(api,Renderer(HealthDocumentRenderResult.Failure("bad"))).read(Uri.parse("content://report")) is HealthReportAiResult.Failure)
        assertEquals(0,api.calls)
        assertTrue(reader(api,Renderer(HealthDocumentRenderResult.Success(emptyList()))).read(Uri.parse("content://report")) is HealthReportAiResult.Failure)
        assertEquals(0,api.calls)
    }
    @Test
    fun `renderer cancellation propagates without network`() = runTest {
        val api=FakeApi("{}")
        try { reader(api,Renderer(throwCancellation=true)).read(Uri.parse("content://report")); throw AssertionError("Expected cancellation") }
        catch (_: CancellationException) { assertEquals(0,api.calls) }
    }
    private fun reader(content: String,renderer:Renderer=Renderer()) = reader(FakeApi(content),renderer)
    private fun reader(api: FakeApi,renderer: Renderer) = AiApiHealthReportAiReader(api, Config(), renderer, Json { ignoreUnknownKeys = true }, UnconfinedTestDispatcher())
    private class Renderer(private val result:HealthDocumentRenderResult=HealthDocumentRenderResult.Success(listOf("data:image/jpeg;base64,AA==")),private val throwCancellation:Boolean=false) : HealthDocumentRenderer { override suspend fun render(uri: Uri):HealthDocumentRenderResult { if(throwCancellation) throw CancellationException(); return result } }
    private class Config : AiApiConfigurationProvider {
        override val isConfigured: Flow<Boolean> = flowOf(true)
        override suspend fun connection() = AiApiConnection("https://example.test/v1/", "key")
        override suspend fun requestConfiguration() = AiApiRequestConfiguration(AiApiConnection("https://example.test/v1/", "key"), "model")
    }
    private class FakeApi(private val content: String) : AiApi {
        var calls=0; var request:AiApiChatRequest?=null
        override suspend fun createCompletion(endpoint: String, authorization: String, request: AiApiChatRequest):AiApiChatResponse { calls++; this.request=request; return AiApiChatResponse(listOf(AiApiChoice(AiApiResponseMessage(content = JsonPrimitive(content))))) }
        override suspend fun getModels(endpoint: String, authorization: String) = AiModelsResponse()
    }
}
