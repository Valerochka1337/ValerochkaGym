package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Canonical v10 aggregate wire. Its key order is stable because it is hashed for conflicts. */
object HealthSyncPayloadCodec {
    private val json = Json { ignoreUnknownKeys = false }
    data class ReportAggregate(val report: HealthReportEntity, val observations: List<HealthObservationEntity>)

    fun report(report: HealthReportEntity, observations: List<HealthObservationEntity>): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("kind", "report")
            put("report", report.toJson())
            put("observations", buildJsonArray { observations.sortedBy { it.syncId }.forEach { add(it.toJson()) } })
        })

    fun restriction(value: HealthRestrictionEntity): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("kind", "restriction")
        put("restriction", value.toJson())
    })

    fun decodeReport(payload: String): ReportAggregate? = runCatching {
        json.parseToJsonElement(payload).jsonObject.let { root ->
            if (root["kind"]?.jsonPrimitive?.content != "report") null
            else ReportAggregate(root.getValue("report").jsonObject.toReport(), root.getValue("observations").jsonArray.map { it.jsonObject.toObservation() })
        }
    }.getOrNull()

    fun decodeRestriction(payload: String): HealthRestrictionEntity? = runCatching {
        json.parseToJsonElement(payload).jsonObject.let { root ->
            if (root["kind"]?.jsonPrimitive?.content == "restriction") root.getValue("restriction").jsonObject.toRestriction() else null
        }
    }.getOrNull()

    private fun HealthReportEntity.toJson() = buildJsonObject {
        p("id", syncId); n("v", version); n("u", updatedAt); b("d", isTombstone); p("s", status); p("p", provenance)
        n("r", reportedAt); qn("col", collectedAt); p("t", title); q("n", note); qn("x", supersedesVersion)
        qn("cx", correctionOfVersion); q("cond", conditions); b("orig", originalExpected)
    }
    private fun HealthObservationEntity.toJson() = buildJsonObject {
        p("id", syncId); p("r", reportSyncId); n("v", version); n("u", updatedAt); b("d", isTombstone); n("o", observedAt)
        p("n", rawName); p("k", valueType); p("x", rawValue); q("unit", unit); q("ref", referenceRange); q("m", method)
        q("mat", material); q("src", source); q("c", canonicalKey); qn("page", sourcePage?.toLong())
    }
    private fun HealthRestrictionEntity.toJson() = buildJsonObject {
        p("id", syncId); n("v", version); n("u", updatedAt); b("d", isTombstone); p("s", status); p("src", source)
        n("c", confirmedAt); qn("a", startsAt); qn("r", reviewAt); p("x", description)
    }

    private fun JsonObject.toReport() = HealthReportEntity(
        str("id"), long("v"), long("u"), bool("d"), str("s").legacyStatus(), str("p"), long("r"), str("t"), opt("n"), optLong("x"),
        correctionOfVersion = optLong("cx"), collectedAt = optLong("col"), conditions = opt("cond"), originalExpected = get("orig")?.jsonPrimitive?.boolean ?: false,
    )
    private fun JsonObject.toObservation() = HealthObservationEntity(
        str("id"), str("r"), long("v"), long("u"), bool("d"), long("o"), str("n"), str("k"), str("x"), opt("unit"), opt("ref"),
        opt("m"), opt("mat"), opt("src"), opt("c"), optPage("page"),
    )
    private fun JsonObject.toRestriction() = HealthRestrictionEntity(
        str("id"), long("v"), long("u"), bool("d"), str("s"), str("src"), long("c"), optLong("a"), optLong("r"), str("x"),
    )

    private fun String.legacyStatus() = if (this == "CONFIRMED") "FINAL" else this
    private fun JsonObjectBuilder.p(key: String, value: String) { put(key, JsonPrimitive(value)) }
    private fun JsonObjectBuilder.n(key: String, value: Long) { put(key, JsonPrimitive(value)) }
    private fun JsonObjectBuilder.b(key: String, value: Boolean) { put(key, JsonPrimitive(value)) }
    private fun JsonObjectBuilder.q(key: String, value: String?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonObjectBuilder.qn(key: String, value: Long?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.opt(key: String) = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.optLong(key: String) = get(key)?.jsonPrimitive?.longOrNull
    private fun JsonObject.optPage(key: String): Int? = optLong(key)?.let { require(it in 1..Int.MAX_VALUE); it.toInt() }
}
