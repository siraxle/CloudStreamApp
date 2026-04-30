package com.example.cloudstreamapp.domain.model

data class MediaItem(
    val id: String,
    val sourceId: String,
    val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val mimeType: String?,
    val thumbnailPath: String?,
    val streamUrl: String,
    val cloudType: CloudType,
    val cacheStatus: CacheStatus = CacheStatus.REMOTE,
)
