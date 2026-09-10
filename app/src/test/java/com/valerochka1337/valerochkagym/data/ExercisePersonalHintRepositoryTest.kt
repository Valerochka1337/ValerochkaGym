package com.valerochka1337.valerochkagym.data

import com.valerochka1337.valerochkagym.data.backend.BackendSessionStore
import com.valerochka1337.valerochkagym.data.backend.BackendSync
import com.valerochka1337.valerochkagym.data.backend.BackendTokens
import com.valerochka1337.valerochkagym.data.backend.BackendTransport
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.NoteSaveResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ExercisePersonalHintRepositoryTest : RoomDaoTest() {
  @Test
  fun `hint save and unpin leave a standard exercise unchanged`() = runTest {
    val id =
        db.exerciseDao()
            .insert(
                ExerciseEntity(
                    name = "Жим",
                    muscleGroup = MuscleGroup.CHEST,
                    type = ExerciseType.STRENGTH,
                    syncId = "standard-exercise",
                    origin = "STANDARD",
                ),
            )
    val store = Store()
    val repository =
        ExercisePersonalHintRepositoryImpl(
            db,
            db.exerciseDao(),
            db.exercisePersonalHintDao(),
            sync(store),
            store,
        )

    val target = repository.editTarget(id)!!
    assertEquals(NoteSaveResult.Saved, repository.save(target, "  держать лопатки  "))
    assertEquals("держать лопатки", db.exercisePersonalHintDao().get("standard-exercise")?.text)
    assertEquals("STANDARD", db.exerciseDao().getById(id)?.origin)
    assertEquals(NoteSaveResult.Saved, repository.unpin(target))
    assertEquals(null, db.exercisePersonalHintDao().get("standard-exercise"))
    assertEquals(null, repository.editTarget(99))

    val stale = repository.editTarget(id)!!
    store.save(BackendTokens("other", "other@example.com", "access", "refresh"))
    assertEquals(NoteSaveResult.MissingOrInactive, repository.save(stale, "foreign"))
  }

  private fun sync(store: Store): BackendSync =
      BackendSync(
          db,
          object : BackendTransport {
            override val json = Json

            override suspend fun public(
                method: String,
                path: String,
                body: kotlinx.serialization.json.JsonElement?,
            ) = kotlinx.serialization.json.JsonNull

            override suspend fun authorized(
                method: String,
                path: String,
                body: kotlinx.serialization.json.JsonElement?,
            ) = kotlinx.serialization.json.JsonNull
          },
          store,
      )

  private class Store : BackendSessionStore {
    override val session = MutableStateFlow<BackendTokens?>(null)
    private var epoch = 0L
    override val sessionEpoch: Long
      get() = epoch

    override fun save(tokens: BackendTokens?) {
      epoch++
      session.value = tokens
    }
  }
}
