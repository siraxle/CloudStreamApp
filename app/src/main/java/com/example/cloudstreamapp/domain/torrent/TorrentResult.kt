package com.example.cloudstreamapp.domain.torrent

data class TorrentResult(
    val name: String,
    val magnetUri: String,
    val infoHash: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val source: String,
)
