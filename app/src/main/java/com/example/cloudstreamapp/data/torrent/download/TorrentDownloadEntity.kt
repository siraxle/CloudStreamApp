package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "torrent_downloads",
    indices = [Index("infoHash")],
)
data class TorrentDownloadEntity(
    @PrimaryKey val id: String,          // "${infoHash}:${fileIndex}"
    val infoHash: String,
    val fileIndex: Int,
    val localPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val torrentName: String,
    val downloadedAt: Long = System.currentTimeMillis(),
)
