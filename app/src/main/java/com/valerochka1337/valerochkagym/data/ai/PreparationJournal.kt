package com.valerochka1337.valerochkagym.data.ai

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** One owner-scoped pointer; replacement and retained result are committed together. */
@Entity(tableName = "workout_preparations")
data class PreparationEntity(
    @PrimaryKey val owner: String,
    val requestId: String,
    val intentJson: String,
    val replacesJson: String,
    val requestJson: String? = null,
    val revision: Long? = null,
    val catalogRevision: Long? = null,
    val generation: Long? = null,
    val state: String = "WAITING",
    val errorCode: String? = null,
    val proposalJson: String? = null,
)

@Dao
interface PreparationDao {
  @Query("SELECT * FROM workout_preparations WHERE owner=:owner")
  fun observe(owner: String): Flow<PreparationEntity?>

  @Query("SELECT * FROM workout_preparations WHERE owner=:owner")
  suspend fun get(owner: String): PreparationEntity?

  @Query("SELECT generation FROM backend_state WHERE owner=:owner")
  suspend fun generation(owner: String): Long?

  @Upsert suspend fun save(row: PreparationEntity)

  @Query("SELECT generation FROM backend_state WHERE owner=:owner")
  fun observeGeneration(owner: String): Flow<Long?>
}
