package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks files that were explicitly requested for caching but not yet finished.
 * Inserted when [TorrentCacheManager.startCachingJob] begins, deleted in the job's
 * finally block. [TorrentCacheManager.resumePendingCaching] only resumes files whose
 * keys appear here, preventing boundary-piece spillover from starting caching in
 * neighboring folders.
 */
@Entity(
    tableName = "torrent_pending_cache",
    indices = [Index("infoHash")],
)
data class TorrentPendingCacheEntity(
    @PrimaryKey val key: String,  // "$infoHash:$fileIndex"
    val infoHash: String,
    val fileIndex: Int,
)
