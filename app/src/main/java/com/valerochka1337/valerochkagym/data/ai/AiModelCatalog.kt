package com.valerochka1337.valerochkagym.data.ai

import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

data class AiModel(
    val id: String,
    val ownedBy: String? = null,
)

/** Каталог моделей, доступных выписанному ключу на настроенном сервере. */
interface AiModelCatalog {
    suspend fun getModels(): List<AiModel>
}

@Singleton
class RemoteAiModelCatalog @Inject constructor(
    private val api: AiApi,
    private val configurationProvider: AiApiConfigurationProvider,
) : AiModelCatalog {

    override suspend fun getModels(): List<AiModel> {
        val connection = configurationProvider.connection()
            ?: error("Адрес и API key не настроены")
        return api.getModels(
            endpoint = aiModelsEndpoint(connection.baseUrl),
            authorization = "Bearer ${connection.apiKey}",
        ).data
            .mapNotNull { dto ->
                val id = dto.id.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                AiModel(id = id, ownedBy = dto.ownedBy?.trim()?.takeIf { it.isNotEmpty() })
            }
            .distinctBy(AiModel::id)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, AiModel::id))
    }
}

/**
 * `json_object` не навязывает upstream-у поддержку strict structured outputs, поэтому точную
 * схему кладём в неизменяемую системную инструкцию и по-прежнему валидируем ответ локально.
 */
internal fun jsonObjectSystemPrompt(
    basePrompt: String,
    schema: JsonObject,
): String = """
    $basePrompt

    Ниже точная JSON Schema ответа. Соблюдай её имена полей, типы и обязательные поля.
    <response_schema_json>
    $schema
    </response_schema_json>
""".trimIndent()
