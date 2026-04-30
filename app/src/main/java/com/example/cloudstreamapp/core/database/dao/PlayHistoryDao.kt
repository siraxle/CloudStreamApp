package com.example.cloudstreamapp.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cloudstreamapp.core.database.entity.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT 100")
    fun getRecent(): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history WHERE mediaId = :mediaId ORDER BY playedAt DESC LIMIT 1")
    suspend fun getLastForMedia(mediaId: String): PlayHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE playedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
