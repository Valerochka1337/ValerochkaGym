package com.valerochka1337.valerochkagym.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.valerochka1337.valerochkagym.ui.components.GlowBackground
import com.valerochka1337.valerochkagym.ui.history.HistoryScreen
import com.valerochka1337.valerochkagym.ui.settings.SettingsScreen
import com.valerochka1337.valerochkagym.ui.workouts.WorkoutsScreen

/**
 * All navigation routes in the app. The three tab roots use plain constants;
 * parameterized routes expose a builder that fills in the argument.
 */
object GymRoutes {
    const val WORKOUTS = "workouts"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"
    const val ACTIVE_WORKOUT = "active_workout"

    const val ROUTINE_ID_ARG = "routineId"
    const val WORKOUT_ID_ARG = "workoutId"

    const val ROUTINE_EDITOR = "routine_editor/{$ROUTINE_ID_ARG}"
    const val WORKOUT_SUMMARY = "workout_summary/{$WORKOUT_ID_ARG}"
    const val WORKOUT_DETAIL = "workout_detail/{$WORKOUT_ID_ARG}"

    fun routineEditor(routineId: String) = "routine_editor/$routineId"
    fun workoutSummary(workoutId: String) = "workout_summary/$workoutId"
    fun workoutDetail(workoutId: String) = "workout_detail/$workoutId"
}

/** The tab root that the app opens on and that back navigation returns to. */
const val GYM_START_DESTINATION = GymRoutes.WORKOUTS

/**
 * Hosts every destination. [modifier] carries the padding from the enclosing
 * [MainScaffold] so tab content sits above the navigation bar.
 */
@Composable
fun GymNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = GYM_START_DESTINATION,
        modifier = modifier,
    ) {
        composable(GymRoutes.WORKOUTS) { WorkoutsScreen() }
        composable(GymRoutes.HISTORY) { HistoryScreen() }
        composable(GymRoutes.SETTINGS) { SettingsScreen() }

        composable(GymRoutes.LIBRARY) { PlaceholderScreen("Библиотека упражнений") }
        composable(GymRoutes.ACTIVE_WORKOUT) { PlaceholderScreen("Активная тренировка") }

        composable(
            route = GymRoutes.ROUTINE_EDITOR,
            arguments = listOf(navArgument(GymRoutes.ROUTINE_ID_ARG) { type = NavType.StringType }),
        ) { PlaceholderScreen("Редактор программы") }

        composable(
            route = GymRoutes.WORKOUT_SUMMARY,
            arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
        ) { PlaceholderScreen("Итоги тренировки") }

        composable(
            route = GymRoutes.WORKOUT_DETAIL,
            arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
        ) { PlaceholderScreen("Детали тренировки") }
    }
}

/** Temporary full-screen placeholder for routes whose real screens land in later stages. */
@Composable
private fun PlaceholderScreen(title: String) {
    GlowBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
