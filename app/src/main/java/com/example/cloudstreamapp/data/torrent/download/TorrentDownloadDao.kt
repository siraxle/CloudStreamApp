package com.example.cloudstreamapp.data.torrent.download

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TorrentDownloadDao {

    @Query("SELECT * FROM torrent_downloads ORDER BY downloadedAt DESC")
    fun getAll(): Flow<List<TorrentDownloadEntity>>

    @Query("SELECT * FROM torrent_downloads WHERE id = :id")
    suspend fun findById(id: String): TorrentDownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TorrentDownloadEntity)

    @Delete
    suspend fun delete(entity: TorrentDownloadEntity)

    @Query("DELETE FROM torrent_downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
