package com.valerochka1337.valerochkagym.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
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
 */
@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination
    val showBottomBar = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        bottomBar = {
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
        },
    ) { innerPadding ->
        GymNavGraph(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
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
