package com.example.cloudstreamapp.domain.torrent

sealed class DownloadProgress {
    object Queued : DownloadProgress()
    data class Downloading(val fraction: Float) : DownloadProgress()
    data class Done(val localPath: String) : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
}
