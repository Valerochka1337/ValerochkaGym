package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface MuscleLoadUpgradeNoticeDao {
    @Query("SELECT EXISTS(SELECT 1 FROM muscle_load_upgrade_notice WHERE id = 1)")
    suspend fun isPending(): Boolean
    @Query("DELETE FROM muscle_load_upgrade_notice WHERE id = 1")
    suspend fun acknowledge()
}
