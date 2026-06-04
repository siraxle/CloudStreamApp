package com.example.cloudstreamapp.data.torrent.saved

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saved_torrents", indices = [Index("savedAt")])
data class SavedTorrentEntity(
    @PrimaryKey val infoHash: String,
    val name: String,
    val magnetUri: String,
    val sizeBytes: Long,
    val seeders: Int,
    val leechers: Int,
    val source: String,
    val savedAt: Long = System.currentTimeMillis(),
)
