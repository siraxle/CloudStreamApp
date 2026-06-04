package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TorrentCachedFileDao {

    @Query("SELECT key FROM torrent_cached_files WHERE infoHash = :infoHash")
    fun getKeysForHash(infoHash: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: TorrentCachedFileEntity)

    @Query("DELETE FROM torrent_cached_files WHERE key IN (:keys)")
    fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM torrent_cached_files WHERE infoHash = :infoHash")
    fun deleteByInfoHash(infoHash: String)

    @Query("DELETE FROM torrent_cached_files")
    fun deleteAll()
}
