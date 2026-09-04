package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Confirmed health study aggregate. Drafts deliberately never enter this table. */
@Entity(
    tableName = "health_reports",
    indices = [Index(value = ["syncId"], unique = true), Index("reportedAt"), Index("supersedesVersion"), Index("correctionOfVersion")],
)
data class HealthReportEntity(
    @PrimaryKey val syncId: String,
    val version: Long,
    val updatedAt: Long,
    val isTombstone: Boolean = false,
    val status: String,
    val provenance: String,
    val reportedAt: Long,
    val title: String,
    val note: String? = null,
    /** Previous version of this same stable aggregate; document FKs remain bound to [syncId]. */
    val supersedesVersion: Long? = null,
    /** Separate relation: status alone must never imply which revision was corrected. */
    val correctionOfVersion: Long? = null,
    val collectedAt: Long? = null,
    val conditions: String? = null,
    /** User expectation only; it must not assert that an original was stored. */
    val originalExpected: Boolean = false,
)
