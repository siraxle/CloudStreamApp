package com.example.cloudstreamapp.data.torrent.provider

enum class TorrentSource(val displayName: String, val requiresAuth: Boolean = false) {
    PIRATE_BAY("TPB"),
    NYAA("Nyaa"),
    X1337("1337x"),
    TORRENTZ2("Torrentz2"),
    RUTRACKER("RuTracker", requiresAuth = true),
}
