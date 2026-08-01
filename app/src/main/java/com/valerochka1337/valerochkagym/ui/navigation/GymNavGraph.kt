package com.valerochka1337.valerochkagym.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.valerochka1337.valerochkagym.ui.active.ActiveWorkoutScreen
import com.valerochka1337.valerochkagym.ui.active.ActiveWorkoutViewModel
import com.valerochka1337.valerochkagym.ui.history.HistoryScreen
import com.valerochka1337.valerochkagym.ui.history.WorkoutDetailScreen
import com.valerochka1337.valerochkagym.ui.library.ExerciseLibraryScreen
import com.valerochka1337.valerochkagym.ui.routine.RoutineEditorScreen
import com.valerochka1337.valerochkagym.ui.routine.RoutineEditorViewModel
import com.valerochka1337.valerochkagym.ui.settings.SettingsScreen
import com.valerochka1337.valerochkagym.ui.summary.WorkoutSummaryScreen
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

    /** Ключ savedStateHandle, через который библиотека-пикер возвращает выбранное упражнение. */
    const val SELECTED_EXERCISE_ID = "selected_exercise_id"

    const val ROUTINE_EDITOR = "routine_editor?$ROUTINE_ID_ARG={$ROUTINE_ID_ARG}"
    const val WORKOUT_SUMMARY = "workout_summary/{$WORKOUT_ID_ARG}"
    const val WORKOUT_DETAIL = "workout_detail/{$WORKOUT_ID_ARG}"

    fun routineEditor(routineId: String? = null) =
        if (routineId != null) "routine_editor?$ROUTINE_ID_ARG=$routineId" else "routine_editor"
    fun workoutSummary(workoutId: String) = "workout_summary/$workoutId"
    fun workoutDetail(workoutId: String) = "workout_detail/$workoutId"
}

/** The tab root that the app opens on and that back navigation returns to. */
const val GYM_START_DESTINATION = GymRoutes.WORKOUTS

// Пружинная моторика M3 Expressive: плавный «спатиальный» сдвиг + «эффектное» затухание.
private val NavSlideSpec: FiniteAnimationSpec<IntOffset> =
    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
private val NavFadeSpec: FiniteAnimationSpec<Float> =
    spring(stiffness = Spring.StiffnessMedium)

/** Кроссфейд между вкладками нижнего меню — быстрый, предсказуемый, без слайда. */
private val TabFadeSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 180)

/** Порядок нижних вкладок слева направо; -1 — маршрут не является вкладкой. */
private fun tabIndex(route: String?): Int = when (route) {
    GymRoutes.WORKOUTS -> 0
    GymRoutes.HISTORY -> 1
    GymRoutes.SETTINGS -> 2
    else -> -1
}

/**
 * Вкладки нижнего меню — сиблинги, а не иерархия, поэтому между ними используем чистый
 * кроссфейд (направление слайда для них не имеет смысла и подтормаживает при restoreState).
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean {
    val from = tabIndex(initialState.destination.route)
    val to = tabIndex(targetState.destination.route)
    return from >= 0 && to >= 0 && from != to
}

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
        // Базовый переход для обычных экранов: сдвиг по горизонтали + затухание.
        enterTransition = {
            if (isTabSwitch()) fadeIn(TabFadeSpec)
            else fadeIn(NavFadeSpec) + slideIntoContainer(SlideDirection.Start, NavSlideSpec)
        },
        exitTransition = {
            if (isTabSwitch()) fadeOut(TabFadeSpec)
            else fadeOut(NavFadeSpec) + slideOutOfContainer(SlideDirection.Start, NavSlideSpec)
        },
        popEnterTransition = {
            if (isTabSwitch()) fadeIn(TabFadeSpec)
            else fadeIn(NavFadeSpec) + slideIntoContainer(SlideDirection.End, NavSlideSpec)
        },
        popExitTransition = {
            if (isTabSwitch()) fadeOut(TabFadeSpec)
            else fadeOut(NavFadeSpec) + slideOutOfContainer(SlideDirection.End, NavSlideSpec)
        },
    ) {
        composable(GymRoutes.WORKOUTS) {
            WorkoutsScreen(
                onCreateRoutine = { navController.navigate(GymRoutes.routineEditor(null)) },
                onEditRoutine = { id -> navController.navigate(GymRoutes.routineEditor(id.toString())) },
                onStartWorkout = { navController.navigate(GymRoutes.ACTIVE_WORKOUT) },
            )
        }
        composable(GymRoutes.HISTORY) {
            HistoryScreen(
                onWorkoutClick = { workoutId -> navController.navigate(GymRoutes.workoutDetail(workoutId)) },
            )
        }
        composable(GymRoutes.SETTINGS) { SettingsScreen() }

        composable(GymRoutes.LIBRARY) {
            ExerciseLibraryScreen(
                onBack = { navController.popBackStack() },
                // Открыта из редактора программы: возвращаем выбранное упражнение назад.
                onExerciseSelected = { exercise ->
                    navController.previousBackStackEntry?.savedStateHandle
                        ?.set(GymRoutes.SELECTED_EXERCISE_ID, exercise.id)
                    navController.popBackStack()
                },
            )
        }
        composable(
            GymRoutes.ACTIVE_WORKOUT,
            // Полноэкранный маршрут — выезжает снизу и уезжает вниз.
            enterTransition = {
                fadeIn(NavFadeSpec) +
                    slideIntoContainer(SlideDirection.Up, NavSlideSpec)
            },
            popExitTransition = {
                fadeOut(NavFadeSpec) +
                    slideOutOfContainer(SlideDirection.Down, NavSlideSpec)
            },
        ) { backStackEntry ->
            val viewModel = hiltViewModel<ActiveWorkoutViewModel>(backStackEntry)
            val selectedExerciseId by backStackEntry.savedStateHandle
                .getStateFlow<Long?>(GymRoutes.SELECTED_EXERCISE_ID, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(selectedExerciseId) {
                val id = selectedExerciseId ?: return@LaunchedEffect
                viewModel.addExerciseById(id)
                backStackEntry.savedStateHandle[GymRoutes.SELECTED_EXERCISE_ID] = null
            }
            ActiveWorkoutScreen(
                onFinished = { workoutId ->
                    navController.navigate(GymRoutes.workoutSummary(workoutId)) {
                        popUpTo(GymRoutes.ACTIVE_WORKOUT) { inclusive = true }
                    }
                },
                onDiscarded = { navController.popBackStack(GymRoutes.WORKOUTS, inclusive = false) },
                onNavigateBack = { navController.popBackStack() },
                onAddExercise = { navController.navigate(GymRoutes.LIBRARY) },
                viewModel = viewModel,
            )
        }

        composable(
            route = GymRoutes.ROUTINE_EDITOR,
            arguments = listOf(
                navArgument(GymRoutes.ROUTINE_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val viewModel = hiltViewModel<RoutineEditorViewModel>(backStackEntry)
            val selectedExerciseId by backStackEntry.savedStateHandle
                .getStateFlow<Long?>(GymRoutes.SELECTED_EXERCISE_ID, null)
                .collectAsStateWithLifecycle()
            LaunchedEffect(selectedExerciseId) {
                val id = selectedExerciseId ?: return@LaunchedEffect
                viewModel.addExerciseById(id)
                backStackEntry.savedStateHandle[GymRoutes.SELECTED_EXERCISE_ID] = null
            }
            RoutineEditorScreen(
                onBack = { navController.popBackStack() },
                onAddExercise = { navController.navigate(GymRoutes.LIBRARY) },
                viewModel = viewModel,
            )
        }

        composable(
            route = GymRoutes.WORKOUT_SUMMARY,
            arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
            // Итоги — тоже полноэкранные: выезжают снизу, уезжают вниз.
            enterTransition = {
                fadeIn(NavFadeSpec) +
                    slideIntoContainer(SlideDirection.Up, NavSlideSpec)
            },
            popExitTransition = {
                fadeOut(NavFadeSpec) +
                    slideOutOfContainer(SlideDirection.Down, NavSlideSpec)
            },
        ) {
            WorkoutSummaryScreen(
                onDone = { navController.popBackStack(GymRoutes.WORKOUTS, inclusive = false) },
            )
        }

        composable(
            route = GymRoutes.WORKOUT_DETAIL,
            arguments = listOf(navArgument(GymRoutes.WORKOUT_ID_ARG) { type = NavType.StringType }),
        ) {
            WorkoutDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
