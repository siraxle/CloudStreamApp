package com.example.cloudstreamapp.domain.model

data class PlaylistBundleData(
    val version: Int = 2,
    val exportedAt: Long,
    val playlists: List<PlaylistEntry>,
) {
    data class PlaylistEntry(
        val name: String,
        val tracks: List<PlaylistExportData.ExportTrack>,
    )
}
