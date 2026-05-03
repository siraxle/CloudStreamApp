package com.example.cloudstreamapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.cloudstreamapp.ui.browser.BrowserScreen
import com.example.cloudstreamapp.ui.home.HomeScreen
import com.example.cloudstreamapp.ui.player.PlayerScreen
import com.example.cloudstreamapp.ui.playlist.PlaylistDetailScreen
import com.example.cloudstreamapp.ui.playlist.PlaylistsScreen
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
                onPlayMedia = { item, folderPath ->
                    navController.navigate(
                        Screen.FolderPlayer.createRoute(
                            cloudType = item.path.cloudType.name,
                            sourceUrl = item.path.sourceId,
                            folderPath = folderPath,
                            mediaId = item.id,
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
                navArgument("encodedMediaId") { type = NavType.StringType },
            ),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screen.FolderPlayer.route,
            arguments = listOf(
                navArgument("cloudType") { type = NavType.StringType },
                navArgument("encodedSourceUrl") { type = NavType.StringType },
                navArgument("encodedFolderPath") { type = NavType.StringType },
                navArgument("encodedMediaId") { type = NavType.StringType },
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
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
            PlaylistDetailScreen(
                onBack = { navController.popBackStack() },
                onPlayTrack = { startIndex ->
                    navController.navigate(
                        Screen.PlaylistPlayer.createRoute(playlistId, startIndex)
                    )
                },
            )
        }

        composable(
            route = Screen.PlaylistPlayer.route,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("startIndex") { type = NavType.IntType },
            ),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}
