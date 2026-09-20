package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks files that were explicitly requested for permanent download but not yet
 * finished. Inserted when [TorrentDownloadManager] starts a download job, deleted in
 * the job's finally block. [TorrentDownloadManager.resumePendingDownloads] uses this
 * to continue downloads that were still in progress when the process died, instead of
 * silently forgetting them (which otherwise looks like the download restarting from
 * scratch the next time the user presses Download).
 */
@Entity(
    tableName = "torrent_pending_downloads",
    indices = [Index("infoHash")],
)
data class TorrentPendingDownloadEntity(
    @PrimaryKey val key: String,  // "$infoHash:$fileIndex"
    val infoHash: String,
    val fileIndex: Int,
    val fileName: String,
    val sizeBytes: Long,
    val torrentName: String,
    val folderPath: String = "",
)
