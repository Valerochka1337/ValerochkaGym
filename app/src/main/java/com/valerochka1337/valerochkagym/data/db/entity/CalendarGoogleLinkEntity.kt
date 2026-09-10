package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity

/** Local CAL-02 metadata. It is deliberately absent from portable sync data. */
@Entity(tableName = "calendar_google_links", primaryKeys = ["objectKind", "objectId"])
data class CalendarGoogleLinkEntity(
    val objectKind: String,
    val objectId: String,
    val ownerEmail: String?,
    val calendarId: String?,
    val eventId: String?,
    val status: String,
    val error: String? = null,
    val remoteRevision: Long? = null,
)
