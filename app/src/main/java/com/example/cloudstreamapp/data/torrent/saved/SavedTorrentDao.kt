package com.example.cloudstreamapp.data.torrent.saved

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedTorrentDao {

    @Query("SELECT * FROM saved_torrents ORDER BY savedAt DESC")
    fun getAll(): Flow<List<SavedTorrentEntity>>

    @Query("SELECT infoHash FROM saved_torrents")
    fun getAllHashes(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavedTorrentEntity)

    @Query("DELETE FROM saved_torrents WHERE infoHash = :infoHash")
    suspend fun deleteByInfoHash(infoHash: String)
}
