package com.valerochka1337.valerochkagym.data.ai

import android.net.Uri
import com.valerochka1337.valerochkagym.di.ComputeDispatcher
import com.valerochka1337.valerochkagym.domain.health.HealthObservationDraft
import com.valerochka1337.valerochkagym.domain.health.HealthRawValue
import com.valerochka1337.valerochkagym.domain.health.HealthReportDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class HealthReportAiDraft(val report: HealthReportDraft, val sourcePages: List<Int>)
sealed interface HealthReportAiResult { data class Success(val draft: HealthReportAiDraft) : HealthReportAiResult; data class Failure(val message: String) : HealthReportAiResult }
interface HealthReportAiReader {
    suspend fun read(uri: Uri, loopbackHttpConsent: Boolean = false): HealthReportAiResult

    /** The disclosed endpoint and model are the request configuration; do not re-read settings. */
    suspend fun read(
        uri: Uri,
        configuration: AiApiRequestConfiguration,
        loopbackHttpConsent: Boolean,
    ): HealthReportAiResult = read(uri, loopbackHttpConsent)
}

/** Strict draft-only reader: parsing never has access to repositories or schedulers. */
@Singleton
class AiApiHealthReportAiReader @Inject constructor(
    private val api: AiApi,
    private val configurationProvider: AiApiConfigurationProvider,
    private val renderer: HealthDocumentRenderer,
    private val json: Json,
    @param:ComputeDispatcher private val dispatcher: CoroutineDispatcher,
) : HealthReportAiReader {
    override suspend fun read(uri: Uri, loopbackHttpConsent: Boolean): HealthReportAiResult {
        val configuration = configurationProvider.requestConfiguration()
            ?: return HealthReportAiResult.Failure("Настройте нейросеть в настройках")
        return read(uri, configuration, loopbackHttpConsent)
    }

    override suspend fun read(
        uri: Uri,
        configuration: AiApiRequestConfiguration,
        loopbackHttpConsent: Boolean,
    ): HealthReportAiResult {
        when (healthAiEndpointDecision(configuration.connection.baseUrl, loopbackHttpConsent)) {
            HealthAiEndpointDecision.Allowed -> Unit
            HealthAiEndpointDecision.LoopbackConsentRequired -> return HealthReportAiResult.Failure("Подтвердите передачу медицинского документа локальному HTTP-серверу")
            HealthAiEndpointDecision.PublicHttpRejected -> return HealthReportAiResult.Failure("Медицинские данные можно отправлять только через HTTPS")
            HealthAiEndpointDecision.Invalid -> return HealthReportAiResult.Failure("Некорректный адрес нейросети")
        }
        val pages = (renderer.render(uri) as? HealthDocumentRenderResult.Success)?.pages
            ?: return HealthReportAiResult.Failure("Не удалось подготовить документ — доступен ручной ввод")
        if (pages.isEmpty()) return HealthReportAiResult.Failure("В документе нет страниц — доступен ручной ввод")
        val request = AiApiChatRequest(configuration.modelId, listOf(
            AiApiMessage.text("system", "Верни только JSON с title, provenance, reportedAt и observations. Каждое observation содержит rawName,valueType,rawValue,observedAt,sourcePage,unit,referenceRange,method,material,source,canonicalKey."),
            AiApiMessage.textAndImages("user", "Распознай только подтверждаемые результаты исследования. Укажи sourcePage для каждого результата.", pages),
        ), AiApiResponseFormat(), 4096)
        return try {
            val response = api.createCompletion(aiApiChatCompletionsEndpoint(configuration.connection.baseUrl), "Bearer ${configuration.connection.apiKey}", request)
            withContext(dispatcher) { parse(response.choices.firstOrNull()?.message?.content as? JsonPrimitive, pages.size) }
        } catch (e: CancellationException) { throw e }
        catch (_: HttpException) { HealthReportAiResult.Failure("Сервер не принял запрос — доступен ручной ввод") }
        catch (_: Exception) { HealthReportAiResult.Failure("Ошибка распознавания — доступен ручной ввод") }
    }
    private fun parse(content: JsonPrimitive?, pageCount: Int): HealthReportAiResult {
        val root = content?.contentOrNull?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return HealthReportAiResult.Failure("Неверный ответ модели")
        val title = root.string("title") ?: return HealthReportAiResult.Failure("Неверный ответ модели")
        val provenance = root.string("provenance") ?: return HealthReportAiResult.Failure("Неверный ответ модели")
        val reportedAt = root.string("reportedAt")?.toDateMillis() ?: return HealthReportAiResult.Failure("Неверная дата отчёта")
        val observations = root["observations"] as? JsonArray ?: return HealthReportAiResult.Failure("Неверный ответ модели")
        val seen = mutableSetOf<String>(); val pages = mutableListOf<Int>()
        val parsed = observations.mapNotNull { element ->
            val item = element as? JsonObject ?: return HealthReportAiResult.Failure("Неверный результат")
            val name = item.string("rawName")?.takeIf(String::isNotBlank) ?: return HealthReportAiResult.Failure("Неверный результат")
            val observedAt = item.string("observedAt")?.toDateMillis() ?: return HealthReportAiResult.Failure("Неверная дата результата")
            val raw = item.string("rawValue") ?: return HealthReportAiResult.Failure("Неверный результат")
            val value = item.string("valueType")?.let { typedValue(it, raw) } ?: return HealthReportAiResult.Failure("Неверный тип результата")
            val page = item["sourcePage"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it in 1..pageCount } ?: return HealthReportAiResult.Failure("Некорректная страница источника")
            val duplicate = listOf(name, raw, observedAt.toString(), page.toString()).joinToString("|")
            if (!seen.add(duplicate)) return HealthReportAiResult.Failure("Повторяющийся результат")
            pages += page
            HealthObservationDraft(name, value, item.string("unit"), item.string("referenceRange"), item.string("method"), item.string("material"), item.string("source"), item.string("canonicalKey"), observedAt, page)
        }
        if (parsed.isEmpty()) return HealthReportAiResult.Failure("В документе не найдено результатов")
        return HealthReportAiResult.Success(HealthReportAiDraft(HealthReportDraft(title, provenance, reportedAt, parsed), pages))
    }
    private fun typedValue(kind: String, raw: String): HealthRawValue? = when (kind) {
        "NUMBER" -> raw.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.Number(it, raw) }
        "NUMBER_WITH_OPERATOR" -> Regex("(<=|>=|<|>)(.+)").matchEntire(raw)?.let { m -> m.groupValues[2].toDoubleOrNull()?.takeIf(Double::isFinite)?.let { HealthRawValue.NumberWithOperator(m.groupValues[1], it, raw) } }
        "RANGE" -> HealthRawValue.Range(null, null, raw).takeIf { raw.isNotBlank() }
        "CATEGORY" -> HealthRawValue.Category(raw).takeIf { raw.isNotBlank() }
        "CODE" -> HealthRawValue.Code(raw).takeIf { raw.isNotBlank() }
        "TEXT" -> HealthRawValue.Text(raw).takeIf { raw.isNotBlank() }
        else -> null
    }
    private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull
    private fun String.toDateMillis() = runCatching { LocalDate.parse(this).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
}
