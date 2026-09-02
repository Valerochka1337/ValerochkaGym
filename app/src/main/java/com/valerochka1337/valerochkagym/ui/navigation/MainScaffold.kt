package com.valerochka1337.valerochkagym.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.valerochka1337.valerochkagym.R
import com.valerochka1337.valerochkagym.service.RestTimerState
import com.valerochka1337.valerochkagym.ui.common.formatRestClock
import com.valerochka1337.valerochkagym.ui.haptics.gymHaptics
import com.valerochka1337.valerochkagym.ui.theme.GymMotion
import com.valerochka1337.valerochkagym.ui.update.AppUpdateHost
import com.valerochka1337.valerochkagym.ui.update.AppUpdateViewModel

/** A root destination shared by the compact navigation bar and larger-screen rail. */
private data class TabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val tabs = listOf(
    TabItem(
        GymRoutes.WORKOUTS,
        "Тренировки",
        Icons.Filled.FitnessCenter,
        Icons.Outlined.FitnessCenter,
    ),
    TabItem(
        GymRoutes.CALENDAR,
        "Календарь",
        Icons.Filled.CalendarMonth,
        Icons.Outlined.CalendarMonth,
    ),
    TabItem(
        GymRoutes.ANALYSIS,
        "Анализ",
        Icons.Filled.Insights,
        Icons.Outlined.Insights,
    ),
)

/**
 * Top-level app shell: Material adaptive navigation chooses a bar or rail for the available
 * window. Navigation is hidden on pushed/full-screen routes (e.g. active workout).
 * While a workout is active and the user is on a tab, a resume banner sits above the bar.
 *
 * [requestedRoute] — маршрут, запрошенный извне (тап по уведомлению тренировки). Обрабатывается
 * один раз и сбрасывается через [onRequestedRouteHandled], иначе возврат назад тут же вернул бы
 * пользователя обратно на тот же экран.
 */
@Composable
fun MainScaffold(
    requestedRoute: String? = null,
    onRequestedRouteHandled: () -> Unit = {},
    viewModel: MainScaffoldViewModel = hiltViewModel(),
    appUpdateViewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val showBottomBar = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val appUpdateState by appUpdateViewModel.uiState.collectAsStateWithLifecycle()
    // Баннер показываем только на вкладках (где виден нижний бар): это и есть «мы не на
    // активной тренировке», при этом он не мешает временным полноэкранным экранам.
    val showResumeBanner = banner != null && showBottomBar

    // currentBackStackEntry в ключе не для реакции на переходы, а как признак «граф уже установлен»:
    // при холодном старте по тапу на уведомление он ещё null, и navigate упал бы.
    LaunchedEffect(requestedRoute, currentBackStackEntry) {
        val route = requestedRoute ?: return@LaunchedEffect
        val destination = currentBackStackEntry?.destination ?: return@LaunchedEffect
        if (destination.route != route) {
            navController.navigate(route) { launchSingleTop = true }
        }
        onRequestedRouteHandled()
    }

    val destinationContent: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowWidthClass = GymWindowWidthClass.from(maxWidth)
            MainDestinationContent(
                showResumeBanner = showResumeBanner,
                banner = banner,
                onResumeWorkout = { navController.navigate(GymRoutes.ACTIVE_WORKOUT) },
            ) { modifier ->
                GymNavGraph(
                    navController = navController,
                    modifier = modifier,
                    windowWidthClass = windowWidthClass,
                    appUpdateState = appUpdateState,
                    onCheckUpdate = appUpdateViewModel::checkForUpdate,
                    onDownloadUpdate = appUpdateViewModel::downloadAvailableUpdate,
                    onInstallUpdate = appUpdateViewModel::requestInstallation,
                    onRetryUpdate = appUpdateViewModel::retryFailedAction,
                )
            }
        }
    }
    if (showBottomBar) {
        val haptics = gymHaptics()
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                tabs.forEach { tab ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    item(
                        selected = selected,
                        onClick = {
                            if (!selected) haptics.tap()
                            navController.navigateToTab(tab.route)
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            content = destinationContent,
        )
    } else {
        destinationContent()
    }

    AppUpdateHost(
        state = appUpdateState,
        externalActions = appUpdateViewModel.externalActions,
        onDismissPromptOnce = appUpdateViewModel::dismissPromptOnce,
        onIgnorePromptVersion = appUpdateViewModel::ignorePromptVersion,
        onDownload = appUpdateViewModel::downloadAvailableUpdate,
        onHideDownloadDialog = appUpdateViewModel::hideDownloadDialog,
        onRetry = appUpdateViewModel::retryFailedAction,
        onDismissError = appUpdateViewModel::dismissErrorDialog,
        onUnknownSourcesReturned = appUpdateViewModel::unknownSourcesPermissionReturned,
        onExternalActionFailed = appUpdateViewModel::externalActionFailed,
    )
}

@Composable
private fun MainDestinationContent(
    showResumeBanner: Boolean,
    banner: SessionBannerState?,
    onResumeWorkout: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showResumeBanner,
                enter = expandVertically(GymMotion.spatialDefault()) +
                    fadeIn(GymMotion.effectsDefault()),
                exit = shrinkVertically(GymMotion.spatialDefault()) +
                    fadeOut(GymMotion.effectsDefault()),
            ) {
                var lastBanner by remember { mutableStateOf(banner) }
                banner?.let { lastBanner = it }
                lastBanner?.let { state ->
                    ResumeWorkoutBanner(state = state, onClick = onResumeWorkout)
                }
            }
        },
    ) { innerPadding ->
        content(Modifier.padding(innerPadding))
    }
}

/**
 * Плашка идущей тренировки: та же информация и тот же язык, что в уведомлении в шторке —
 * во время отдыха отсчёт либо ожидание пульса, между подходами упражнение и номер подхода.
 */
@Composable
private fun ResumeWorkoutBanner(state: SessionBannerState, onClick: () -> Unit) {
    val rest = state.rest
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (rest != null) {
                        ImageVector.vectorResource(R.drawable.ic_rest_timer)
                    } else {
                        Icons.Default.PlayArrow
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (rest != null) {
                            restLabel(rest)
                        } else {
                            "Тренировка идёт"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    bannerSubtitle(state)?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Открыть",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (rest is RestTimerState.Timed && rest.totalSec > 0) {
                Spacer(Modifier.height(12.dp))
                val elapsed = (rest.totalSec - rest.remainingSec).toFloat() / rest.totalSec
                LinearProgressIndicator(
                    progress = { elapsed },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

private fun restLabel(rest: RestTimerState): String = when (rest) {
    is RestTimerState.Timed -> "Отдых · ${formatRestClock(rest.remainingSec)}"
    is RestTimerState.HeartRate -> "Отдых · пульс ≤ ${rest.thresholdBpm} BPM"
}

/** «Жим лёжа · 60×10» во время отдыха, «Жим лёжа · подход 3 из 4» между подходами. */
private fun bannerSubtitle(state: SessionBannerState): String? {
    val tail = if (state.rest != null) {
        state.setSummary
    } else {
        state.setNumber?.let { number -> "подход $number из ${state.setsInExercise}" }
    }
    return listOfNotNull(state.exerciseName, tail).joinToString(" · ").ifEmpty { null }
}

/** Standard bottom-nav switch: single top-level copy of each tab, state preserved. */
private fun androidx.navigation.NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
