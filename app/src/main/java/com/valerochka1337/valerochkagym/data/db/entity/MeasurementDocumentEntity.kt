package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only metadata for a measurement original. Its bytes never enter Sheets sync. */
@Entity(
    tableName = "measurement_documents",
    foreignKeys = [
        ForeignKey(
            entity = BodyMeasurementEntity::class,
            parentColumns = ["id"],
            childColumns = ["measurementId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("measurementId"), Index("sha256"), Index("state")],
)
data class MeasurementDocumentEntity(
    @PrimaryKey val id: String,
    val measurementId: String,
    val sha256: String,
    /** PENDING or READY; file lifecycle is deliberately implemented in the next slice. */
    val state: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val createdAt: Long,
)
