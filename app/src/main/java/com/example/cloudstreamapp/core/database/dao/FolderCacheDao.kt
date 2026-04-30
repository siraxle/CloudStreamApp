package com.example.cloudstreamapp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cloudstreamapp.core.database.entity.FolderCacheEntity

@Dao
interface FolderCacheDao {
    @Query("SELECT * FROM folder_cache WHERE sourceId = :sourceId AND path = :path LIMIT 1")
    suspend fun get(sourceId: String, path: String): FolderCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: FolderCacheEntity)

    @Query("DELETE FROM folder_cache WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query("DELETE FROM folder_cache WHERE cachedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
