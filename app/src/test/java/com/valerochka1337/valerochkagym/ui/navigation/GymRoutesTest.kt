package com.valerochka1337.valerochkagym.ui.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GymRoutesTest {

  @Test
  fun `legacy v9 detail state restores against the base-only graph`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val original = controller(context).apply { graph = detailGraph() }
    original.navigate("exercise_detail/42/old-variant-token")
    val savedState = original.saveState()
    assertNotNull(savedState)

    val restored = controller(context)
    restored.restoreState(savedState)
    restored.graph = restored.detailGraph()

    assertEquals(GymRoutes.LEGACY_EXERCISE_DETAIL, restored.currentDestination?.route)
    assertEquals(42L, restored.currentBackStackEntry?.arguments?.getLong(GymRoutes.EXERCISE_ID_ARG))
    assertEquals(
        "old-variant-token",
        restored.currentBackStackEntry?.arguments?.getString("executionGroup"),
    )
    assertEquals("exercise_detail/42", GymRoutes.exerciseDetail(42))
    assertEquals(
        "exercise_detail/{exerciseId}/{executionGroup}",
        GymRoutes.LEGACY_EXERCISE_DETAIL,
    )
  }

  @Test
  fun `gym detail route encodes and restores the stable gym id`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val navController = controller(context).apply { graph = detailGraph() }

    navController.navigate(GymRoutes.gymDetail("gym/id with space"))

    assertEquals("gym_detail/gym%2Fid%20with%20space", GymRoutes.gymDetail("gym/id with space"))
    assertEquals(GymRoutes.GYM_DETAIL, navController.currentDestination?.route)
    assertEquals(
        "gym/id with space",
        navController.currentBackStackEntry?.arguments?.getString(GymRoutes.GYM_ID_ARG),
    )
  }

  @Test
  fun `routine detail route restores the stable routine id`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val navController = controller(context).apply { graph = detailGraph() }

    navController.navigate(GymRoutes.routineDetail(42))

    assertEquals("routine_detail/42", GymRoutes.routineDetail(42))
    assertEquals(GymRoutes.ROUTINE_DETAIL, navController.currentDestination?.route)
    assertEquals(
        42L,
        navController.currentBackStackEntry?.arguments?.getLong(GymRoutes.ROUTINE_ID_ARG),
    )
  }

  private fun controller(context: android.content.Context): NavHostController =
      NavHostController(context).also { it.navigatorProvider.addNavigator(ComposeNavigator()) }

  private fun NavHostController.detailGraph() =
      createGraph(startDestination = "root") {
        composable("root") {}
        composable(
            GymRoutes.EXERCISE_DETAIL,
            arguments = listOf(navArgument(GymRoutes.EXERCISE_ID_ARG) { type = NavType.LongType }),
        ) {}
        composable(
            GymRoutes.LEGACY_EXERCISE_DETAIL,
            arguments =
                listOf(
                    navArgument(GymRoutes.EXERCISE_ID_ARG) { type = NavType.LongType },
                    navArgument("executionGroup") { type = NavType.StringType },
                ),
        ) {}
        composable(
            GymRoutes.GYM_DETAIL,
            arguments = listOf(navArgument(GymRoutes.GYM_ID_ARG) { type = NavType.StringType }),
        ) {}
        composable(
            GymRoutes.ROUTINE_DETAIL,
            arguments = listOf(navArgument(GymRoutes.ROUTINE_ID_ARG) { type = NavType.LongType }),
        ) {}
      }
}
