package com.valerochka1337.valerochkagym.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Inserted only by v12→v13; a freshly-created v13 database deliberately has no row. */
@Entity(tableName = "muscle_load_upgrade_notice")
data class MuscleLoadUpgradeNoticeEntity(@PrimaryKey val id: Int = 1)
