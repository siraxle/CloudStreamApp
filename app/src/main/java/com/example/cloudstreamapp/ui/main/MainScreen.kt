package com.example.cloudstreamapp.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
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

@Composable
fun MainScreen() {
    val navController = rememberNavController()

    val navItems = listOf(
        NavItem(Screen.Home.route, "Главная", Icons.Default.Home),
        NavItem(Screen.Home.route, "Браузер", Icons.Default.Folder),
        NavItem(Screen.Playlists.route, "Плейлисты", Icons.Default.QueueMusic),
        NavItem(Screen.Search.route, "Поиск", Icons.Default.Search),
        NavItem(Screen.Settings.route, "Настройки", Icons.Default.Settings),
    )

    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDest = currentEntry?.destination

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayerBar()
                NavigationBar {
                    navItems.forEach { item ->
                        val selected = currentDest?.hierarchy
                            ?.any { it.route == item.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
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
