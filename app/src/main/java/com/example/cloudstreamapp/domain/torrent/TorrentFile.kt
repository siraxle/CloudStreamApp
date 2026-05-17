package com.example.cloudstreamapp.domain.torrent

data class TorrentFile(
    val index: Int,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
)
