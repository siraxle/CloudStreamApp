package com.example.cloudstreamapp.domain.torrent

sealed class CacheProgress {
    data class Caching(val fraction: Float) : CacheProgress()
    object Cached : CacheProgress()
}
