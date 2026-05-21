package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "torrent_cached_files",
    indices = [Index("infoHash")],
)
data class TorrentCachedFileEntity(
    @PrimaryKey val key: String,  // "$infoHash:$fileIndex"
    val infoHash: String,
    val fileIndex: Int,
    val cachedAt: Long = System.currentTimeMillis(),
)
