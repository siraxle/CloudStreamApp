package com.example.cloudstreamapp.domain.model

data class CloudPath(
    val sourceId: String,
    val relativePath: String,
    val cloudType: CloudType
)
