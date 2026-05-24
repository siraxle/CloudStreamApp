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
import com.example.cloudstreamapp.data.torrent.download.TorrentCachedFileDao
import com.example.cloudstreamapp.data.torrent.download.TorrentCachedFileEntity
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadDao
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadEntity
import com.example.cloudstreamapp.data.torrent.download.TorrentPendingCacheDao
import com.example.cloudstreamapp.data.torrent.download.TorrentPendingCacheEntity
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentDao
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentEntity
import com.example.cloudstreamapp.data.torrent.saved.SavedTorrentDao
import com.example.cloudstreamapp.data.torrent.saved.SavedTorrentEntity

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
        TorrentDownloadEntity::class,
        TorrentCachedFileEntity::class,
        TorrentPendingCacheEntity::class,
        LocalTorrentEntity::class,
        SavedTorrentEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun folderCacheDao(): FolderCacheDao
    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoritePlaylistDao(): FavoritePlaylistDao
    abstract fun torrentDownloadDao(): TorrentDownloadDao
    abstract fun torrentCachedFileDao(): TorrentCachedFileDao
    abstract fun torrentPendingCacheDao(): TorrentPendingCacheDao
    abstract fun localTorrentDao(): LocalTorrentDao
    abstract fun savedTorrentDao(): SavedTorrentDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS torrent_downloads (
                        id TEXT NOT NULL PRIMARY KEY,
                        infoHash TEXT NOT NULL,
                        fileIndex INTEGER NOT NULL,
                        localPath TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        torrentName TEXT NOT NULL,
                        downloadedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_torrent_downloads_infoHash ON torrent_downloads(infoHash)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE torrent_downloads ADD COLUMN folderPath TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_torrents (
                        infoHash TEXT NOT NULL PRIMARY KEY,
                        torrentName TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_torrents (
                        infoHash TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        magnetUri TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        seeders INTEGER NOT NULL,
                        leechers INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        savedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_saved_torrents_savedAt ON saved_torrents(savedAt)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS torrent_cached_files (
                        key TEXT NOT NULL PRIMARY KEY,
                        infoHash TEXT NOT NULL,
                        fileIndex INTEGER NOT NULL,
                        cachedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_torrent_cached_files_infoHash ON torrent_cached_files(infoHash)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS torrent_pending_cache (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        infoHash TEXT NOT NULL,
                        fileIndex INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_torrent_pending_cache_infoHash ON torrent_pending_cache(infoHash)"
                )
            }
        }
    }
}
