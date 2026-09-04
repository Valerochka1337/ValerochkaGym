package com.valerochka1337.valerochkagym.ui.library

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.valerochka1337.valerochkagym.data.db.CanonicalExerciseRegistry
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseDao
import com.valerochka1337.valerochkagym.data.db.dao.ExerciseMuscleDao
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseMuscleEntity
import com.valerochka1337.valerochkagym.data.db.entity.ExerciseType
import com.valerochka1337.valerochkagym.data.db.entity.MuscleGroup
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepository
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogRepositoryState
import com.valerochka1337.valerochkagym.domain.ExerciseCatalogSnapshot
import com.valerochka1337.valerochkagym.ui.theme.GymTheme
import com.valerochka1337.valerochkagym.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w420dp-h800dp-xhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseLibraryScreenTest {

  private val mainDispatcherRule = MainDispatcherRule()
  private val composeRule = createComposeRule()

  @get:Rule val rules: RuleChain = RuleChain.outerRule(mainDispatcherRule).around(composeRule)

  @Test
  fun `search result update scrolls the catalog to the first item`() {
    val computeDispatcher = StandardTestDispatcher()
    val exercises =
        List(100) { index ->
          ExerciseEntity(
              id = index.toLong() + 1,
              name =
                  if (index < 50) {
                    "Жим ${index.toString().padStart(3, '0')}"
                  } else {
                    "Тяга ${index.toString().padStart(3, '0')}"
                  },
              muscleGroup = MuscleGroup.CHEST,
              type = ExerciseType.STRENGTH,
          )
        }
    val viewModel =
        ExerciseLibraryViewModel(
            exerciseDao = FakeExerciseDao,
            exerciseMuscleDao = FakeExerciseMuscleDao,
            catalogRepository = FakeExerciseCatalogRepository(exercises),
            computeDispatcher = computeDispatcher,
        )
    lateinit var listState: LazyListState

    composeRule.setContent {
      listState = rememberLazyListState()
      GymTheme {
        ExerciseLibraryScreen(
            onBack = {},
            onOpenSettings = {},
            exerciseListState = listState,
            viewModel = viewModel,
        )
      }
    }
    computeDispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag(EXERCISE_CATALOG_LIST_TAG).performScrollToIndex(70)
    composeRule.runOnIdle { assertTrue(listState.firstVisibleItemIndex > 0) }

    composeRule.onNodeWithText("Поиск упражнения").performTextInput("Тяга")
    composeRule.runOnIdle {
      assertEquals("Тяга", viewModel.query.value)
      assertEquals("", viewModel.uiState.value.query)
      assertTrue(listState.firstVisibleItemIndex > 0)
    }

    computeDispatcher.scheduler.advanceUntilIdle()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      viewModel.uiState.value.query == "Тяга" && viewModel.uiState.value.exercises?.size == 50
    }
    composeRule.waitForIdle()

    composeRule.runOnIdle { assertEquals(0, listState.firstVisibleItemIndex) }
  }

  @Test
  fun `browse mode leaves built-in rows inert while custom rows stay editable`() {
    val computeDispatcher = StandardTestDispatcher()
    val builtIn = CanonicalExerciseRegistry.entries.first().exercise.copy(id = 201L)
    val custom =
        ExerciseEntity(
            id = 202L,
            name = "Своё упражнение",
            muscleGroup = MuscleGroup.CHEST,
            type = ExerciseType.STRENGTH,
            isCustom = true,
        )
    val viewModel =
        ExerciseLibraryViewModel(
            exerciseDao = FakeExerciseDao,
            exerciseMuscleDao = FakeExerciseMuscleDao,
            catalogRepository = FakeExerciseCatalogRepository(listOf(builtIn, custom)),
            computeDispatcher = computeDispatcher,
        )

    composeRule.setContent {
      GymTheme { ExerciseLibraryScreen(onBack = {}, onOpenSettings = {}, viewModel = viewModel) }
    }
    computeDispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()

    assertTrue(
        !composeRule
            .onNodeWithTag("exercise_catalog_row_${builtIn.id}")
            .fetchSemanticsNode()
            .config
            .contains(SemanticsActions.OnClick)
    )
    assertTrue(
        composeRule
            .onNodeWithTag("exercise_catalog_row_${custom.id}")
            .fetchSemanticsNode()
            .config
            .contains(SemanticsActions.OnClick)
    )
  }

  @Test
  fun `picker mode keeps built-in row selection available`() {
    val computeDispatcher = StandardTestDispatcher()
    val builtIn = CanonicalExerciseRegistry.entries.first().exercise.copy(id = 203L)
    val viewModel =
        ExerciseLibraryViewModel(
            exerciseDao = FakeExerciseDao,
            exerciseMuscleDao = FakeExerciseMuscleDao,
            catalogRepository = FakeExerciseCatalogRepository(listOf(builtIn)),
            computeDispatcher = computeDispatcher,
        )
    val selected = mutableListOf<ExerciseEntity>()

    composeRule.setContent {
      GymTheme {
        ExerciseLibraryScreen(
            onBack = {},
            onOpenSettings = {},
            onExerciseSelected = { selected += it },
            viewModel = viewModel,
        )
      }
    }
    computeDispatcher.scheduler.advanceUntilIdle()
    composeRule.waitForIdle()

    val builtInRow = composeRule.onNodeWithTag("exercise_catalog_row_${builtIn.id}")
    assertTrue(builtInRow.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
    builtInRow.performClick()
    composeRule.runOnIdle { assertEquals(listOf(builtIn), selected) }
  }

  private object FakeExerciseDao : ExerciseDao {
    override fun getAll(): Flow<List<ExerciseEntity>> = flowOf(emptyList())

    override suspend fun insert(exercise: ExerciseEntity): Long = 0

    override suspend fun update(exercise: ExerciseEntity) = Unit

    override suspend fun insertAll(exercises: List<ExerciseEntity>) = Unit

    override suspend fun count(): Int = 0

    override suspend fun getById(id: Long): ExerciseEntity? = null

    override suspend fun getAllOnce(): List<ExerciseEntity> = emptyList()
  }

  private object FakeExerciseMuscleDao : ExerciseMuscleDao {
    override fun observeAll(): Flow<List<ExerciseMuscleEntity>> = flowOf(emptyList())

    override suspend fun getForExercise(exerciseId: Long): List<ExerciseMuscleEntity> = emptyList()

    override suspend fun getMappedExerciseIds(): List<Long> = emptyList()

    override suspend fun upsertAll(rows: List<ExerciseMuscleEntity>) = Unit

    override suspend fun deleteForExercise(exerciseId: Long) = Unit
  }

  private class FakeExerciseCatalogRepository(exercises: List<ExerciseEntity>) :
      ExerciseCatalogRepository {
    private val state =
        ExerciseCatalogRepositoryState(
            snapshot = ExerciseCatalogSnapshot(exercises, emptyList(), emptyList()),
            gymNames = emptyList(),
        )

    override fun observeCatalog(gymIds: Set<String>): Flow<ExerciseCatalogRepositoryState> =
        flowOf(state)
  }
}
