package com.example.cloudstreamapp.core.database

import android.content.Context
import androidx.room.Room
import com.example.cloudstreamapp.core.database.dao.FavoritePlaylistDao
import com.example.cloudstreamapp.core.database.dao.FolderCacheDao
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.core.database.dao.PlayHistoryDao
import com.example.cloudstreamapp.core.database.dao.PlaylistDao
import com.example.cloudstreamapp.core.database.dao.SourceDao
import com.example.cloudstreamapp.data.torrent.download.TorrentDownloadDao
import com.example.cloudstreamapp.data.torrent.local.LocalTorrentDao
import com.example.cloudstreamapp.data.torrent.saved.SavedTorrentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "cloudstream.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()

    @Provides
    fun provideSourceDao(db: AppDatabase): SourceDao = db.sourceDao()

    @Provides
    fun provideFolderCacheDao(db: AppDatabase): FolderCacheDao = db.folderCacheDao()

    @Provides
    fun provideMediaMetadataDao(db: AppDatabase): MediaMetadataDao = db.mediaMetadataDao()

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun providePlayHistoryDao(db: AppDatabase): PlayHistoryDao = db.playHistoryDao()

    @Provides
    fun provideFavoritePlaylistDao(db: AppDatabase): FavoritePlaylistDao = db.favoritePlaylistDao()

    @Provides
    fun provideTorrentDownloadDao(db: AppDatabase): TorrentDownloadDao = db.torrentDownloadDao()

    @Provides
    fun provideLocalTorrentDao(db: AppDatabase): LocalTorrentDao = db.localTorrentDao()

    @Provides
    fun provideSavedTorrentDao(db: AppDatabase): SavedTorrentDao = db.savedTorrentDao()
}
