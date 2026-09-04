package com.valerochka1337.valerochkagym.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelCatalogTest {

  @Test
  fun `base url normalization adds scheme api version and trailing slash`() {
    assertEquals(
        "https://ai.example.com/v1/",
        normalizeAiBaseUrl(" ai.example.com "),
    )
    assertEquals(
        "https://ai.example.com/gateway/v1/",
        normalizeAiBaseUrl("https://ai.example.com/gateway"),
    )
  }

  @Test
  fun `base url normalization accepts complete API endpoints`() {
    assertEquals(
        "https://ai.example.com/v1/",
        normalizeAiBaseUrl("https://ai.example.com/v1/chat/completions"),
    )
    assertEquals(
        "https://ai.example.com/api/v1/",
        normalizeAiBaseUrl("https://ai.example.com/api/v1/models"),
    )
    assertEquals(
        "https://ai.example.com/v1/",
        normalizeAiBaseUrl("https://ai.example.com/chat/completions"),
    )
  }

  @Test
  fun `base url normalization accepts http and rejects credentialed addresses`() {
    assertEquals(
        "http://ai.example.com/v1/",
        normalizeAiBaseUrl("http://ai.example.com"),
    )
    assertNull(normalizeAiBaseUrl("https://user:pass@ai.example.com"))
    assertNull(normalizeAiBaseUrl("https://ai.example.com?token=secret"))
    assertNull(normalizeAiBaseUrl("not a host"))
  }

  @Test
  fun `api endpoints resolve from the automatically versioned base path`() {
    val rootBaseUrl = requireNotNull(normalizeAiBaseUrl("http://ai.example.com"))
    val customBaseUrl = requireNotNull(normalizeAiBaseUrl("http://ai.example.com/custom"))
    assertEquals(
        "http://ai.example.com/v1/models",
        aiModelsEndpoint(rootBaseUrl),
    )
    assertEquals(
        "http://ai.example.com/v1/chat/completions",
        aiApiChatCompletionsEndpoint(rootBaseUrl),
    )
    assertEquals(
        "http://ai.example.com/custom/v1/chat/completions",
        aiApiChatCompletionsEndpoint(customBaseUrl),
    )
  }

  @Test
  fun `catalog requests authenticated models and sorts unique ids`() = runTest {
    val api =
        FakeAiApi(
            models =
                listOf(
                    AiModelDto(id = " zeta ", ownedBy = " owner "),
                    AiModelDto(id = "Alpha"),
                    AiModelDto(id = "Alpha", ownedBy = "duplicate"),
                    AiModelDto(id = "  "),
                ),
        )
    val catalog =
        RemoteAiModelCatalog(
            api = api,
            configurationProvider =
                FakeConfigurationProvider(
                    AiApiConnection(
                        baseUrl = "https://ai.example.com/gateway/v1/",
                        apiKey = "sk-ai",
                    ),
                ),
        )

    assertEquals(
        listOf(AiModel("Alpha"), AiModel("zeta", "owner")),
        catalog.getModels(),
    )
    assertEquals("https://ai.example.com/gateway/v1/models", api.modelsEndpoint)
    assertEquals("Bearer sk-ai", api.authorization)
  }

  @Test
  fun `catalog requires a configured server and key`() = runTest {
    val catalog =
        RemoteAiModelCatalog(
            api = FakeAiApi(emptyList()),
            configurationProvider = FakeConfigurationProvider(null),
        )

    assertTrue(runCatching { catalog.getModels() }.exceptionOrNull() is IllegalStateException)
  }

  private class FakeAiApi(
      private val models: List<AiModelDto>,
  ) : AiApi {
    var modelsEndpoint: String? = null
    var authorization: String? = null

    override suspend fun getModels(
        endpoint: String,
        authorization: String,
    ): AiModelsResponse {
      modelsEndpoint = endpoint
      this.authorization = authorization
      return AiModelsResponse(models)
    }

    override suspend fun createCompletion(
        endpoint: String,
        authorization: String,
        request: AiApiChatRequest,
    ): AiApiChatResponse = error("Not used")
  }

  private class FakeConfigurationProvider(
      private val configuredConnection: AiApiConnection?,
  ) : AiApiConfigurationProvider {
    override val isConfigured: Flow<Boolean> = flowOf(configuredConnection != null)

    override suspend fun connection(): AiApiConnection? = configuredConnection

    override suspend fun requestConfiguration(): AiApiRequestConfiguration? = null
  }
}
