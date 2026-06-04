package com.example.cloudstreamapp.domain.torrent

data class TorrentStreamSession(
    val infoHash: String,
    val localUrl: String,
    val bufferProgress: Float,       // 0.0–1.0
    val downloadSpeedBps: Long,
    val seeders: Int,
    val state: SessionState,
) {
    enum class SessionState {
        CONNECTING,
        FETCHING_METADATA,
        BUFFERING,
        READY,
        ERROR,
    }
}
