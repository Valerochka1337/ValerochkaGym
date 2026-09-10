package com.valerochka1337.valerochkagym.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerationResult
import com.valerochka1337.valerochkagym.data.ai.ExerciseAiGenerator
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEquipmentEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.profile.AiProfilePromptGate
import com.valerochka1337.valerochkagym.data.settings.SettingsRepository
import com.valerochka1337.valerochkagym.domain.BasicProfile
import com.valerochka1337.valerochkagym.domain.ProfileEditTarget
import com.valerochka1337.valerochkagym.domain.ProfileEditorSnapshot
import com.valerochka1337.valerochkagym.domain.ProfileRepository
import com.valerochka1337.valerochkagym.domain.ProfileSaveResult
import com.valerochka1337.valerochkagym.service.WallClock
import com.valerochka1337.valerochkagym.ui.library.ExerciseLibraryViewModel
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiProfileEntryGateTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun `exercise fill consumes prompt without AI request and continue runs once`() =
      runTest(main.testDispatcher.scheduler) {
        val generator = CountingGenerator()
        val profile = EntryProfileRepository()
        val vm =
            ExerciseLibraryViewModel(
                FakeExerciseDao(),
                FakeMuscleDao(),
                generator,
                aiProfilePromptGate = gate(profile),
            )
        val openedProfiles = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
          vm.openProfile.collect { openedProfiles += it }
        }
        vm.openCreate()
        vm.onAiDescriptionChange("жим")
        vm.generateAiExercise()
        advanceUntilIdle()
        val token = requireNotNull(vm.profilePrompt.value).token
        vm.fillProfileFromPrompt(token)
        vm.fillProfileFromPrompt(token)
        advanceUntilIdle()
        assertEquals(0, generator.calls)
        assertEquals(1, openedProfiles.size)
        val vm2 =
            ExerciseLibraryViewModel(
                FakeExerciseDao(),
                FakeMuscleDao(),
                generator,
                aiProfilePromptGate = gate(EntryProfileRepository()),
            )
        vm2.openCreate()
        vm2.onAiDescriptionChange("жим")
        vm2.generateAiExercise()
        advanceUntilIdle()
        val token2 = requireNotNull(vm2.profilePrompt.value).token
        vm2.continueAfterProfilePrompt(token2)
        vm2.continueAfterProfilePrompt(token2)
        advanceUntilIdle()
        assertEquals(1, generator.calls)
      }

  @Test
  fun `disabling profile reminders continues the exercise once and suppresses the next prompt`() =
      runTest(main.testDispatcher.scheduler) {
        val settings = SettingsRepository(FakeStore())
        val gate = AiProfilePromptGate(settings, EntryProfileRepository(), WallClock { 1L })
        val generator = CountingGenerator()
        val vm =
            ExerciseLibraryViewModel(
                FakeExerciseDao(),
                FakeMuscleDao(),
                generator,
                aiProfilePromptGate = gate,
            )
        vm.openCreate()
        vm.onAiDescriptionChange("жим с гантелями")
        vm.generateAiExercise()
        advanceUntilIdle()
        val token = requireNotNull(vm.profilePrompt.value).token
        vm.acknowledgeProfilePrompt(token)
        advanceUntilIdle()
        assertEquals(0, generator.calls)

        vm.continueAfterProfilePrompt(token, disableFuturePrompts = true)
        vm.continueAfterProfilePrompt(token, disableFuturePrompts = true)
        advanceUntilIdle()
        assertEquals(1, generator.calls)
        assertEquals(listOf("жим с гантелями"), generator.descriptions)
        assertTrue(settings.aiProfilePromptState("owner").disabled)
        assertNull(vm.profilePrompt.value)

        vm.generateAiExercise()
        advanceUntilIdle()
        assertEquals(2, generator.calls)
        assertNull(vm.profilePrompt.value)
      }

  private fun gate(repo: ProfileRepository) =
      AiProfilePromptGate(SettingsRepository(FakeStore()), repo, WallClock { 1L })
}

private class CountingGenerator : ExerciseAiGenerator {
  var calls = 0
  val descriptions = mutableListOf<String>()

  override suspend fun generate(description: String): ExerciseAiGenerationResult {
    calls++
    descriptions += description
    return ExerciseAiGenerationResult.Failure("x")
  }
}

private class EntryProfileRepository : ProfileRepository {
  private val snap = ProfileEditorSnapshot(ProfileEditTarget("owner", "owner", 1), BasicProfile())

  override fun observeCurrent(): Flow<ProfileEditorSnapshot?> = flowOf(snap)

  override suspend fun openEditor(): ProfileEditorSnapshot = snap

  override fun observe(target: ProfileEditTarget): Flow<BasicProfile?> = flowOf(BasicProfile())

  override suspend fun save(target: ProfileEditTarget, profile: BasicProfile) =
      ProfileSaveResult.Saved
}

private class FakeStore : DataStore<Preferences> {
  override val data = MutableStateFlow<Preferences>(emptyPreferences())

  override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
      transform(data.value).also { data.value = it }
}

private class FakeExerciseDao : ExerciseDao {
  override fun getAll(): Flow<List<ExerciseEntity>> = flowOf(emptyList())

  override suspend fun insert(exercise: ExerciseEntity) = 1L

  override suspend fun update(exercise: ExerciseEntity) {}

  override suspend fun insertAll(exercises: List<ExerciseEntity>) {}

  override suspend fun count() = 0

  override suspend fun getById(id: Long): ExerciseEntity? = null

  override suspend fun getAllOnce() = emptyList<ExerciseEntity>()

  override fun observeAllRequirements(): Flow<List<ExerciseEquipmentEntity>> = flowOf(emptyList())

  override suspend fun getRequirementIds(exerciseId: Long) = emptyList<String>()

  override suspend fun getRequirements(exerciseIds: List<Long>) =
      emptyList<ExerciseEquipmentEntity>()

  override suspend fun insertRequirements(requirements: List<ExerciseEquipmentEntity>) {}

  override suspend fun deleteRequirements(exerciseId: Long) {}
}

private class FakeMuscleDao : ExerciseMuscleDao {
  override fun observeAll(): Flow<List<ExerciseMuscleEntity>> = flowOf(emptyList())

  override suspend fun getForExercise(exerciseId: Long) = emptyList<ExerciseMuscleEntity>()

  override suspend fun getMappedExerciseIds() = emptyList<Long>()

  override suspend fun upsertAll(rows: List<ExerciseMuscleEntity>) {}

  override suspend fun deleteForExercise(exerciseId: Long) {}
}
