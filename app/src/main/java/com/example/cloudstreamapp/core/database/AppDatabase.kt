package com.example.cloudstreamapp.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.cloudstreamapp.core.database.dao.FavoritePlaylistDao
import com.example.cloudstreamapp.core.database.dao.FolderCacheDao
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.core.database.dao.PlayHistoryDao
import com.example.cloudstreamapp.core.database.dao.PlaylistDao
import com.example.cloudstreamapp.core.database.dao.SourceDao
import com.example.cloudstreamapp.core.database.entity.FavoritePlaylistEntity
import com.example.cloudstreamapp.core.database.entity.FavoriteTrackEntity
import com.example.cloudstreamapp.core.database.entity.FolderCacheEntity
import com.example.cloudstreamapp.core.database.entity.MediaMetadataEntity
import com.example.cloudstreamapp.core.database.entity.PlayHistoryEntity
import com.example.cloudstreamapp.core.database.entity.PlaylistEntity
import com.example.cloudstreamapp.core.database.entity.PlaylistItemEntity
import com.example.cloudstreamapp.core.database.entity.SourceEntity

@Database(
    entities = [
        SourceEntity::class,
        FolderCacheEntity::class,
        MediaMetadataEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        PlayHistoryEntity::class,
        FavoritePlaylistEntity::class,
        FavoriteTrackEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun folderCacheDao(): FolderCacheDao
    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoritePlaylistDao(): FavoritePlaylistDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_metadata ADD COLUMN cloudType TEXT NOT NULL DEFAULT 'HTTP'"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorite_playlists (
                        id TEXT NOT NULL PRIMARY KEY,
                        originalPlaylistId TEXT,
                        name TEXT NOT NULL,
                        savedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS favorite_tracks (
                        id TEXT NOT NULL PRIMARY KEY,
                        favoritePlaylistId TEXT NOT NULL,
                        mediaId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        cloudType TEXT NOT NULL,
                        sizeBytes INTEGER,
                        mimeType TEXT,
                        position INTEGER NOT NULL,
                        FOREIGN KEY(favoritePlaylistId) REFERENCES favorite_playlists(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_favorite_tracks_favoritePlaylistId ON favorite_tracks(favoritePlaylistId)"
                )
            }
        }
    }
}
