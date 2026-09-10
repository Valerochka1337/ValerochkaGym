package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Pure display grouping: it deliberately performs no unit conversion or health interpretation. */
internal object HealthComparability {
  fun trends(records: List<HealthCurrentRecord>): List<HealthTrend> {
    val observations =
        records
            .filter { !it.deleted }
            .mapNotNull { record ->
              (record.payload as? HealthPayload.Observation)?.let { record to it }
            }
    val numeric =
        observations.filter { (_, value) ->
          value.valueKind == HealthValueKind.NUMBER && value.numberValue != null
        }
    val numericGroups =
        numeric.groupBy { (_, value) ->
          HealthTrendKey(
              value.metricIdentityId,
              value.unitOriginal,
              value.methodOriginal,
              value.specimenOriginal,
          )
        }
    val trends =
        numericGroups.map { (key, values) -> trend(key, values, observations) }.toMutableList()
    observations
        .groupBy { it.second.metricIdentityId }
        .forEach { (metric, values) ->
          if (
              values.none {
                it.second.valueKind == HealthValueKind.NUMBER && it.second.numberValue != null
              }
          ) {
            val first = values.first().second
            trends +=
                HealthTrend(
                    HealthTrendKey(
                        metric,
                        first.unitOriginal,
                        first.methodOriginal,
                        first.specimenOriginal,
                    ),
                    emptyList(),
                    values.map { (record, value) ->
                      HealthTrendIncompatibility(
                          record.logicalId,
                          HealthTrendIncompatibilityReason.VALUE_KIND,
                          value.valueOriginal,
                      )
                    },
                )
          }
        }
    return trends.sortedBy { it.key.metricIdentityId }
  }

  private fun trend(
      key: HealthTrendKey,
      values: List<Pair<HealthCurrentRecord, HealthPayload.Observation>>,
      all: List<Pair<HealthCurrentRecord, HealthPayload.Observation>>,
  ): HealthTrend {
    val points =
        values
            .sortedWith(
                compareBy(
                    { observedOrder(it.second.observedAt, it.second.observedPrecision) },
                    { it.first.logicalId },
                )
            )
            .map { (record, value) ->
              HealthNumericPoint(
                  record.logicalId,
                  value.observedAt,
                  requireNotNull(value.numberValue),
              )
            }
    val incompatible =
        all.filter { (_, value) ->
              value.metricIdentityId == key.metricIdentityId &&
                  (value.valueKind != HealthValueKind.NUMBER ||
                      value.numberValue == null ||
                      value.unitOriginal != key.unitOriginal ||
                      value.methodOriginal != key.methodOriginal ||
                      value.specimenOriginal != key.specimenOriginal)
            }
            .map { (record, value) ->
              HealthTrendIncompatibility(record.logicalId, reason(key, value), value.valueOriginal)
            }
    return HealthTrend(key, points, incompatible)
  }

  private fun observedOrder(value: String, precision: HealthObservedPrecision): Instant =
      when (precision) {
        HealthObservedPrecision.DATE ->
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
        HealthObservedPrecision.DATETIME -> OffsetDateTime.parse(value).toInstant()
      }

  private fun reason(
      key: HealthTrendKey,
      value: HealthPayload.Observation,
  ): HealthTrendIncompatibilityReason =
      when {
        value.valueKind != HealthValueKind.NUMBER || value.numberValue == null ->
            HealthTrendIncompatibilityReason.VALUE_KIND
        value.unitOriginal != key.unitOriginal -> HealthTrendIncompatibilityReason.UNIT
        value.methodOriginal != key.methodOriginal -> HealthTrendIncompatibilityReason.METHOD
        value.specimenOriginal != key.specimenOriginal -> HealthTrendIncompatibilityReason.SPECIMEN
        else -> HealthTrendIncompatibilityReason.METRIC
      }
}
