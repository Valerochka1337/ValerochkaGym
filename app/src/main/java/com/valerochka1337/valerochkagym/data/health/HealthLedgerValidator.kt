package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.domain.HealthEditorDraft
import com.valerochka1337.valerochkagym.domain.HealthObservedPrecision
import com.valerochka1337.valerochkagym.domain.HealthOperator
import com.valerochka1337.valerochkagym.domain.HealthPayload
import com.valerochka1337.valerochkagym.domain.HealthValueKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Strictly normalizes local drafts and rejects unsafe wire values before any Room mutation. */
internal object HealthLedgerValidator {
  private val decimal = Regex("-?(?:0|[1-9]\\d{0,17})(?:\\.\\d{0,11}[1-9])?")
  private val datetime =
      Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})")

  fun normalize(draft: HealthEditorDraft, now: Long): HealthPayload? =
      when (draft) {
        is HealthEditorDraft.Report -> {
          // A report title is the one display field the wire contract canonicalizes. Originals
          // from observations remain byte-for-byte user input for auditability.
          val title = required(draft.title.trim(), 200) ?: return null
          if (!optionalValid(draft.sourceText, 10_000)) return null
          val source = optional(draft.sourceText)
          if (!observed(draft.observedAt, draft.observedPrecision)) return null
          HealthPayload.Report(title, source, draft.observedAt, draft.observedPrecision)
        }
        is HealthEditorDraft.Observation -> {
          if (draft.reportLogicalId.isBlank() || draft.metricIdentityId.isBlank()) return null
          val name = required(draft.metricNameOriginal, 200) ?: return null
          val original = required(draft.valueOriginal, 4_096) ?: return null
          if (
              !optionalValid(draft.unitOriginal, 1_000) ||
                  !optionalValid(draft.methodOriginal, 1_000) ||
                  !optionalValid(draft.specimenOriginal, 1_000) ||
                  !optionalValid(draft.sourceOriginal, 1_000) ||
                  !optionalValid(draft.referenceOriginal, 4_096)
          )
              return null
          val unit = optional(draft.unitOriginal)
          val method = optional(draft.methodOriginal)
          val specimen = optional(draft.specimenOriginal)
          val source = optional(draft.sourceOriginal)
          val reference = optional(draft.referenceOriginal)
          if (!observed(draft.observedAt, draft.observedPrecision)) return null
          if (
              !decimalValid(draft.numberValue) ||
                  !decimalValid(draft.rangeLow) ||
                  !decimalValid(draft.rangeHigh)
          )
              return null
          val number = draft.numberValue
          val low = draft.rangeLow
          val high = draft.rangeHigh
          if (low != null && high != null && BigDecimal(low) > BigDecimal(high)) return null
          when (draft.valueKind) {
            HealthValueKind.NUMBER ->
                if (number == null || low != null || high != null || draft.operator != null)
                    return null
            HealthValueKind.COMPARATOR ->
                if (number == null || draft.operator == null || low != null || high != null)
                    return null
            HealthValueKind.RANGE ->
                if (number != null || low == null || high == null || draft.operator != null)
                    return null
            HealthValueKind.CATEGORY,
            HealthValueKind.TEXT ->
                if (number != null || low != null || high != null || draft.operator != null)
                    return null
          }
          HealthPayload.Observation(
              draft.reportLogicalId,
              draft.metricIdentityId,
              name,
              draft.valueKind,
              original,
              number,
              low,
              high,
              draft.operator,
              unit,
              method,
              specimen,
              source,
              reference,
              draft.observedAt,
              draft.observedPrecision,
              now.coerceAtLeast(0),
          )
        }
        is HealthEditorDraft.Restriction ->
            required(draft.textOriginal, 5_000)?.let {
              HealthPayload.Restriction(it, now.coerceAtLeast(0))
            }
      }

  fun metricName(value: String): String? = required(value, 200)

  private fun required(value: String, limit: Int): String? =
      value.takeIf { it.isNotBlank() && it.length <= limit && it.hasValidUtf16() }

  private fun optional(value: String?): String? = value

  private fun optionalValid(value: String?, limit: Int): Boolean =
      value == null || (value.isNotEmpty() && value.length <= limit && value.hasValidUtf16())

  private fun String.hasValidUtf16(): Boolean {
    var index = 0
    while (index < length) {
      val codeUnit = this[index]
      when {
        codeUnit in '\uD800'..'\uDBFF' -> {
          if (index + 1 >= length || this[index + 1] !in '\uDC00'..'\uDFFF') return false
          index += 2
        }
        codeUnit in '\uDC00'..'\uDFFF' -> return false
        else -> index++
      }
    }
    return true
  }

  private fun decimalValid(value: String?): Boolean =
      value == null || (decimal.matches(value) && value != "-0")

  private fun observed(value: String, precision: HealthObservedPrecision): Boolean =
      value.hasValidUtf16() &&
          when (precision) {
            HealthObservedPrecision.DATE ->
                runCatching { LocalDate.parse(value) }.getOrNull()?.toString() == value
            HealthObservedPrecision.DATETIME ->
                datetime.matches(value) && runCatching { OffsetDateTime.parse(value) }.isSuccess
          }

  fun payloadToJson(payload: HealthPayload): String =
      buildJsonObject {
            when (payload) {
              is HealthPayload.Report -> {
                put("title", payload.title)
                putNullable("sourceText", payload.sourceText)
                put("observedAt", payload.observedAt)
                put("observedPrecision", payload.observedPrecision.name)
              }
              is HealthPayload.Observation -> {
                put("reportLogicalId", payload.reportLogicalId)
                put("metricIdentityId", payload.metricIdentityId)
                put("metricNameOriginal", payload.metricNameOriginal)
                put("valueKind", payload.valueKind.name)
                put("valueOriginal", payload.valueOriginal)
                putNullable("numberValue", payload.numberValue)
                putNullable("rangeLow", payload.rangeLow)
                putNullable("rangeHigh", payload.rangeHigh)
                putNullable("operator", payload.operator?.name)
                putNullable("unitOriginal", payload.unitOriginal)
                putNullable("methodOriginal", payload.methodOriginal)
                putNullable("specimenOriginal", payload.specimenOriginal)
                putNullable("sourceOriginal", payload.sourceOriginal)
                putNullable("referenceOriginal", payload.referenceOriginal)
                put("observedAt", payload.observedAt)
                put("observedPrecision", payload.observedPrecision.name)
                put("enteredAtEpochMs", payload.enteredAtEpochMs)
              }
              is HealthPayload.Restriction -> {
                put("textOriginal", payload.textOriginal)
                put("confirmedAtEpochMs", payload.confirmedAtEpochMs)
              }
            }
          }
          .toString()

  private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
      name: String,
      value: String?,
  ) {
    if (value == null) put(name, JsonNull) else put(name, value)
  }

  fun payloadFromJson(kind: String, value: JsonObject?): HealthPayload? {
    if (value == null) return null
    val required =
        when (kind) {
          "REPORT" -> setOf("title", "sourceText", "observedAt", "observedPrecision")
          "OBSERVATION" ->
              setOf(
                  "reportLogicalId",
                  "metricIdentityId",
                  "metricNameOriginal",
                  "valueKind",
                  "valueOriginal",
                  "numberValue",
                  "rangeLow",
                  "rangeHigh",
                  "operator",
                  "unitOriginal",
                  "methodOriginal",
                  "specimenOriginal",
                  "sourceOriginal",
                  "referenceOriginal",
                  "observedAt",
                  "observedPrecision",
                  "enteredAtEpochMs",
              )
          "RESTRICTION" -> setOf("textOriginal", "confirmedAtEpochMs")
          else -> return null
        }
    if (value.keys != required) return null
    fun string(name: String): String? =
        (value[name] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.hasValidUtf16() }
    fun nullable(name: String): String? =
        when (val item = value[name]) {
          JsonNull -> null
          is JsonPrimitive -> item.takeIf { it.isString }?.content?.takeIf { it.hasValidUtf16() }
          else -> null
        }
    fun nullableString(name: String): Boolean =
        value[name] is JsonNull || (value[name] as? JsonPrimitive)?.isString == true
    return when (kind) {
      "REPORT" -> {
        if (!nullableString("sourceText")) return null
        val payload =
            HealthPayload.Report(
                string("title") ?: return null,
                nullable("sourceText"),
                string("observedAt") ?: return null,
                enum<HealthObservedPrecision>(string("observedPrecision")) ?: return null,
            )
        normalize(
            HealthEditorDraft.Report(
                payload.title,
                payload.sourceText,
                payload.observedAt,
                payload.observedPrecision,
            ),
            0,
        )
            as? HealthPayload.Report
      }
      "OBSERVATION" -> {
        if (
            listOf(
                    "numberValue",
                    "rangeLow",
                    "rangeHigh",
                    "operator",
                    "unitOriginal",
                    "methodOriginal",
                    "specimenOriginal",
                    "sourceOriginal",
                    "referenceOriginal",
                )
                .any { !nullableString(it) }
        )
            return null
        val entered =
            value["enteredAtEpochMs"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull
                ?: return null
        val payload =
            HealthPayload.Observation(
                string("reportLogicalId") ?: return null,
                string("metricIdentityId") ?: return null,
                string("metricNameOriginal") ?: return null,
                enum<HealthValueKind>(string("valueKind")) ?: return null,
                string("valueOriginal") ?: return null,
                nullable("numberValue").also { if (!nullableString("numberValue")) return null },
                nullable("rangeLow").also { if (!nullableString("rangeLow")) return null },
                nullable("rangeHigh").also { if (!nullableString("rangeHigh")) return null },
                nullable("operator")?.let { enum<HealthOperator>(it) ?: return null },
                nullable("unitOriginal"),
                nullable("methodOriginal"),
                nullable("specimenOriginal"),
                nullable("sourceOriginal"),
                nullable("referenceOriginal"),
                string("observedAt") ?: return null,
                enum<HealthObservedPrecision>(string("observedPrecision")) ?: return null,
                entered,
            )
        normalize(payload.toDraft(), entered)?.let { normalized ->
          (normalized as? HealthPayload.Observation)?.takeIf { it.enteredAtEpochMs == entered }
        }
      }
      "RESTRICTION" -> {
        val confirmed =
            value["confirmedAtEpochMs"]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull
                ?: return null
        HealthPayload.Restriction(string("textOriginal") ?: return null, confirmed).takeIf {
          normalize(it.toDraft(), confirmed) != null
        }
      }
      else -> null
    }
  }

  private inline fun <reified T : Enum<T>> enum(value: String?): T? =
      value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }

  private fun HealthPayload.toDraft(): HealthEditorDraft =
      when (this) {
        is HealthPayload.Report ->
            HealthEditorDraft.Report(title, sourceText, observedAt, observedPrecision)
        is HealthPayload.Observation ->
            HealthEditorDraft.Observation(
                reportLogicalId,
                metricIdentityId,
                metricNameOriginal,
                valueKind,
                valueOriginal,
                numberValue,
                rangeLow,
                rangeHigh,
                operator,
                unitOriginal,
                methodOriginal,
                specimenOriginal,
                sourceOriginal,
                referenceOriginal,
                observedAt,
                observedPrecision,
            )
        is HealthPayload.Restriction -> HealthEditorDraft.Restriction(textOriginal)
      }
}
