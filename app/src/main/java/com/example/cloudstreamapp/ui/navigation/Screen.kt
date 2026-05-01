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
    object Playlists : Screen("playlists")
    object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist/$playlistId"
    }
    object Search : Screen("search")
    object Settings : Screen("settings")
}
