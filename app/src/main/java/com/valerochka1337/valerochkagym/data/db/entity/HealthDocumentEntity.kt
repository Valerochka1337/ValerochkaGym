package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only original metadata. The bytes and hashes are never put in Sheets payloads. */
@Entity(
    tableName = "health_documents",
    foreignKeys = [
        ForeignKey(
            entity = HealthReportEntity::class,
            parentColumns = ["syncId"],
            childColumns = ["reportSyncId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reportSyncId"), Index("sha256"), Index("state")],
)
data class HealthDocumentEntity(
    @PrimaryKey val id: String,
    val reportSyncId: String,
    val sha256: String,
    val state: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val sourcePage: Int? = null,
    val createdAt: Long,
)
