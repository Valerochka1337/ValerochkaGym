package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEntity
import com.valerochka1337.valerochkagym.data.db.entity.ProfileEquipmentPreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
  @Query("SELECT * FROM profiles WHERE scope=:scope")
  fun observe(scope: String): Flow<ProfileEntity?>

  @Query("SELECT * FROM profiles WHERE scope=:scope") suspend fun get(scope: String): ProfileEntity?

  @Query("SELECT equipmentId FROM profile_equipment WHERE scope=:scope ORDER BY equipmentId")
  fun observeEquipmentIds(scope: String): Flow<List<String>>

  @Query("SELECT equipmentId FROM profile_equipment WHERE scope=:scope ORDER BY equipmentId")
  suspend fun equipmentIds(scope: String): List<String>

  @Upsert suspend fun upsert(profile: ProfileEntity)

  @Query("DELETE FROM profile_equipment WHERE scope=:scope")
  suspend fun deleteEquipment(scope: String)

  @Upsert suspend fun upsertEquipment(items: List<ProfileEquipmentPreferenceEntity>)

  @Query("DELETE FROM profiles WHERE scope=:scope") suspend fun delete(scope: String)
}
