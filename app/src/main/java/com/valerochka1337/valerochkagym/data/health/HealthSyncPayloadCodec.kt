package com.valerochka1337.valerochkagym.data.health

import com.valerochka1337.valerochkagym.data.db.entity.HealthObservationEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthReportEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthRestrictionEntity
import kotlinx.serialization.json.*

/** Immutable outbox payload; deliberately contains only syncable primary wire state. */
object HealthSyncPayloadCodec {
    private val json = Json { ignoreUnknownKeys = false }
    data class ReportAggregate(val report: HealthReportEntity, val observations: List<HealthObservationEntity>)
    fun report(report: HealthReportEntity, observations: List<HealthObservationEntity>): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("kind", "report"); put("report", report.toJson()); put("observations", buildJsonArray { observations.sortedBy { it.syncId }.forEach { add(it.toJson()) } })
    })
    fun restriction(value: HealthRestrictionEntity): String = json.encodeToString(JsonObject.serializer(), buildJsonObject { put("kind", "restriction"); put("restriction", value.toJson()) })
    fun decodeReport(payload: String): ReportAggregate? = runCatching { json.parseToJsonElement(payload).jsonObject.let { root ->
        if (root["kind"]?.jsonPrimitive?.content != "report") null else ReportAggregate(root["report"]!!.jsonObject.toReport(), root["observations"]!!.jsonArray.map { it.jsonObject.toObservation() })
    } }.getOrNull()
    fun decodeRestriction(payload: String): HealthRestrictionEntity? = runCatching { json.parseToJsonElement(payload).jsonObject.let { if (it["kind"]?.jsonPrimitive?.content == "restriction") it["restriction"]!!.jsonObject.toRestriction() else null } }.getOrNull()
    private fun HealthReportEntity.toJson() = buildJsonObject { p("id",syncId); n("v",version); n("u",updatedAt); b("d",isTombstone); p("s",status); p("p",provenance); n("r",reportedAt); p("t",title); q("n",note); qn("x",supersedesVersion) }
    private fun HealthObservationEntity.toJson() = buildJsonObject { p("id",syncId); p("r",reportSyncId); n("v",version); n("u",updatedAt); b("d",isTombstone); n("o",observedAt); p("n",rawName); p("k",valueType); p("x",rawValue); q("unit",unit); q("ref",referenceRange); q("m",method); q("mat",material); q("src",source); q("c",canonicalKey); qn("page",sourcePage?.toLong()) }
    private fun HealthRestrictionEntity.toJson() = buildJsonObject { p("id",syncId); n("v",version); n("u",updatedAt); b("d",isTombstone); p("s",status); p("src",source); n("c",confirmedAt); qn("a",startsAt); qn("r",reviewAt); p("x",description) }
    private fun JsonObject.toReport()=HealthReportEntity(str("id"),long("v"),long("u"),bool("d"),str("s"),str("p"),long("r"),str("t"),opt("n"),optLong("x"))
    private fun JsonObject.toObservation()=HealthObservationEntity(str("id"),str("r"),long("v"),long("u"),bool("d"),long("o"),str("n"),str("k"),str("x"),opt("unit"),opt("ref"),opt("m"),opt("mat"),opt("src"),opt("c"),optPage("page"))
    private fun JsonObject.toRestriction()=HealthRestrictionEntity(str("id"),long("v"),long("u"),bool("d"),str("s"),str("src"),long("c"),optLong("a"),optLong("r"),str("x"))
    private fun JsonObjectBuilder.p(k:String,v:String){put(k,JsonPrimitive(v))}; private fun JsonObjectBuilder.n(k:String,v:Long){put(k,JsonPrimitive(v))}; private fun JsonObjectBuilder.b(k:String,v:Boolean){put(k,JsonPrimitive(v))}; private fun JsonObjectBuilder.q(k:String,v:String?){put(k,v?.let(::JsonPrimitive)?:JsonNull)}; private fun JsonObjectBuilder.qn(k:String,v:Long?){put(k,v?.let(::JsonPrimitive)?:JsonNull)}
    private fun JsonObject.str(k:String)=getValue(k).jsonPrimitive.content; private fun JsonObject.long(k:String)=getValue(k).jsonPrimitive.long; private fun JsonObject.bool(k:String)=getValue(k).jsonPrimitive.boolean; private fun JsonObject.opt(k:String)=get(k)?.jsonPrimitive?.contentOrNull; private fun JsonObject.optLong(k:String)=get(k)?.jsonPrimitive?.longOrNull
    private fun JsonObject.optPage(k: String): Int? = optLong(k)?.let { value ->
        require(value in 1..Int.MAX_VALUE) { "Invalid source page" }
        value.toInt()
    }
}
