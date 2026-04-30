package com.example.cloudstreamapp.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Browser : Screen("browser/{sourceId}/{encodedPath}") {
        fun createRoute(sourceId: String, encodedPath: String) =
            "browser/$sourceId/$encodedPath"
    }
    object Player : Screen("player/{mediaId}") {
        fun createRoute(mediaId: String) = "player/$mediaId"
    }
    object Playlists : Screen("playlists")
    object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist/$playlistId"
    }
    object Search : Screen("search")
    object Settings : Screen("settings")
}
