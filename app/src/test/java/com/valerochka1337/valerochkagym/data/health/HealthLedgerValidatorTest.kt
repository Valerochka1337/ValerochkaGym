package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.domain.HealthEditorDraft
import com.valerochka1337.valerochkagym.domain.HealthObservedPrecision
import com.valerochka1337.valerochkagym.domain.HealthPayload
import com.valerochka1337.valerochkagym.domain.HealthValueKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HealthLedgerValidatorTest {
  private fun number(value: String, observedAt: String = "2030-01-01T03:00:00.5+03:00") =
      HealthEditorDraft.Observation(
          "report",
          "metric",
          "Глюкоза",
          HealthValueKind.NUMBER,
          value,
          value,
          null,
          null,
          null,
          "ммоль/л",
          null,
          null,
          null,
          null,
          observedAt,
          HealthObservedPrecision.DATETIME,
      )

  @Test
  fun `canonical signed decimal boundaries preserve original values`() {
    listOf("0", "10", "-20", "-2.5", "0.000000000001", "123456789012345678").forEach {
      assertNotNull(HealthLedgerValidator.normalize(number(it), 7))
    }
    listOf("-0", "1.0", "01", "1e3", "1234567890123456789", "0.1234567890123").forEach {
      assertNull(HealthLedgerValidator.normalize(number(it), 7))
    }
  }

  @Test
  fun `datetime preserves fractional seconds and original offset`() {
    val payload = requireNotNull(HealthLedgerValidator.normalize(number("10"), 7))
    assertEquals(
        "2030-01-01T03:00:00.5+03:00",
        (payload as com.valerochka1337.valerochkagym.domain.HealthPayload.Observation).observedAt,
    )
  }

  @Test
  fun `wire payload rejects unknown and mistyped fields`() {
    val payload =
        HealthLedgerValidator.payloadToJson(
            requireNotNull(HealthLedgerValidator.normalize(number("10"), 7))
        )
    val objectValue = Json.parseToJsonElement(payload).jsonObject
    assertNotNull(HealthLedgerValidator.payloadFromJson("OBSERVATION", objectValue))
    assertNull(
        HealthLedgerValidator.payloadFromJson(
            "OBSERVATION",
            kotlinx.serialization.json.JsonObject(
                objectValue + ("extra" to kotlinx.serialization.json.JsonPrimitive(true))
            ),
        )
    )
    assertNull(
        HealthLedgerValidator.payloadFromJson(
            "OBSERVATION",
            kotlinx.serialization.json.JsonObject(
                objectValue + ("numberValue" to kotlinx.serialization.json.JsonPrimitive(10))
            ),
        )
    )
  }

  @Test
  fun `report trims only title and rejects empty optional or malformed utf16 text`() {
    val report =
        HealthEditorDraft.Report(
            title = "  Биохимия  ",
            sourceText = "лаборатория",
            observedAt = "2030-01-01",
            observedPrecision = HealthObservedPrecision.DATE,
        )
    assertEquals(
        "Биохимия",
        (HealthLedgerValidator.normalize(report, 0) as HealthPayload.Report).title,
    )
    assertNull(HealthLedgerValidator.normalize(report.copy(sourceText = ""), 0))
    assertNull(HealthLedgerValidator.normalize(report.copy(sourceText = "\uD800"), 0))
  }
}
