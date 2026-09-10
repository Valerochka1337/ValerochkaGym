package com.valerochka1337.valerochkagym.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentIntentEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentOutboxEntity
import com.valerochka1337.valerochkagym.data.db.entity.HealthAiConsentStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthAiConsentDao {
  @Query("SELECT * FROM health_ai_consent_state WHERE owner=:owner")
  suspend fun state(owner: String): HealthAiConsentStateEntity?

  @Query("SELECT * FROM health_ai_consent_state WHERE owner=:owner")
  fun observeState(owner: String): Flow<HealthAiConsentStateEntity?>

  @Query("SELECT * FROM health_ai_consent_outbox WHERE owner=:owner")
  suspend fun outbox(owner: String): HealthAiConsentOutboxEntity?

  @Query("SELECT * FROM health_ai_consent_intent WHERE owner=:owner")
  suspend fun intent(owner: String): HealthAiConsentIntentEntity?

  @Upsert suspend fun upsertState(state: HealthAiConsentStateEntity)

  @Upsert suspend fun upsertIntent(intent: HealthAiConsentIntentEntity)

  @Query("DELETE FROM health_ai_consent_intent WHERE owner=:owner")
  suspend fun deleteIntent(owner: String)

  /** Caller keeps this alongside outbox acknowledgement in the same Room transaction. */
  @Query("DELETE FROM health_ai_consent_intent WHERE owner=:owner AND enabled=:enabled")
  suspend fun deleteIntentIfEnabled(owner: String, enabled: Boolean): Int

  @Upsert suspend fun upsertOutbox(outbox: HealthAiConsentOutboxEntity)

  @Query("UPDATE health_ai_consent_outbox SET dispatched=1 WHERE owner=:owner")
  suspend fun markDispatched(owner: String)

  @Query("DELETE FROM health_ai_consent_outbox WHERE owner=:owner AND operationId=:operationId")
  suspend fun deleteOutbox(owner: String, operationId: String)
}
