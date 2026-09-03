package com.valerochka1337.valerochkagym.domain

import java.util.UUID

/** Route/UI representation of an execution group; Room and Sheets keep nullable UUIDs. */
object ExecutionGroupToken {
    const val NONE = "none"

    fun encode(variantSyncId: String?): String = variantSyncId?.canonicalUuidOrNull() ?: NONE

    /** Invalid or missing legacy route input is deliberately the explicit no-variant group. */
    fun decode(token: String?): String? = token?.canonicalUuidOrNull()

    private fun String.canonicalUuidOrNull(): String? = runCatching {
        UUID.fromString(this).toString()
    }.getOrNull()?.takeIf { it.equals(this, ignoreCase = true) }
}
