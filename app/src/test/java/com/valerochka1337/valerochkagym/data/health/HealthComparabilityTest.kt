package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthComparabilityTest {
  private fun observation(
      id: String,
      metric: String,
      value: String?,
      at: String,
      unit: String? = "mg",
  ) =
      HealthCurrentRecord(
          id,
          HealthRecordKind.OBSERVATION,
          1,
          id,
          1,
          false,
          null,
          HealthPayload.Observation(
              "report",
              metric,
              "Оригинальное имя",
              if (value == null) HealthValueKind.TEXT else HealthValueKind.NUMBER,
              value ?: "низкий",
              value,
              null,
              null,
              null,
              unit,
              null,
              null,
              null,
              null,
              at,
              HealthObservedPrecision.DATETIME,
              1,
          ),
      )

  @Test
  fun `trend orders RFC3339 points by instant rather than offset text`() {
    val trend =
        HealthComparability.trends(
                listOf(
                    observation("late", "m", "2", "2030-01-01T03:00:00+03:00"),
                    observation("early", "m", "1", "2030-01-01T00:30:00+03:00"),
                )
            )
            .single()
    assertEquals(listOf("early", "late"), trend.points.map { it.logicalId })
  }

  @Test
  fun `different metric identities never merge and nonnumeric metric remains explainable`() {
    val trends =
        HealthComparability.trends(
            listOf(
                observation("one", "first", "1", "2030-01-01T00:00:00Z"),
                observation("two", "second", "2", "2030-01-01T00:00:00Z"),
                observation("text", "text", null, "2030-01-01T00:00:00Z"),
            )
        )
    assertEquals(3, trends.size)
    assertTrue(
        trends.single { it.key.metricIdentityId == "text" }.incompatible.single().reason ==
            HealthTrendIncompatibilityReason.VALUE_KIND
    )
  }
}
