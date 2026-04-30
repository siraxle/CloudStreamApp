package com.example.cloudstreamapp.domain.model

data class CloudSource(
    val id: String,
    val url: String,
    val name: String?,
    val provider: CloudType,
    val addedAt: Long,
    val lastSync: Long?,
    val isPinned: Boolean = false,
)
