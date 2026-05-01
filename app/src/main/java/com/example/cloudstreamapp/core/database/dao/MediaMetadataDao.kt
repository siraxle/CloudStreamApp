package com.example.cloudstreamapp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cloudstreamapp.core.database.entity.MediaMetadataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaMetadataDao {
    @Query("SELECT * FROM media_metadata WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MediaMetadataEntity?

    @Query("SELECT * FROM media_metadata WHERE sourceId = :sourceId AND path = :path LIMIT 1")
    suspend fun get(sourceId: String, path: String): MediaMetadataEntity?

    @Query("SELECT * FROM media_metadata WHERE title LIKE :query OR artist LIKE :query OR album LIKE :query")
    fun search(query: String): Flow<List<MediaMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: MediaMetadataEntity)

    @Query("UPDATE media_metadata SET sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateSizeBytes(id: String, sizeBytes: Long)

    @Query("DELETE FROM media_metadata WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM media_metadata WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)
}
