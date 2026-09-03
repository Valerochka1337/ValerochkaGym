package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One raw laboratory or examination result; unknown names remain distinct until confirmed. */
@Entity(
    tableName = "health_observations",
    foreignKeys = [
        ForeignKey(
            entity = HealthReportEntity::class,
            parentColumns = ["syncId"],
            childColumns = ["reportSyncId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reportSyncId"), Index("canonicalKey"), Index("observedAt")],
)
data class HealthObservationEntity(
    @PrimaryKey val syncId: String,
    val reportSyncId: String,
    val version: Long,
    val updatedAt: Long,
    val isTombstone: Boolean = false,
    val observedAt: Long,
    val rawName: String,
    val valueType: String,
    val rawValue: String,
    val unit: String? = null,
    val referenceRange: String? = null,
    val method: String? = null,
    val material: String? = null,
    val source: String? = null,
    val canonicalKey: String? = null,
    /** One-based source page from the original document; null for manual or legacy entries. */
    val sourcePage: Int? = null,
)
