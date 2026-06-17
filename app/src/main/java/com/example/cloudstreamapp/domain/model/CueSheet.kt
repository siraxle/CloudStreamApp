package com.example.cloudstreamapp.domain.model

data class CueSheet(
    val title: String? = null,
    val performer: String? = null,
    val audioFileName: String,
    val tracks: List<CueTrack>,
)

data class CueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val startMs: Long,
)
