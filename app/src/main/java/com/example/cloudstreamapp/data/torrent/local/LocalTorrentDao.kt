package com.example.cloudstreamapp.data.torrent.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalTorrentDao {

    @Query("SELECT * FROM local_torrents ORDER BY addedAt DESC")
    fun getAll(): Flow<List<LocalTorrentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalTorrentEntity)

    @Query("DELETE FROM local_torrents WHERE infoHash = :infoHash")
    suspend fun deleteByInfoHash(infoHash: String)

    @Query("SELECT * FROM local_torrents WHERE infoHash = :infoHash LIMIT 1")
    suspend fun findByInfoHash(infoHash: String): LocalTorrentEntity?
}
