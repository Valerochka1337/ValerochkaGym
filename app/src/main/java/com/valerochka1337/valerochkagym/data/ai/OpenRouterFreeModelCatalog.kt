package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** ID безопасного по умолчанию free-роутера OpenRouter для обоих ИИ-сценариев приложения. */
const val DEFAULT_OPEN_ROUTER_MODEL_ID = "openrouter/free"

/** Как OpenRouter просит модель вернуть JSON для локально валидируемого ответа. */
enum class OpenRouterJsonMode {
    /** Модель подтверждает поддержку JSON Schema, поэтому OpenRouter может принудительно применить её. */
    JSON_SCHEMA,

    /** Модель умеет вернуть JSON-объект; состав и значения дополнительно проверяет приложение. */
    JSON_OBJECT,
    ;

    companion object {
        /** Старые сохранённые конкретные модели безопаснее запускать в более совместимом режиме. */
        fun fromStored(value: String?, modelId: String): OpenRouterJsonMode =
            entries.firstOrNull { it.name == value }
                ?: if (modelId == DEFAULT_OPEN_ROUTER_MODEL_ID) JSON_SCHEMA else JSON_OBJECT
    }
}

/**
 * Модель, которую можно выбрать для ИИ-функций приложения. В список попадают только варианты,
 * которые одновременно принимают фото InBody и умеют вернуть JSON-объект.
 */
data class OpenRouterFreeModel(
    val id: String,
    val name: String,
    val contextLength: Int,
    val jsonMode: OpenRouterJsonMode,
    /** Сохранённая совместимая настройка reasoning, подобранная из публичных возможностей модели. */
    val reasoningEffort: String? = null,
    val expiresAt: String? = null,
) {
    val isAutomatic: Boolean get() = id == DEFAULT_OPEN_ROUTER_MODEL_ID

    companion object {
        val Automatic = OpenRouterFreeModel(
            id = DEFAULT_OPEN_ROUTER_MODEL_ID,
            name = "Автовыбор бесплатной модели",
            contextLength = 200_000,
            jsonMode = OpenRouterJsonMode.JSON_SCHEMA,
        )
    }
}

/** Получает подходящие free-модели из публичного каталога OpenRouter. */
interface OpenRouterFreeModelCatalog {
    suspend fun getModels(): List<OpenRouterFreeModel>
}

/** Выбор модели вместе с режимом JSON, сохранённым в момент выбора из живого каталога. */
data class OpenRouterModelSelection(
    val id: String,
    val jsonMode: OpenRouterJsonMode,
    val reasoningEffort: String? = null,
)

/** Возвращает выбор пользователя непосредственно перед ИИ-запросом. */
interface OpenRouterModelSelector {
    suspend fun selectedModel(): OpenRouterModelSelection
}

@Singleton
class SettingsOpenRouterModelSelector @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : OpenRouterModelSelector {
    override suspend fun selectedModel(): OpenRouterModelSelection = settingsRepository.settings.first().let { settings ->
        OpenRouterModelSelection(
            id = settings.openRouterModelId,
            jsonMode = settings.openRouterModelJsonMode,
            reasoningEffort = settings.openRouterModelReasoningEffort,
        )
    }
}

/**
 * Превращает сохранённую возможность модели в корректный контракт OpenRouter.
 * JSON Schema используем там, где он действительно заявлен; для остальных моделей [JSON_OBJECT]
 * достаточно, потому что оба ИИ-сценария всё равно валидируют состав и значения ответа локально.
 */
internal fun OpenRouterModelSelection.responseFormat(
    schemaName: String,
    schema: JsonObject,
): OpenRouterResponseFormat = when (jsonMode) {
    OpenRouterJsonMode.JSON_SCHEMA -> OpenRouterResponseFormat(
        type = "json_schema",
        jsonSchema = OpenRouterJsonSchema(
            name = schemaName,
            strict = true,
            schema = schema,
        ),
    )
    OpenRouterJsonMode.JSON_OBJECT -> OpenRouterResponseFormat(type = "json_object")
}

/** Настройка есть только для моделей, которые явно заявили поддержку reasoning. */
internal fun OpenRouterModelSelection.reasoningPreferences(): OpenRouterReasoningPreferences? =
    reasoningEffort?.let(::OpenRouterReasoningPreferences)

/**
 * В режиме [OpenRouterJsonMode.JSON_OBJECT] OpenRouter не передаёт JSON Schema модели сам,
 * поэтому вкладываем неизменяемую схему в инструкцию. Она остаётся статической, а ответ в обоих
 * сценариях всё равно проходит локальную валидацию.
 */
internal fun OpenRouterModelSelection.systemPrompt(
    basePrompt: String,
    schema: JsonObject,
): String = when (jsonMode) {
    OpenRouterJsonMode.JSON_SCHEMA -> basePrompt
    OpenRouterJsonMode.JSON_OBJECT -> """
        $basePrompt

        Ниже точная JSON Schema ответа. Соблюдай её имена полей, типы и обязательные поля.
        <response_schema_json>
        $schema
        </response_schema_json>
    """.trimIndent()
}

/**
 * Оставляет в каталоге только бесплатные text+vision-модели с [OpenRouterResponseFormat].
 * Автовыбор OpenRouter стоит первым как наиболее устойчивый к временному исчезновению конкретного
 * endpoint-а; далее — модели с JSON Schema, затем совместимые JSON-объекты.
 */
@Singleton
class RemoteOpenRouterFreeModelCatalog @Inject constructor(
    private val api: OpenRouterModelsApi,
) : OpenRouterFreeModelCatalog {

    override suspend fun getModels(): List<OpenRouterFreeModel> {
        val models = api.getModels().data
            .asSequence()
            .filter { it.supportsAiFeaturesForGym() }
            .map { model ->
                if (model.id == DEFAULT_OPEN_ROUTER_MODEL_ID) {
                    OpenRouterFreeModel.Automatic
                } else {
                    OpenRouterFreeModel(
                        id = model.id,
                        name = model.name.ifBlank { model.id },
                        contextLength = model.contextLength.coerceAtLeast(0),
                        jsonMode = if (model.supportedParameters.contains("structured_outputs")) {
                            OpenRouterJsonMode.JSON_SCHEMA
                        } else {
                            OpenRouterJsonMode.JSON_OBJECT
                        },
                        reasoningEffort = model.preferredReasoningEffort(),
                        expiresAt = model.expirationDate,
                    )
                }
            }
            .toList()

        return (models + OpenRouterFreeModel.Automatic)
            .distinctBy(OpenRouterFreeModel::id)
            .sortedWith(MODEL_RELEVANCE_COMPARATOR)
    }

    private fun OpenRouterModelDto.supportsAiFeaturesForGym(): Boolean =
        pricing.prompt.isZeroPrice() &&
            pricing.completion.isZeroPrice() &&
            (pricing.image == null || pricing.image.isZeroPrice()) &&
            architecture.inputModalities.contains("image") &&
            architecture.outputModalities.contains("text") &&
            supportedParameters.contains("response_format")

    /**
     * JSON-сценариям не требуется цепочка рассуждений. Используем настройку только когда модель
     * явно объявила допустимые efforts: отключаем необязательный reasoning, а у обязательного
     * берём наименее затратный уровень. У моделей без селектора сохраняем её исходное поведение.
     */
    private fun OpenRouterModelDto.preferredReasoningEffort(): String? {
        if (!supportedParameters.contains("reasoning")) return null
        val modelReasoning = reasoning ?: return null
        val supportedEfforts = modelReasoning.supportedEfforts ?: return null
        if (!modelReasoning.mandatory) return REASONING_DISABLED
        return REASONING_EFFORTS_ASCENDING.firstOrNull(supportedEfforts::contains)
    }

    private fun String?.isZeroPrice(): Boolean =
        this?.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO) == 0

    private companion object {
        const val REASONING_DISABLED = "none"
        val REASONING_EFFORTS_ASCENDING = listOf("minimal", "low", "medium", "high", "xhigh", "max")

        val MODEL_RELEVANCE_COMPARATOR: Comparator<OpenRouterFreeModel> = compareBy(
            { model -> if (model.isAutomatic) 0 else 1 },
            { model -> if (model.jsonMode == OpenRouterJsonMode.JSON_SCHEMA) 0 else 1 },
            { model -> if (model.expiresAt == null) 0 else 1 },
            { model -> -model.contextLength },
            OpenRouterFreeModel::name,
        )
    }
}
