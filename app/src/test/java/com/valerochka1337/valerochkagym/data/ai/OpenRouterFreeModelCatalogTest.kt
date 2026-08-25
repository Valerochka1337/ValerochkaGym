package com.valerochka1337.valerochkagym.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterFreeModelCatalogTest {

    @Test
    fun `catalog keeps free vision models with JSON output and orders strict schema first`() = runTest {
        val catalog = RemoteOpenRouterFreeModelCatalog(
            FakeOpenRouterModelsApi(
                listOf(
                    model(
                        id = "vendor/temporary:free",
                        name = "Temporary",
                        contextLength = 900_000,
                        expiresAt = "2026-09-30",
                    ),
                    model(
                        id = "vendor/stable:free",
                        name = "Stable",
                        contextLength = 300_000,
                    ),
                    model(
                        id = "vendor/paid",
                        name = "Paid",
                        promptPrice = "0.000001",
                    ),
                    model(
                        id = "vendor/text-only:free",
                        name = "Text only",
                        inputModalities = listOf("text"),
                    ),
                    model(
                        id = "vendor/json-object:free",
                        name = "JSON object",
                        supportedParameters = listOf("response_format"),
                    ),
                    model(
                        id = "vendor/no-json:free",
                        name = "No JSON",
                        supportedParameters = emptyList(),
                    ),
                    model(
                        id = "vendor/audio-output:free",
                        name = "Audio output",
                        outputModalities = listOf("audio"),
                    ),
                ),
            ),
        )

        val models = catalog.getModels()

        assertEquals(
            listOf(
                DEFAULT_OPEN_ROUTER_MODEL_ID,
                "vendor/stable:free",
                "vendor/temporary:free",
                "vendor/json-object:free",
            ),
            models.map(OpenRouterFreeModel::id),
        )
        assertEquals(
            listOf(
                OpenRouterJsonMode.JSON_SCHEMA,
                OpenRouterJsonMode.JSON_SCHEMA,
                OpenRouterJsonMode.JSON_SCHEMA,
                OpenRouterJsonMode.JSON_OBJECT,
            ),
            models.map(OpenRouterFreeModel::jsonMode),
        )
    }

    @Test
    fun `catalog recognizes zero prices written with decimal places`() = runTest {
        val catalog = RemoteOpenRouterFreeModelCatalog(
            FakeOpenRouterModelsApi(
                listOf(
                    model(
                        id = "vendor/zero-decimal:free",
                        name = "Zero decimal",
                        promptPrice = "0.000",
                        completionPrice = "0.0",
                        imagePrice = "0.0000",
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(DEFAULT_OPEN_ROUTER_MODEL_ID, "vendor/zero-decimal:free"),
            catalog.getModels().map(OpenRouterFreeModel::id),
        )
    }

    @Test
    fun `catalog disables optional reasoning and minimizes mandatory reasoning`() = runTest {
        val catalog = RemoteOpenRouterFreeModelCatalog(
            FakeOpenRouterModelsApi(
                listOf(
                    model(
                        id = "vendor/optional-reasoning:free",
                        name = "Optional reasoning",
                        supportedParameters = listOf("response_format", "structured_outputs", "reasoning"),
                        reasoning = OpenRouterModelReasoning(
                            mandatory = false,
                            supportedEfforts = listOf("high", "low"),
                        ),
                    ),
                    model(
                        id = "vendor/mandatory-reasoning:free",
                        name = "Mandatory reasoning",
                        supportedParameters = listOf("response_format", "structured_outputs", "reasoning"),
                        reasoning = OpenRouterModelReasoning(
                            mandatory = true,
                            supportedEfforts = listOf("max", "high", "low"),
                        ),
                    ),
                ),
            ),
        )

        val models = catalog.getModels().associateBy(OpenRouterFreeModel::id)

        assertEquals("none", models.getValue("vendor/optional-reasoning:free").reasoningEffort)
        assertEquals("low", models.getValue("vendor/mandatory-reasoning:free").reasoningEffort)
        assertEquals(null, models.getValue(DEFAULT_OPEN_ROUTER_MODEL_ID).reasoningEffort)
    }

    @Test
    fun `JSON object response format does not serialize an empty schema`() {
        val encoded = networkJson.encodeToString(
            OpenRouterResponseFormat(type = "json_object"),
        )

        assertEquals("{\"type\":\"json_object\"}", encoded)
    }

    @Test
    fun `completion request serializes a selected reasoning effort only when set`() {
        val baseRequest = OpenRouterChatRequest(
            model = "vendor/model:free",
            messages = listOf(OpenRouterMessage.text(role = "user", text = "test")),
            responseFormat = OpenRouterResponseFormat(type = "json_object"),
            provider = OpenRouterProviderPreferences(requireParameters = true),
            maxTokens = 2_048,
        )

        assertFalse(networkJson.encodeToString(baseRequest).contains("\"reasoning\""))
        assertTrue(
            networkJson.encodeToString(
                baseRequest.copy(reasoning = OpenRouterReasoningPreferences(effort = "low")),
            ).contains("\"reasoning\":{\"effort\":\"low\"}"),
        )
    }

    private fun model(
        id: String,
        name: String,
        contextLength: Int = 200_000,
        promptPrice: String = "0",
        completionPrice: String = "0",
        imagePrice: String? = null,
        inputModalities: List<String> = listOf("text", "image"),
        outputModalities: List<String> = listOf("text"),
        supportedParameters: List<String> = listOf("response_format", "structured_outputs"),
        reasoning: OpenRouterModelReasoning? = null,
        expiresAt: String? = null,
    ): OpenRouterModelDto = OpenRouterModelDto(
        id = id,
        name = name,
        contextLength = contextLength,
        architecture = OpenRouterModelArchitecture(
            inputModalities = inputModalities,
            outputModalities = outputModalities,
        ),
        pricing = OpenRouterModelPricing(
            prompt = promptPrice,
            completion = completionPrice,
            image = imagePrice,
        ),
        supportedParameters = supportedParameters,
        reasoning = reasoning,
        expirationDate = expiresAt,
    )

    private class FakeOpenRouterModelsApi(
        private val models: List<OpenRouterModelDto>,
    ) : OpenRouterModelsApi {
        override suspend fun getModels(): OpenRouterModelsResponse = OpenRouterModelsResponse(models)
    }

    private companion object {
        val networkJson = Json { encodeDefaults = true }
    }
}
