package com.valerochka1337.valerochkagym.data.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class BackendTokens(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int = 900,
)

@Serializable
data class CloudRecord(
    val kind: String,
    val id: String,
    val revision: Long,
    val deleted: Boolean = false,
    val payload: JsonObject? = null,
) {
  val key: String
    get() = "$kind:$id"
}

@Serializable data class CloudSnapshot(val revision: Long, val records: List<CloudRecord>)

@Serializable
data class CloudChange(
    val kind: String,
    val id: String,
    val baseRevision: Long,
    val deleted: Boolean = false,
    val payload: JsonObject? = null,
)

@Serializable data class CloudPush(val operationId: String, val changes: List<CloudChange>)

@Serializable data class CloudAck(val revision: Long)

@Serializable
data class BackendSession(
    val id: String,
    val deviceName: String,
    val createdAt: String,
    val current: Boolean,
)

class BackendException(val status: Int, val code: String, override val message: String) :
    Exception(message)

/** Three-way comparison preserves unrelated remote changes and refuses silent overwrites. */
object CloudMerge {
  fun conflicts(
      local: Map<String, JsonObject>,
      baseline: Map<String, CloudRecord>,
      remote: List<CloudRecord>,
  ): Set<String> =
      remote
          .filter { r ->
            val old = baseline[r.key]
            val localPayload = local[r.key]
            val previous = old?.payload
            val cloud = r.payload
            old?.revision != r.revision &&
                localPayload != previous &&
                localPayload != cloud &&
                // First login: installed built-ins defer to the existing account catalogue.
                !(old == null &&
                    r.kind == "exercise" &&
                    localPayload?.get("isCustom")?.toString() == "false")
          }
          .map { it.key }
          .toSet()
}
