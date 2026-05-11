package com.example.cloudstreamapp.core.cache

import android.content.Context
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @PermanentMediaCache
    @Provides
    @Singleton
    fun providePermanentCache(manager: MediaCacheManager): SimpleCache = manager.permanentCache

    @TempMediaCache
    @Provides
    @Singleton
    fun provideTempCache(manager: MediaCacheManager): SimpleCache = manager.tempCache

    // Unqualified binding → permanent cache so workers need no annotation changes.
    @Provides
    @Singleton
    fun provideSimpleCache(@PermanentMediaCache cache: SimpleCache): SimpleCache = cache

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
