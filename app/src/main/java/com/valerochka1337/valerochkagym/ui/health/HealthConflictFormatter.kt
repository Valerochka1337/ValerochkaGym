package com.valerochka1337.valerochkagym.ui.health

import com.valerochka1337.valerochkagym.data.db.entity.HealthSyncCategory
import com.valerochka1337.valerochkagym.data.health.HealthSyncPayloadCodec
import com.valerochka1337.valerochkagym.data.measurements.MeasurementSnapshotCodec
import com.valerochka1337.valerochkagym.ui.analysis.formatDateWithYear
import java.time.ZoneId

/** Human-readable, deliberately non-JSON rendering of a durable conflict payload. */
data class HealthConflictPayloadView(
    val title: String,
    val rows: List<String>,
)

object HealthConflictFormatter {
    fun format(category: String, payload: String, zone: ZoneId = ZoneId.systemDefault()): HealthConflictPayloadView? =
        when (category) {
            HealthSyncCategory.HEALTH_REPORTS_AND_OBSERVATIONS -> HealthSyncPayloadCodec.decodeReport(payload)?.let { aggregate ->
                val report = aggregate.report
                HealthConflictPayloadView(
                    title = if (report.isTombstone) "Исследование удалено" else report.title,
                    rows = buildList {
                        add("Статус: ${report.status}")
                        add("Дата: ${formatDateWithYear(report.reportedAt, zone)}")
                        add("Источник: ${report.provenance}")
                        if (!report.isTombstone) aggregate.observations.forEach { observation ->
                            add(buildString {
                                append(observation.rawName).append(": ").append(observation.rawValue)
                                observation.unit?.let { append(" ").append(it) }
                                observation.referenceRange?.let { append(" · реф. ").append(it) }
                                observation.source?.let { append(" · ").append(it) }
                            })
                        }
                    },
                )
            }
            HealthSyncCategory.HEALTH_RESTRICTIONS -> HealthSyncPayloadCodec.decodeRestriction(payload)?.let { restriction ->
                HealthConflictPayloadView(
                    title = if (restriction.isTombstone) "Ограничение удалено" else restriction.description,
                    rows = buildList {
                        add("Статус: ${restriction.status}")
                        add("Источник: ${restriction.source}")
                        restriction.startsAt?.let { add("Начало: ${formatDateWithYear(it, zone)}") }
                        restriction.reviewAt?.let { add("Пересмотр: ${formatDateWithYear(it, zone)}") }
                    },
                )
            }
            HealthSyncCategory.MEASUREMENTS -> MeasurementSnapshotCodec.decode(payload)?.let { decoded ->
                val measurement = decoded.measurement
                HealthConflictPayloadView(
                    title = if (decoded.isTombstone) "Замер удалён" else "Замер ${formatDateWithYear(measurement.measuredAt, zone)}",
                    rows = buildList {
                        if (decoded.isTombstone) add("Эта версия удаляет замер")
                        measurement.weightKg?.let { add("Вес: $it кг") }
                        measurement.skeletalMuscleMassKg?.let { add("Скелетная мышечная масса: $it кг") }
                        measurement.bodyFatPercentage?.let { add("Жир: $it %") }
                        measurement.waistCm?.let { add("Талия: $it см") }
                        measurement.inBodyScore?.let { add("Оценка InBody: $it") }
                        if (isEmpty()) add("Доступные поля замера не заполнены")
                    },
                )
            }
            else -> null
        }
}
