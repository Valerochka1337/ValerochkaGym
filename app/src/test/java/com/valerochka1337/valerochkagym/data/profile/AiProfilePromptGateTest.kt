package com.valerochka1337.valerochkagym.data.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutEntity
import com.valerochka1337.valerochkagym.data.db.entity.WorkoutSetEntity
import com.valerochka1337.valerochkagym.data.db.relation.WorkoutFull
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.ActiveWorkoutRepository
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.domain.TrainingGoal
import com.valerochka1337.valerochkagym.service.WallClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProfilePromptGateTest {
  @Test
  fun `calendar shares visible cooldown and one time continuation with other AI actions`() =
      runTest {
        val store = FakeDataStore()
        val gate =
            AiProfilePromptGate(
                SettingsRepository(store),
                FakeProfileRepository(),
                WallClock { 1000L },
            )
        val prompt = gate.request(AiProfilePromptKind.CALENDAR) as AiProfilePromptDecision.Show
        assertEquals(AiProfilePromptDecision.Busy, gate.request(AiProfilePromptKind.EXERCISE))
        assertTrue(gate.acknowledgeVisible(prompt.token))
        assertTrue(gate.consume(prompt.token, false))
        assertFalse(gate.consume(prompt.token, false))
        assertEquals(AiProfilePromptDecision.Proceed, gate.request(AiProfilePromptKind.IN_BODY))
      }

  @Test
  fun `visible acknowledgement starts cooldown and stale callbacks do not consume twice`() =
      runTest {
        val store = FakeDataStore()
        val repository = FakeProfileRepository()
        var now = 1_000L
        val gate = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { now })

        val prompt = gate.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show
        assertEquals(AiProfilePromptDecision.Busy, gate.request(AiProfilePromptKind.EXERCISE))
        assertTrue(gate.acknowledgeVisible(prompt.token))
        now += 100
        assertFalse(gate.acknowledgeVisible(prompt.token))
        assertEquals(
            1_000L,
            SettingsRepository(store).aiProfilePromptState("owner-a").lastShownAtMillis,
        )
        assertTrue(gate.consume(prompt.token, disableFuturePrompts = false))
        assertFalse(gate.consume(prompt.token, disableFuturePrompts = false))
        now = 1_000L + 72L * 60L * 60L * 1000L - 1L
        assertEquals(AiProfilePromptDecision.Proceed, gate.request(AiProfilePromptKind.EXERCISE))
      }

  @Test
  fun `owner scopes keep independent reservations`() = runTest {
    val repository = FakeProfileRepository()
    val gate =
        AiProfilePromptGate(SettingsRepository(FakeDataStore()), repository, WallClock { 1L })
    assertTrue(gate.request(AiProfilePromptKind.IN_BODY) is AiProfilePromptDecision.Show)
    repository.switchTo("owner-b", epoch = 2L)
    assertTrue(gate.request(AiProfilePromptKind.IN_BODY) is AiProfilePromptDecision.Show)
  }

  @Test
  fun `complete profile proceeds without durable reservation`() = runTest {
    val store = FakeDataStore()
    val repository =
        FakeProfileRepository(
            profile =
                BasicProfile(
                    trainingGoal = TrainingGoal.STRENGTH,
                    experienceLevel =
                        com.valerochka1337.valerochkagym.domain.ExperienceLevel.BEGINNER,
                )
        )
    val gate = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { 1L })
    assertEquals(AiProfilePromptDecision.Proceed, gate.request(AiProfilePromptKind.EXERCISE))
    assertEquals(null, SettingsRepository(store).aiProfilePromptState("owner-a").reservationToken)
  }

  @Test
  fun `disable consumes one decision and suppresses later prompts`() = runTest {
    val store = FakeDataStore()
    val gate =
        AiProfilePromptGate(SettingsRepository(store), FakeProfileRepository(), WallClock { 1L })
    val prompt = gate.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show
    assertTrue(gate.consume(prompt.token, disableFuturePrompts = true))
    assertEquals(AiProfilePromptDecision.Proceed, gate.request(AiProfilePromptKind.EXERCISE))
    assertTrue(SettingsRepository(store).aiProfilePromptState("owner-a").disabled)
  }

  @Test
  fun `new process clears an unacknowledged reservation without an effect`() = runTest {
    val store = FakeDataStore()
    val repository = FakeProfileRepository()
    val first = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { 1L })
    assertTrue(first.request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show)
    val restarted = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { 2L })
    assertTrue(restarted.request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show)
    assertEquals(null, SettingsRepository(store).aiProfilePromptState("owner-a").lastShownAtMillis)
  }

  @Test
  fun `new session for the same owner replaces an abandoned live reservation`() = runTest {
    val store = FakeDataStore()
    val repository = FakeProfileRepository()
    val gate = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { 1L })
    assertTrue(gate.request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show)
    repository.switchTo("owner-a", epoch = 2L)
    assertTrue(gate.request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show)
  }

  @Test
  fun `either missing goal or experience requires a prompt`() = runTest {
    val store = FakeDataStore()
    assertTrue(
        AiProfilePromptGate(
                SettingsRepository(store),
                FakeProfileRepository(
                    BasicProfile(
                        experienceLevel =
                            com.valerochka1337.valerochkagym.domain.ExperienceLevel.BEGINNER
                    )
                ),
                WallClock { 1L },
            )
            .request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show
    )
    assertTrue(
        AiProfilePromptGate(
                SettingsRepository(FakeDataStore()),
                FakeProfileRepository(BasicProfile(trainingGoal = TrainingGoal.STRENGTH)),
                WallClock { 1L },
            )
            .request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show
    )
  }

  @Test
  fun `process death after visible acknowledgement preserves cooldown without continuation`() =
      runTest {
        val store = FakeDataStore()
        val repository = FakeProfileRepository()
        val first = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { 1L })
        val prompt = first.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show
        assertTrue(first.acknowledgeVisible(prompt.token))
        val restarted = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { 2L })
        assertEquals(
            AiProfilePromptDecision.Proceed,
            restarted.request(AiProfilePromptKind.EXERCISE),
        )
        assertEquals(
            1L,
            SettingsRepository(store).aiProfilePromptState("owner-a").lastShownAtMillis,
        )
      }

  @Test
  fun `prompt becomes eligible exactly at seventy two hours`() = runTest {
    val store = FakeDataStore()
    val repository = FakeProfileRepository()
    var now = 0L
    val gate = AiProfilePromptGate(SettingsRepository(store), repository, WallClock { now })
    val prompt = gate.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show
    gate.acknowledgeVisible(prompt.token)
    gate.consume(prompt.token, false)
    now = 72L * 60L * 60L * 1000L - 1L
    assertEquals(AiProfilePromptDecision.Proceed, gate.request(AiProfilePromptKind.EXERCISE))
    now++
    assertTrue(gate.request(AiProfilePromptKind.EXERCISE) is AiProfilePromptDecision.Show)
  }

  @Test
  fun `workout starting while reservation waits cancels the unshown prompt`() = runTest {
    val store = FakeDataStore()
    val settings = SettingsRepository(store)
    val active = FakeActiveWorkoutRepository()
    val reserved = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    store.afterUpdate = { preferences ->
      if (preferences.asMap().keys.any { it.name.endsWith(".reservation_token") }) {
        reserved.complete(Unit)
        release.await()
      }
    }
    val gate = AiProfilePromptGate(settings, FakeProfileRepository(), WallClock { 1L }, active)
    val request = async { gate.request(AiProfilePromptKind.EXERCISE) }
    reserved.await()
    assertTrue(settings.aiProfilePromptState("owner-a").reservationToken != null)
    active.startEmpty()
    release.complete(Unit)

    assertEquals(AiProfilePromptDecision.Proceed, request.await())
    val state = settings.aiProfilePromptState("owner-a")
    assertEquals(null, state.reservationToken)
    assertEquals(null, state.lastShownAtMillis)
  }

  @Test
  fun `old owner callback cannot consume the new owners reservation`() = runTest {
    val store = FakeDataStore()
    val settings = SettingsRepository(store)
    val profiles = FakeProfileRepository()
    val gate = AiProfilePromptGate(settings, profiles, WallClock { 1L })
    val old = gate.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show
    profiles.switchTo("owner-b", 2L)
    val current = gate.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show

    assertFalse(gate.consume(old.token, disableFuturePrompts = true))
    assertEquals(current.token, settings.aiProfilePromptState("owner-b").reservationToken)
    assertFalse(settings.aiProfilePromptState("owner-b").disabled)
    assertTrue(gate.consume(current.token, disableFuturePrompts = false))
  }

  @Test
  fun `process death after consumption cannot restore the consumed continuation`() = runTest {
    val store = FakeDataStore()
    val settings = SettingsRepository(store)
    val profiles = FakeProfileRepository()
    val first = AiProfilePromptGate(settings, profiles, WallClock { 1L })
    val prompt = first.request(AiProfilePromptKind.EXERCISE) as AiProfilePromptDecision.Show
    assertTrue(first.acknowledgeVisible(prompt.token))
    assertTrue(first.consume(prompt.token, disableFuturePrompts = false))
    // The process dies here, before the caller runs its live continuation.
    val restarted = AiProfilePromptGate(settings, profiles, WallClock { 2L })
    assertFalse(restarted.consume(prompt.token, disableFuturePrompts = false))
    assertEquals(null, settings.aiProfilePromptState("owner-a").reservationToken)
    assertEquals(1L, settings.aiProfilePromptState("owner-a").lastShownAtMillis)
    // Only a newly entered action can pass the gate; no pending action is restored.
    assertEquals(AiProfilePromptDecision.Proceed, restarted.request(AiProfilePromptKind.EXERCISE))
  }
}

private class FakeProfileRepository(profile: BasicProfile = BasicProfile()) : ProfileRepository {
  private var snapshot = ProfileEditorSnapshot(ProfileEditTarget("owner-a", "owner-a", 1L), profile)

  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> = flowOf(snapshot)

  override suspend fun openEditor(): ProfileEditorSnapshot = snapshot

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> =
      flowOf(if (target == snapshot.target) snapshot.profile else null)

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile): ProfileSaveResult =
      ProfileSaveResult.Saved

  fun switchTo(scope: String, epoch: Long) {
    snapshot = snapshot.copy(target = ProfileEditTarget(scope, scope, epoch))
  }
}

private class FakeDataStore : DataStore<Preferences> {
  override val data = MutableStateFlow<Preferences>(emptyPreferences())
  var afterUpdate: suspend (Preferences) -> Unit = {}

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
    val updated = transform(data.value)
    data.value = updated
    afterUpdate(updated)
    return updated
  }
}

private class FakeActiveWorkoutRepository : ActiveWorkoutRepository {
  private val active = MutableStateFlow<WorkoutFull?>(null)

  override fun observeActive(): Flow<WorkoutFull?> = active

  override suspend fun startEmpty(): String {
    active.value =
        WorkoutFull(WorkoutEntity(id = "active", name = "Тренировка", startedAt = 1L), emptyList())
    return "active"
  }

  override suspend fun startFromRoutine(routineId: Long): String = error("Unused")

  override suspend fun getSet(setId: Long): WorkoutSetEntity? = error("Unused")

  override suspend fun updateSet(set: WorkoutSetEntity): Unit = error("Unused")

  override suspend fun toggleSetCompleted(setId: Long, completed: Boolean): Unit = error("Unused")

  override suspend fun addSet(workoutExerciseId: Long): Unit = error("Unused")

  override suspend fun deleteSet(setId: Long): Unit = error("Unused")

  override suspend fun addExercise(workoutId: String, exerciseId: Long): Long = error("Unused")

  override suspend fun deleteExercise(workoutExerciseId: Long): Unit = error("Unused")

  override suspend fun reorderExercises(
      workoutId: String,
      orderedWorkoutExerciseIds: List<Long>,
  ): Unit = error("Unused")

  override suspend fun finish(workoutId: String): Unit = error("Unused")

  override suspend fun discard(workoutId: String): Unit = error("Unused")
}
