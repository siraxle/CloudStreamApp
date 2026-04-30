package com.example.cloudstreamapp.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.cloudstreamapp.core.database.dao.FolderCacheDao
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.core.database.dao.PlayHistoryDao
import com.example.cloudstreamapp.core.database.dao.PlaylistDao
import com.example.cloudstreamapp.core.database.dao.SourceDao
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
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun folderCacheDao(): FolderCacheDao
    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE media_metadata ADD COLUMN cloudType TEXT NOT NULL DEFAULT 'HTTP'"
                )
            }
        }
    }
}
