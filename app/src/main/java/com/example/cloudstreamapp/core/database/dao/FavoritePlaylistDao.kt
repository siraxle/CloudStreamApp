package com.example.cloudstreamapp.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.example.cloudstreamapp.core.database.entity.FavoritePlaylistEntity
import com.example.cloudstreamapp.core.database.entity.FavoriteTrackEntity
import kotlinx.coroutines.flow.Flow

data class FavoritePlaylistWithTracks(
    @Embedded val playlist: FavoritePlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "favoritePlaylistId",
    )
    val tracks: List<FavoriteTrackEntity>,
)

@Dao
interface FavoritePlaylistDao {

    @Transaction
    @Query("SELECT * FROM favorite_playlists ORDER BY savedAt DESC")
    fun getAllWithTracks(): Flow<List<FavoritePlaylistWithTracks>>

    @Transaction
    @Query("SELECT * FROM favorite_playlists WHERE id = :id")
    suspend fun getById(id: String): FavoritePlaylistWithTracks?

    @Query("SELECT * FROM favorite_playlists WHERE originalPlaylistId = :originalId LIMIT 1")
    suspend fun findByOriginalId(originalId: String): FavoritePlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoritePlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<FavoriteTrackEntity>)

    @Query("DELETE FROM favorite_playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE favorite_playlists SET originalPlaylistId = :newId WHERE id = :favoriteId")
    suspend fun updateOriginalId(favoriteId: String, newId: String?)
}
