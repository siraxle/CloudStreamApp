package com.example.cloudstreamapp.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.cloudstreamapp.ui.navigation.NavGraph
import com.example.cloudstreamapp.ui.navigation.Screen
import com.example.cloudstreamapp.ui.player.MiniPlayerBar

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private const val PLAYER_NAV_ROUTE = "player_tab"


@Composable
fun MainScreen() {
    val navController = rememberNavController()

    val navItems = listOf(
        NavItem(Screen.Home.route, "Главная", Icons.Default.Home),
        NavItem(Screen.Home.route, "Браузер", Icons.Default.Folder),
        NavItem(Screen.Playlists.route, "Плейлисты", Icons.Default.QueueMusic),
        NavItem(PLAYER_NAV_ROUTE, "Плеер", Icons.Default.PlayArrow),
        NavItem(Screen.Settings.route, "Настройки", Icons.Default.Settings),
    )

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDest = currentEntry?.destination

    val isOnPlayerScreen = currentDest?.route?.startsWith("player/") == true

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayerBar()
                NavigationBar {
                    navItems.forEach { item ->
                        val selected = when (item.route) {
                            PLAYER_NAV_ROUTE -> isOnPlayerScreen
                            else -> currentDest?.hierarchy?.any { it.route == item.route } == true
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                when {
                                    item.route == PLAYER_NAV_ROUTE -> {
                                        if (!isOnPlayerScreen) {
                                            navController.navigate(Screen.NowPlaying.route) {
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                    isOnPlayerScreen -> {
                                        // Pop the player off the back stack, then navigate to the tab
                                        navController.popBackStack()
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                    else -> {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavGraph(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
