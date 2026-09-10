package com.valerochka1337.valerochkagym.data.ai

import android.net.Uri
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegment
import com.valerochka1337.valerochkagym.domain.measurements.InBodySegmentValues
import java.time.LocalDate
import java.time.LocalTime

/** Parsed, still editable values from one InBody report. No reader persists a measurement. */
data class InBodyReportDraft(
    val measuredDate: LocalDate? = null,
    val measuredTime: LocalTime? = null,
    val weightKg: Double? = null,
    val skeletalMuscleMassKg: Double? = null,
    val bodyFatPercentage: Double? = null,
    val bodyFatMassKg: Double? = null,
    val visceralFatLevel: Int? = null,
    val waistHipRatio: Double? = null,
    val inBodyScore: Int? = null,
    val totalBodyWaterLiters: Double? = null,
    val proteinKg: Double? = null,
    val mineralsKg: Double? = null,
    val bodyMassIndex: Double? = null,
    val fatFreeMassKg: Double? = null,
    val basalMetabolicRateKcal: Int? = null,
    val recommendedCalorieIntakeKcal: Int? = null,
    val segments: Map<InBodySegment, InBodySegmentValues> = emptyMap(),
)

sealed interface InBodyReportAiResult {
  data class Success(val draft: InBodyReportDraft) : InBodyReportAiResult

  data class Failure(val message: String) : InBodyReportAiResult
}

interface InBodyReportAiReader {
  suspend fun read(uri: Uri): InBodyReportAiResult
}
