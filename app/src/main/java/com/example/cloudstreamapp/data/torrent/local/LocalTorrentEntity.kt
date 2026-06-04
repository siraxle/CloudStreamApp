package com.example.cloudstreamapp.data.torrent.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_torrents")
data class LocalTorrentEntity(
    @PrimaryKey val infoHash: String,
    val torrentName: String,
    val fileName: String,
    val addedAt: Long = System.currentTimeMillis(),
)
