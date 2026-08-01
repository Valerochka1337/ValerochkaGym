package com.valerochka1337.valerochkagym.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.valerochka1337.valerochkagym.R

/** A root tab shown in the bottom [NavigationBar]. */
private data class TabItem(
    val route: String,
    val label: String,
    @param:DrawableRes val icon: Int,
)

private val tabs = listOf(
    TabItem(GymRoutes.WORKOUTS, "Тренировки", R.drawable.ic_tab_workouts),
    TabItem(GymRoutes.HISTORY, "История", R.drawable.ic_tab_history),
    TabItem(GymRoutes.SETTINGS, "Настройки", R.drawable.ic_tab_settings),
)

/**
 * Top-level app shell: a [Scaffold] whose bottom [NavigationBar] switches between
 * the three tab roots. The bar is hidden on full-screen routes (e.g. active workout).
 * While a workout is active and the user is on a tab, a resume banner sits above the bar.
 */
@Composable
fun MainScaffold(viewModel: MainScaffoldViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val showBottomBar = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }
    val hasActiveWorkout by viewModel.hasActiveWorkout.collectAsStateWithLifecycle()
    // Баннер показываем только на вкладках (где виден нижний бар): это и есть «мы не на
    // активной тренировке», при этом он не мешает временным полноэкранным экранам.
    val showResumeBanner = hasActiveWorkout && showBottomBar

    Scaffold(
        bottomBar = {
            Column {
                if (showResumeBanner) {
                    ResumeWorkoutBanner(
                        onClick = { navController.navigate(GymRoutes.ACTIVE_WORKOUT) },
                    )
                }
                if (showBottomBar) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected =
                                currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(tab.icon),
                                        contentDescription = tab.label,
                                    )
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        GymNavGraph(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Pinned banner offering to return to the in-progress workout. */
@Composable
private fun ResumeWorkoutBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Тренировка идёт",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Вернуться",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Standard bottom-nav switch: single top-level copy of each tab, state preserved. */
private fun androidx.navigation.NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
