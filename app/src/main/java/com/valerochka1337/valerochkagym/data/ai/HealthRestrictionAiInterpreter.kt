package com.valerochka1337.valerochkagym.data.ai

import com.valerochka1337.valerochkagym.domain.health.HealthInformationSource
import com.valerochka1337.valerochkagym.domain.health.HealthRestrictionState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class RestrictionProposal(val limitedActivity: String, val source: HealthInformationSource, val startsAt: Long?, val reviewAt: Long?, val state: HealthRestrictionState, val clarification: String? = null)
sealed interface RestrictionInterpretation { data class Success(val proposals: List<RestrictionProposal>) : RestrictionInterpretation; data class Failure(val message: String) : RestrictionInterpretation }
interface HealthRestrictionAiInterpreter { suspend fun interpret(originalText: String, loopbackConsent: Boolean = false): RestrictionInterpretation }

@Singleton class AiHealthRestrictionAiInterpreter @Inject constructor(private val api: AiApi, private val config: AiApiConfigurationProvider, private val json: Json) : HealthRestrictionAiInterpreter {
    override suspend fun interpret(originalText: String, loopbackConsent: Boolean): RestrictionInterpretation {
        if (originalText.isBlank()) return RestrictionInterpretation.Failure("Введите исходную формулировку")
        val c=config.requestConfiguration() ?: return RestrictionInterpretation.Failure("Настройте нейросеть в настройках")
        when(healthAiEndpointDecision(c.connection.baseUrl, loopbackConsent)) { HealthAiEndpointDecision.Allowed -> Unit; HealthAiEndpointDecision.LoopbackConsentRequired -> return RestrictionInterpretation.Failure("Подтвердите локальную отправку"); HealthAiEndpointDecision.PublicHttpRejected -> return RestrictionInterpretation.Failure("Ограничения можно отправлять только через HTTPS"); HealthAiEndpointDecision.Invalid -> return RestrictionInterpretation.Failure("Некорректный адрес") }
        return try { parse((api.createCompletion(aiApiChatCompletionsEndpoint(c.connection.baseUrl), "Bearer ${c.connection.apiKey}", AiApiChatRequest(c.modelId,listOf(AiApiMessage.text("system","Верни JSON proposals: limitedActivity,source,startsAt,reviewAt,status,clarification. Не ставь LIFTED."),AiApiMessage.text("user",originalText)),AiApiResponseFormat(),1024)).choices.firstOrNull()?.message?.content as? JsonPrimitive)?.contentOrNull) } catch (error: CancellationException) { throw error } catch (_: Exception) { RestrictionInterpretation.Failure("Не удалось разобрать текст — заполните вручную") }
    }
    private fun parse(raw:String?):RestrictionInterpretation { val rows=raw?.let{runCatching{json.parseToJsonElement(it) as? JsonObject}.getOrNull()}?.get("proposals") as? JsonArray ?: return RestrictionInterpretation.Failure("Неверный ответ") ; val result=rows.mapNotNull { e -> val o=e as? JsonObject?:return RestrictionInterpretation.Failure("Неверный ответ"); val text=(o["limitedActivity"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?:return RestrictionInterpretation.Failure("Неверный ответ"); val source=runCatching{HealthInformationSource.valueOf((o["source"] as? JsonPrimitive)?.contentOrNull?:"USER")}.getOrElse{return RestrictionInterpretation.Failure("Неверный источник")}; RestrictionProposal(text,source,null,null,HealthRestrictionState.ACTIVE,(o["clarification"] as? JsonPrimitive)?.contentOrNull) }; return if(result.isEmpty()) RestrictionInterpretation.Failure("Нет предложений") else RestrictionInterpretation.Success(result) }
}
