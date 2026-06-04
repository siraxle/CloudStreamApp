package com.example.cloudstreamapp.domain.model

data class PlaylistExportData(
    val version: Int = 1,
    val name: String,
    val exportedAt: Long,
    val tracks: List<ExportTrack>,
) {
    data class ExportTrack(
        val name: String,
        val sourceId: String,
        val relativePath: String,
        val cloudType: String,
        val sizeBytes: Long?,
        val mimeType: String?,
    )
}
