package com.example.cloudstreamapp.domain.model

data class CloudItem(
    val id: String,
    val name: String,
    val path: CloudPath,
    val type: ItemType,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val cacheStatus: CacheStatus = CacheStatus.REMOTE,
) {
    enum class ItemType { FILE, DIRECTORY }
}
