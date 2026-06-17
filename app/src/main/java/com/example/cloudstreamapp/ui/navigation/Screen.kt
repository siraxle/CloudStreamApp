package com.example.cloudstreamapp.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Browser : Screen("browser/{sourceId}/{encodedPath}") {
        fun createRoute(sourceId: String, encodedPath: String) =
            "browser/$sourceId/$encodedPath"
    }
    object Player : Screen(
        "player/{cloudType}/{encodedSourceUrl}/{encodedItemPath}/{encodedItemName}/{encodedMediaId}"
    ) {
        fun createRoute(
            cloudType: String,
            sourceUrl: String,
            itemPath: String,
            itemName: String,
            mediaId: String,
        ): String = "player/$cloudType/${Uri.encode(sourceUrl)}/${Uri.encode(itemPath)}/${Uri.encode(itemName)}/${Uri.encode(mediaId)}"
    }
    object PlaylistPlayer : Screen("player/playlist/{playlistId}/{startIndex}") {
        fun createRoute(playlistId: String, startIndex: Int) =
            "player/playlist/$playlistId/$startIndex"
    }
    // Folder browsing mode: loads all media files in the folder as a queue
    object FolderPlayer : Screen(
        "player/folder/{cloudType}/{encodedSourceUrl}/{encodedFolderPath}/{encodedMediaId}"
    ) {
        fun createRoute(
            cloudType: String,
            sourceUrl: String,
            folderPath: String,
            mediaId: String,
        ): String = "player/folder/$cloudType/${Uri.encode(sourceUrl)}/${Uri.encode(folderPath)}/${Uri.encode(mediaId)}"
    }
    object CuePlayer : Screen(
        "player/cue/{cloudType}/{encodedSourceUrl}/{encodedFolderPath}/{encodedCueItemId}"
    ) {
        fun createRoute(
            cloudType: String,
            sourceUrl: String,
            folderPath: String,
            cueItemId: String,
        ): String = "player/cue/$cloudType/${Uri.encode(sourceUrl)}/${Uri.encode(folderPath)}/${Uri.encode(cueItemId)}"
    }
    object NowPlaying : Screen("player/now_playing")
    object Playlists : Screen("playlists")
    object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist/$playlistId"
    }
    object FavoritePlaylists : Screen("favorites")
    object Settings : Screen("settings")
    object ImageGallery : Screen("gallery/{cloudType}/{encodedSourceUrl}/{encodedFolderPath}") {
        fun createRoute(cloudType: String, sourceUrl: String, folderPath: String): String =
            "gallery/$cloudType/${Uri.encode(sourceUrl)}/${Uri.encode(folderPath)}"
    }
    object TorrentBrowser : Screen("torrent")
    object TorrentDownloads : Screen("torrent/downloads")
    object TorrentGroupDetail : Screen("torrent/downloads/{encodedTorrentName}") {
        fun createRoute(torrentName: String) = "torrent/downloads/${Uri.encode(torrentName)}"
    }
    object LocalTorrents : Screen("torrent/local")
    object SavedTorrents : Screen("torrent/saved")
}
