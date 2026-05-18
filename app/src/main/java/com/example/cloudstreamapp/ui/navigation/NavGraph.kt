package com.example.cloudstreamapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.cloudstreamapp.ui.browser.BrowserScreen
import com.example.cloudstreamapp.ui.gallery.ImageGalleryScreen
import com.example.cloudstreamapp.ui.home.HomeScreen
import com.example.cloudstreamapp.ui.player.PlayerScreen
import com.example.cloudstreamapp.ui.playlist.FavoritesScreen
import com.example.cloudstreamapp.ui.playlist.PlaylistDetailScreen
import com.example.cloudstreamapp.ui.playlist.PlaylistsScreen
import com.example.cloudstreamapp.ui.settings.SettingsScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.cloudstreamapp.ui.torrent.TorrentBrowserScreen
import com.example.cloudstreamapp.ui.torrent.downloads.TorrentDownloadsScreen
import com.example.cloudstreamapp.ui.torrent.downloads.TorrentGroupDetailScreen
import com.example.cloudstreamapp.ui.torrent.local.LocalTorrentsScreen

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
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenGallery = { cloudType, sourceUrl, folderPath ->
                    navController.navigate(Screen.ImageGallery.createRoute(cloudType, sourceUrl, folderPath))
                },
            )
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
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenGallery = { cloudType, sourceUrl, folderPath ->
                    navController.navigate(Screen.ImageGallery.createRoute(cloudType, sourceUrl, folderPath))
                },
            )
        }

        composable(
            route = Screen.ImageGallery.route,
            arguments = listOf(
                navArgument("cloudType") { type = NavType.StringType },
                navArgument("encodedSourceUrl") { type = NavType.StringType },
                navArgument("encodedFolderPath") { type = NavType.StringType },
            ),
        ) {
            ImageGalleryScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.NowPlaying.route) {
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenGallery = { cloudType, sourceUrl, folderPath ->
                    navController.navigate(Screen.ImageGallery.createRoute(cloudType, sourceUrl, folderPath))
                },
            )
        }

        composable(Screen.Playlists.route) {
            PlaylistsScreen(
                onPlaylistClick = { id ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(id))
                },
                onFavoritesClick = {
                    navController.navigate(Screen.FavoritePlaylists.route)
                },
            )
        }

        composable(Screen.FavoritePlaylists.route) {
            FavoritesScreen(
                onBack = { navController.popBackStack() },
                onNavigateToPlaylist = { id ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(id))
                },
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
                    ) { launchSingleTop = true }
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
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenGallery = { cloudType, sourceUrl, folderPath ->
                    navController.navigate(Screen.ImageGallery.createRoute(cloudType, sourceUrl, folderPath))
                },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }

        composable(Screen.TorrentBrowser.route) { backStackEntry ->
            // Receive infoHash forwarded back from LocalTorrentsScreen
            val localTorrentToOpen by backStackEntry.savedStateHandle
                .getStateFlow("open_local_torrent_hash", "")
                .collectAsState()

            TorrentBrowserScreen(
                onPlayFile = { item, magnetUri, _ ->
                    navController.navigate(
                        Screen.FolderPlayer.createRoute(
                            cloudType  = "TORRENT",
                            sourceUrl  = magnetUri,
                            folderPath = item.path.relativePath,
                            mediaId    = item.id,
                        )
                    ) { launchSingleTop = true }
                },
                onOpenDownloads = {
                    navController.navigate(Screen.TorrentDownloads.route)
                },
                onOpenLocalTorrents = {
                    navController.navigate(Screen.LocalTorrents.route)
                },
                localTorrentToOpen = localTorrentToOpen,
                onLocalTorrentConsumed = {
                    backStackEntry.savedStateHandle["open_local_torrent_hash"] = ""
                },
            )
        }

        composable(Screen.TorrentDownloads.route) {
            TorrentDownloadsScreen(
                onBack = { navController.popBackStack() },
                onOpenGroup = { torrentName ->
                    navController.navigate(Screen.TorrentGroupDetail.createRoute(torrentName))
                },
                onOpenPlaylist = { playlistId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                },
            )
        }

        composable(Screen.LocalTorrents.route) {
            LocalTorrentsScreen(
                onBack = { navController.popBackStack() },
                onOpenTorrent = { infoHash ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("open_local_torrent_hash", infoHash)
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Screen.TorrentGroupDetail.route,
            arguments = listOf(navArgument("encodedTorrentName") { type = NavType.StringType }),
        ) {
            TorrentGroupDetailScreen(
                onBack = { navController.popBackStack() },
                onPlayTrack = { entity ->
                    // Navigate to FolderPlayer so the whole torrent group is queued.
                    // Compute torrent root by walking up from the file's immediate parent
                    // by the number of folderPath segments (e.g. "Disc1/SubDisc" = 2 levels).
                    val folderDepth = if (entity.folderPath.isEmpty()) 0
                        else entity.folderPath.split("/").count { it.isNotEmpty() }
                    var torrentRoot = java.io.File(entity.localPath).parentFile
                        ?: java.io.File(entity.localPath)
                    repeat(folderDepth) { torrentRoot = torrentRoot.parentFile ?: torrentRoot }
                    navController.navigate(
                        Screen.FolderPlayer.createRoute(
                            cloudType = "LOCAL",
                            sourceUrl = torrentRoot.absolutePath,
                            folderPath = ".",
                            mediaId   = entity.localPath,
                        )
                    ) { launchSingleTop = true }
                },
                onOpenPlaylist = { playlistId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                },
            )
        }
    }
}
