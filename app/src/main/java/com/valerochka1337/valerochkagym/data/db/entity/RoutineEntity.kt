package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Шаблон программы. [syncId] не зависит от локального auto-increment ID и поэтому связывает
 * снимки программы между установками приложения. [updatedAt] — монотонная версия снимка в
 * Google Sheets: при восстановлении побеждает программа с большим значением.
 */
@Entity(
    tableName = "routines",
    indices = [Index(value = ["syncId"], unique = true)],
)
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val name: String,
    val note: String = "",
)

/** Возвращает следующую версию программы, даже если два сохранения попали в одну миллисекунду. */
fun RoutineEntity.withNextUpdatedAt(now: Long = System.currentTimeMillis()): RoutineEntity =
    copy(updatedAt = maxOf(now, updatedAt + 1))
