package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TorrentPendingCacheDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(entity: TorrentPendingCacheEntity)

    @Query("DELETE FROM torrent_pending_cache WHERE `key` = :key")
    fun deleteByKey(key: String)

    @Query("SELECT `key` FROM torrent_pending_cache WHERE infoHash = :infoHash")
    fun getKeysForHash(infoHash: String): List<String>

    @Query("DELETE FROM torrent_pending_cache WHERE `key` IN (:keys)")
    fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM torrent_pending_cache")
    fun deleteAll()
}
