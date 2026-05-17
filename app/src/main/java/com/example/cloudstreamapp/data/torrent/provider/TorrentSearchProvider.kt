package com.example.cloudstreamapp.data.torrent.provider

import com.example.cloudstreamapp.domain.torrent.TorrentResult

interface TorrentSearchProvider {
    val source: TorrentSource
    suspend fun search(query: String, page: Int = 1): List<TorrentResult>
}
