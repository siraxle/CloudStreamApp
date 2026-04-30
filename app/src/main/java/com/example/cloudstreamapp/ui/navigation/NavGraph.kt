package com.example.cloudstreamapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.ui.browser.BrowserScreen
import com.example.cloudstreamapp.ui.home.HomeScreen
import com.example.cloudstreamapp.ui.player.PlayerScreen
import com.example.cloudstreamapp.ui.playlist.PlaylistDetailScreen
import com.example.cloudstreamapp.ui.playlist.PlaylistsScreen
import com.example.cloudstreamapp.ui.search.SearchScreen
import com.example.cloudstreamapp.ui.settings.SettingsScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
    ) {

        composable(Screen.Home.route) {
            HomeScreen(
                onSourceClick = { sourceId ->
                    navController.navigate(Screen.Browser.createRoute(sourceId, "root"))
                }
            )
        }

        composable(
            route = Screen.Browser.route,
            arguments = listOf(
                navArgument("sourceId") { type = NavType.StringType },
                navArgument("encodedPath") { type = NavType.StringType },
            )
        ) {
            BrowserScreen(
                onNavigateToFolder = { sourceId, path ->
                    navController.navigate(Screen.Browser.createRoute(sourceId, path))
                },
                onPlayMedia = { item ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            cloudType = item.path.cloudType.name,
                            sourceUrl = item.path.sourceId,
                            itemPath = item.path.relativePath,
                            itemName = item.name,
                        )
                    ) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("cloudType") { type = NavType.StringType },
                navArgument("encodedSourceUrl") { type = NavType.StringType },
                navArgument("encodedItemPath") { type = NavType.StringType },
                navArgument("encodedItemName") { type = NavType.StringType },
            ),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                onPlaylistClick = { id ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(id))
                }
            )
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) {
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onPlayItem = { item ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            cloudType = item.path.cloudType.name,
                            sourceUrl = item.path.sourceId,
                            itemPath = item.path.relativePath,
                            itemName = item.name,
                        )
                    ) { launchSingleTop = true }
                },
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                onPlayMedia = { item ->
                    navController.navigate(
                        Screen.Player.createRoute(
                            cloudType = item.path.cloudType.name,
                            sourceUrl = item.path.sourceId,
                            itemPath = item.path.relativePath,
                            itemName = item.name,
                        )
                    ) { launchSingleTop = true }
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}
