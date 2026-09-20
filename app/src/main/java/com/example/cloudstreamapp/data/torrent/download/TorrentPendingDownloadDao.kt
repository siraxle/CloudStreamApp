package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TorrentPendingDownloadDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TorrentPendingDownloadEntity)

    @Query("DELETE FROM torrent_pending_downloads WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT * FROM torrent_pending_downloads WHERE infoHash = :infoHash")
    suspend fun getForHash(infoHash: String): List<TorrentPendingDownloadEntity>

    @Query("DELETE FROM torrent_pending_downloads")
    suspend fun deleteAll()
}
