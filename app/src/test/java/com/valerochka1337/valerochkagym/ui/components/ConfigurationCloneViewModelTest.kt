package com.valerochka1337.valerochkagym.ui.components

import com.valerochka1337.valerochkagym.data.db.entity.RoutineEntity
import com.valerochka1337.valerochkagym.domain.GymRepository
import com.valerochka1337.valerochkagym.domain.NoOpGymRepository
import com.valerochka1337.valerochkagym.domain.SaveGymResult
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import com.valerochka1337.valerochkagym.worker.RoutineUploadScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationCloneViewModelTest {
  @get:Rule val mainDispatcherRule = MainDispatcherRule()

  @Test
  fun `opening and cancelling a clone does not write`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeCloneRepository()
        val viewModel = ConfigurationCloneViewModel(repository, FakeRoutineUploadScheduler())

        viewModel.open(ConfigurationCloneTarget.Routine(4, "Ноги"))
        assertEquals("Ноги (копия)", viewModel.uiState.value.name)
        viewModel.dismiss()

        assertNull(viewModel.uiState.value.target)
        assertTrue(repository.routineRequests.isEmpty())
        assertTrue(repository.gymRequests.isEmpty())
      }

  @Test
  fun `blank trimmed name stays open without a write`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeCloneRepository()
        val viewModel = ConfigurationCloneViewModel(repository, FakeRoutineUploadScheduler())
        viewModel.open(ConfigurationCloneTarget.Gym("home", "Дом"))
        viewModel.setName("   ")

        viewModel.save()

        assertEquals("Введите название", viewModel.uiState.value.error)
        assertTrue(repository.gymRequests.isEmpty())
      }

  @Test
  fun `routine clone trims the name and schedules only after repository success`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository =
            FakeCloneRepository(routineResult = RoutineEntity(name = "Ноги 2", syncId = "copy"))
        val scheduler = FakeRoutineUploadScheduler()
        val viewModel = ConfigurationCloneViewModel(repository, scheduler)
        viewModel.open(ConfigurationCloneTarget.Routine(4, "Ноги"))
        viewModel.setName("  Ноги 2  ")

        viewModel.save()

        assertEquals(listOf(4L to "Ноги 2"), repository.routineRequests)
        assertEquals(listOf("copy"), scheduler.scheduled)
        assertNull(viewModel.uiState.value.target)
        assertEquals("Копия программы создана", viewModel.messages.first())
      }

  @Test
  fun `routine clone stays successful when upload scheduling fails after commit`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository =
            FakeCloneRepository(routineResult = RoutineEntity(name = "Ноги 2", syncId = "copy"))
        val viewModel = ConfigurationCloneViewModel(repository, ThrowingRoutineUploadScheduler())
        viewModel.open(ConfigurationCloneTarget.Routine(4, "Ноги"))

        viewModel.save()

        assertNull(viewModel.uiState.value.target)
        assertEquals(listOf(4L to "Ноги (копия)"), repository.routineRequests)
        assertEquals("Копия программы создана", viewModel.messages.first())
      }

  @Test
  fun `failed clone retains draft for retry`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository =
            FakeCloneRepository(
                gymResults = ArrayDeque(listOf(SaveGymResult.Failure, SaveGymResult.Saved("copy")))
            )
        val viewModel = ConfigurationCloneViewModel(repository, FakeRoutineUploadScheduler())
        viewModel.open(ConfigurationCloneTarget.Gym("source", "Дом"))
        viewModel.setName("Мой зал")

        viewModel.save()
        assertEquals("Мой зал", viewModel.uiState.value.name)
        assertEquals("Не удалось создать копию зала", viewModel.uiState.value.error)

        viewModel.save()
        assertNull(viewModel.uiState.value.target)
        assertEquals(listOf("source" to "Мой зал", "source" to "Мой зал"), repository.gymRequests)
      }

  @Test
  fun `failed routine clone retains the draft without scheduling an upload`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = FakeCloneRepository()
        val scheduler = FakeRoutineUploadScheduler()
        val viewModel = ConfigurationCloneViewModel(repository, scheduler)
        viewModel.open(ConfigurationCloneTarget.Routine(4, "Ноги"))
        viewModel.setName("Моя программа")

        viewModel.save()

        assertTrue(scheduler.scheduled.isEmpty())
        assertEquals("Моя программа", viewModel.uiState.value.name)
        assertEquals("Не удалось создать копию программы", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSaving)
      }

  @Test
  fun `saving clone ignores repeated requests while the first write is pending`() =
      runTest(mainDispatcherRule.testDispatcher.scheduler) {
        val repository = BlockingCloneRepository()
        val viewModel = ConfigurationCloneViewModel(repository, FakeRoutineUploadScheduler())
        viewModel.open(ConfigurationCloneTarget.Routine(4, "Ноги"))

        viewModel.save()
        viewModel.save()

        assertTrue(viewModel.uiState.value.isSaving)
        assertEquals(1, repository.calls)
        repository.release.complete(RoutineEntity(name = "Ноги", syncId = "copy"))
        assertFalse(viewModel.uiState.value.isSaving)
        assertNull(viewModel.uiState.value.target)
      }

  private class FakeCloneRepository(
      private val routineResult: RoutineEntity? = null,
      private val gymResults: ArrayDeque<SaveGymResult> = ArrayDeque(),
  ) : GymRepository by NoOpGymRepository {
    val routineRequests = mutableListOf<Pair<Long, String?>>()
    val gymRequests = mutableListOf<Pair<String, String>>()

    override suspend fun duplicateRoutine(sourceRoutineId: Long, name: String?): RoutineEntity? {
      routineRequests += sourceRoutineId to name
      return routineResult
    }

    override suspend fun cloneGym(sourceGymId: String, name: String): SaveGymResult {
      gymRequests += sourceGymId to name
      return gymResults.removeFirstOrNull() ?: SaveGymResult.Failure
    }
  }

  private class BlockingCloneRepository : GymRepository by NoOpGymRepository {
    val release = CompletableDeferred<RoutineEntity>()
    var calls = 0

    override suspend fun duplicateRoutine(sourceRoutineId: Long, name: String?): RoutineEntity? {
      calls++
      return release.await()
    }
  }

  private class FakeRoutineUploadScheduler : RoutineUploadScheduler {
    val scheduled = mutableListOf<String>()

    override fun schedule(syncId: String) {
      scheduled += syncId
    }

    override fun scheduleDeletion(syncId: String, updatedAt: Long) = Unit

    override suspend fun scheduleAll(): Int = 0
  }

  private class ThrowingRoutineUploadScheduler : RoutineUploadScheduler {
    override fun schedule(syncId: String) = error("queue unavailable")

    override fun scheduleDeletion(syncId: String, updatedAt: Long) = Unit

    override suspend fun scheduleAll(): Int = 0
  }
}
