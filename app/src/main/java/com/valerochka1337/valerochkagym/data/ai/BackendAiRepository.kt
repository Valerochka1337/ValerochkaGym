package com.valerochka1337.valerochkagym.data.ai

import android.net.Uri
import com.valerochka1337.valerochkagym.data.backend.BackendException
import com.valerochka1337.valerochkagym.data.backend.BackendResponse
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.backend.SyncReady
import com.valerochka1337.valerochkagym.data.backend.SyncReadySource
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.Muscle
import com.valerochka1337.valerochkagym.data.db.entity.MuscleLoad
import com.valerochka1337.valerochkagym.data.health.HealthAiDisclosureRepository
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Authenticated backend binding; it never sends provider settings, prompts, or local catalog data.
 */
@Singleton
class BackendAiRepository
@Inject
constructor(
    private val api: BackendTransport,
    private val syncReady: SyncReadySource,
    private val exerciseDao: ExerciseDao,
    private val photoEncoder: InBodyPhotoEncoder,
    private val disclosure: HealthAiDisclosureRepository,
) : ExerciseAiGenerator, InBodyReportAiReader {
  override suspend fun generate(description: String): ExerciseAiGenerationResult {
    val trimmed = description.trim()
    if (trimmed.codePointCount(0, trimmed.length) !in 1..MAX_DESCRIPTION_CODE_POINTS) {
      return ExerciseAiGenerationResult.Failure("Опишите упражнение")
    }
    val ready = syncReady.await().asReady() ?: return unavailable()
    if (!isUsable(ready) || !isCurrent(ready)) return unavailable()
    val requestId = UUID.randomUUID().toString()
    return try {
      val response =
          actionResponse(
              EXERCISE_PATH,
              ready,
              buildJsonObject {
                put("requestId", requestId)
                put("expectedRevision", ready.revision)
                put("expectedCatalogRevision", ready.catalogRevision)
                put("description", trimmed)
              },
          )
      if (!matchesContext(response, requestId, ready)) return invalidExercise()
      val result =
          response.body.jsonObjectOrNull()?.objectValue("result") ?: return invalidExercise()
      when (result.strictString("kind")) {
        "EXISTING" -> {
          if (result.keys != setOf("kind", "exerciseId")) return invalidExercise()
          val remoteId = result.strictString("exerciseId") ?: return invalidExercise()
          if (runCatching { UUID.fromString(remoteId) }.isFailure || !isCurrent(ready)) {
            return invalidExercise()
          }
          val mapped = exerciseDao.getAllOnce().firstOrNull { it.syncId == remoteId }
          if (!isCurrent(ready)) invalidExercise()
          else
              mapped?.let { row ->
                ExerciseAiGenerationResult.Existing(row.id) { syncReady.isCurrent(ready) }
              } ?: ExerciseAiGenerationResult.Failure("Упражнение больше не найдено")
        }
        "NEW" -> {
          val parsed = parseNewExercise(result)
          if (isCurrent(ready)) parsed else invalidExercise()
        }
        else -> invalidExercise()
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: BackendException) {
      ExerciseAiGenerationResult.Failure(actionMessage(error))
    } catch (_: Exception) {
      ExerciseAiGenerationResult.Failure("Не удалось подготовить упражнение")
    }
  }

  override suspend fun read(uri: Uri): InBodyReportAiResult {
    // Nothing reads or encodes the photo until the current receipt and personal sync are known.
    val admission = disclosure.admission() ?: return InBodyReportAiResult.Failure(CONSENT_MESSAGE)
    val ready = syncReady.await().asReady() ?: return InBodyReportAiResult.Failure(SYNC_MESSAGE)
    if (
        ready.owner != admission.owner ||
            !isUsable(ready) ||
            disclosure.admission() != admission ||
            !isCurrent(ready)
    )
        return InBodyReportAiResult.Failure(SYNC_MESSAGE)
    val image =
        (photoEncoder.encode(uri) as? InBodyPhotoEncodingResult.Success)?.jpegDataUrl
            ?: return InBodyReportAiResult.Failure("Не удалось подготовить фото")
    val base64 = image.removePrefix("data:image/jpeg;base64,")
    if (base64 == image) return InBodyReportAiResult.Failure("Не удалось подготовить фото")
    // Encoding can suspend for sizeable images. Revoke/switch must stop before bytes leave device.
    if (disclosure.admission() != admission || !isCurrent(ready)) {
      return InBodyReportAiResult.Failure(CONSENT_MESSAGE)
    }
    val requestId = UUID.randomUUID().toString()
    return try {
      val response =
          actionResponse(
              INBODY_PATH,
              ready,
              buildJsonObject {
                put("requestId", requestId)
                put("expectedRevision", ready.revision)
                put("expectedCatalogRevision", ready.catalogRevision)
                putJsonObject("image") {
                  put("mediaType", "image/jpeg")
                  put("base64", base64)
                }
              },
              headers = mapOf(DISCLOSURE_HEADER to admission.revision.toString()),
          )
      // A late revoke, account change or stale response is never applied to the editable form.
      if (
          disclosure.admission() != admission ||
              !isCurrent(ready) ||
              !matchesContext(response, requestId, ready)
      ) {
        return InBodyReportAiResult.Failure(SYNC_MESSAGE)
      }
      parseInBody(response.body.jsonObjectOrNull()?.objectValue("result") ?: return invalidInBody())
    } catch (error: CancellationException) {
      throw error
    } catch (error: BackendException) {
      InBodyReportAiResult.Failure(actionMessage(error))
    } catch (_: Exception) {
      InBodyReportAiResult.Failure("Не удалось распознать лист InBody — попробуйте ещё раз")
    }
  }

  private fun SyncReady.asReady(): SyncReady.Ready? = this as? SyncReady.Ready

  private suspend fun isCurrent(ready: SyncReady.Ready): Boolean = syncReady.isCurrent(ready)

  private fun isUsable(ready: SyncReady.Ready): Boolean =
      ready.revision >= 0 &&
          ready.catalogRevision >= 0 &&
          ready.sessionEpoch >= 0 &&
          ready.cacheGeneration >= 0

  private suspend fun actionResponse(
      path: String,
      ready: SyncReady.Ready,
      body: JsonObject,
      headers: Map<String, String> = emptyMap(),
  ): BackendResponse =
      api.authorizedRawResponse(
          method = "POST",
          path = path,
          rawBody = api.json.encodeToString(JsonObject.serializer(), body).encodeToByteArray(),
          headers = headers,
          expectedOwner = ready.owner,
          expectedSessionEpoch = ready.sessionEpoch,
          retryOnUnauthorized = false,
      )

  private fun matchesContext(
      response: BackendResponse,
      requestId: String,
      ready: SyncReady.Ready,
  ): Boolean =
      response.owner == ready.owner &&
          response.sessionEpoch == ready.sessionEpoch &&
          response.body.jsonObjectOrNull()?.let { body ->
            body.keys == setOf("requestId", "context", "result") &&
                body.strictString("requestId") == requestId &&
                body.objectValue("context")?.let { context ->
                  context.keys == setOf("revision", "catalogRevision") &&
                      context.strictLong("revision") == ready.revision &&
                      context.strictLong("catalogRevision") == ready.catalogRevision
                } == true
          } == true

  private fun parseNewExercise(result: JsonObject): ExerciseAiGenerationResult {
    if (result.keys != setOf("kind", "name", "type", "muscles")) return invalidExercise()
    val name = result.strictString("name")?.trim().orEmpty()
    val type =
        result.strictString("type")?.let { value ->
          ExerciseType.entries.firstOrNull { it.name == value }
        }
    val rows =
        result["muscles"] as? kotlinx.serialization.json.JsonArray ?: return invalidExercise()
    val loads =
        rows.mapNotNull { row ->
          val objectValue = row as? JsonObject ?: return@mapNotNull null
          if (objectValue.keys != setOf("muscle", "contribution")) return@mapNotNull null
          val muscle =
              objectValue.strictString("muscle")?.let {
                Muscle.entries.firstOrNull { item -> item.name == it }
              }
          val contribution =
              objectValue.strictLong("contribution")?.takeIf { it in 0..100 }?.toInt()
          if (muscle == null || contribution == null || contribution !in setOf(0, 50, 100)) null
          else MuscleLoad(muscle, contribution)
        }
    return if (
        name.codePointCount(0, name.length) !in 1..200 ||
            type == null ||
            loads.size != rows.size ||
            loads.map(MuscleLoad::muscle).distinct().size != loads.size ||
            loads.none { it.contribution > 0 }
    )
        invalidExercise()
    else ExerciseAiGenerationResult.New(name, type, loads)
  }

  private fun parseInBody(result: JsonObject): InBodyReportAiResult {
    if (result.keys != setOf("kind", "draft") || result.strictString("kind") != "INBODY") {
      return invalidInBody()
    }
    val draft = result.objectValue("draft") ?: return invalidInBody()
    if (draft.keys != INBODY_DRAFT_KEYS) return invalidInBody()
    val segments = draft.objectValue("segments") ?: return invalidInBody()
    if (segments.keys != InBodySegment.entries.mapTo(linkedSetOf()) { it.name })
        return invalidInBody()
    if (
        !draft.isNullableString("measuredDate") ||
            !draft.isNullableString("measuredTime") ||
            INBODY_DOUBLE_KEYS.any { !draft.isNullableDouble(it) } ||
            INBODY_INT_KEYS.any { !draft.isNullableInt(it) } ||
            draft.nullableDoubleValue("bodyFatPercentage")?.let { it > 100.0 } == true
    )
        return invalidInBody()
    val mapped =
        InBodySegment.entries.associateWith { segment ->
          val value = segments.objectValue(segment.name) ?: return invalidInBody()
          if (value.keys != SEGMENT_KEYS || SEGMENT_KEYS.any { !value.isNullableDouble(it) }) {
            return invalidInBody()
          }
          InBodySegmentValues(
              leanMassKg = value.nullableDoubleValue("leanMassKg"),
              leanPercentage = value.nullableDoubleValue("leanPercentage"),
              fatMassKg = value.nullableDoubleValue("fatMassKg"),
              fatPercentage = value.nullableDoubleValue("fatPercentage"),
          )
        }
    val measuredDate = draft.nullableStringValue("measuredDate")
    val measuredTime = draft.nullableStringValue("measuredTime")
    val parsedDate = measuredDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val parsedTime =
        measuredTime
            ?.takeIf { TIME_PATTERN.matches(it) }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    if (
        (measuredDate != null && parsedDate == null) || (measuredTime != null && parsedTime == null)
    ) {
      return invalidInBody()
    }
    return InBodyReportAiResult.Success(
        InBodyReportDraft(
            measuredDate = parsedDate,
            measuredTime = parsedTime,
            weightKg = draft.nullableDoubleValue("weightKg"),
            skeletalMuscleMassKg = draft.nullableDoubleValue("skeletalMuscleMassKg"),
            bodyFatPercentage = draft.nullableDoubleValue("bodyFatPercentage"),
            bodyFatMassKg = draft.nullableDoubleValue("bodyFatMassKg"),
            visceralFatLevel = draft.nullableIntValue("visceralFatLevel"),
            waistHipRatio = draft.nullableDoubleValue("waistHipRatio"),
            inBodyScore = draft.nullableIntValue("inBodyScore"),
            totalBodyWaterLiters = draft.nullableDoubleValue("totalBodyWaterLiters"),
            proteinKg = draft.nullableDoubleValue("proteinKg"),
            mineralsKg = draft.nullableDoubleValue("mineralsKg"),
            bodyMassIndex = draft.nullableDoubleValue("bodyMassIndex"),
            fatFreeMassKg = draft.nullableDoubleValue("fatFreeMassKg"),
            basalMetabolicRateKcal = draft.nullableIntValue("basalMetabolicRateKcal"),
            recommendedCalorieIntakeKcal = draft.nullableIntValue("recommendedCalorieIntakeKcal"),
            segments = mapped,
        ),
    )
  }

  private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

  private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

  private fun JsonObject.strictString(name: String): String? =
      (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

  private fun JsonObject.strictLong(name: String): Long? =
      (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

  private fun JsonObject.strictDouble(name: String): Double? =
      (this[name] as? JsonPrimitive)
          ?.takeIf { !it.isString && it !is JsonNull }
          ?.doubleOrNull
          ?.takeIf { it >= 0.0 && it.isFinite() }

  /** `null` is valid for every draft field, but absence and coercion are invalid. */
  private fun JsonObject.isNullableString(name: String): Boolean =
      this[name] is JsonNull || strictString(name) != null

  private fun JsonObject.isNullableDouble(name: String): Boolean =
      this[name] is JsonNull || strictDouble(name) != null

  private fun JsonObject.isNullableInt(name: String): Boolean =
      this[name] is JsonNull || strictLong(name)?.let { it in 0..Int.MAX_VALUE.toLong() } == true

  private fun JsonObject.nullableStringValue(name: String): String? = strictString(name)

  private fun JsonObject.nullableDoubleValue(name: String): Double? = strictDouble(name)

  private fun JsonObject.nullableIntValue(name: String): Int? = strictLong(name)?.toInt()

  private fun unavailable(): ExerciseAiGenerationResult.Failure =
      ExerciseAiGenerationResult.Failure(SYNC_MESSAGE)

  private fun invalidExercise(): ExerciseAiGenerationResult.Failure =
      ExerciseAiGenerationResult.Failure("Сервер вернул некорректный черновик")

  private fun invalidInBody(): InBodyReportAiResult.Failure =
      InBodyReportAiResult.Failure("Сервер вернул некорректный черновик")

  private fun actionMessage(error: BackendException): String =
      when (error.code) {
        "ai_context_stale" -> SYNC_MESSAGE
        "ai_unavailable",
        "ai_busy" -> "Нейросеть временно недоступна"
        else -> "Не удалось подготовить черновик"
      }

  private companion object {
    const val MAX_DESCRIPTION_CODE_POINTS = 2_000
    const val EXERCISE_PATH = "/ai/exercise-drafts"
    const val INBODY_PATH = "/ai/inbody-drafts"
    const val DISCLOSURE_HEADER = "X-Health-AI-Disclosure-Revision"
    const val CONSENT_MESSAGE = "Разрешите обработку фото InBody перед отправкой"
    const val SYNC_MESSAGE = "Сначала завершите синхронизацию"
    val TIME_PATTERN = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    val SEGMENT_KEYS = setOf("leanMassKg", "leanPercentage", "fatMassKg", "fatPercentage")
    val INBODY_DOUBLE_KEYS =
        setOf(
            "weightKg",
            "skeletalMuscleMassKg",
            "bodyFatPercentage",
            "bodyFatMassKg",
            "waistHipRatio",
            "totalBodyWaterLiters",
            "proteinKg",
            "mineralsKg",
            "bodyMassIndex",
            "fatFreeMassKg",
        )
    val INBODY_INT_KEYS =
        setOf(
            "visceralFatLevel",
            "inBodyScore",
            "basalMetabolicRateKcal",
            "recommendedCalorieIntakeKcal",
        )
    val INBODY_DRAFT_KEYS =
        INBODY_DOUBLE_KEYS + INBODY_INT_KEYS + setOf("measuredDate", "measuredTime", "segments")
  }
}
