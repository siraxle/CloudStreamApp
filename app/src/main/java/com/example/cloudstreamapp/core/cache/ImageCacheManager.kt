package com.example.cloudstreamapp.core.cache

import android.content.Context
import coil.imageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun clearAll() {
        context.imageLoader.diskCache?.clear()
        context.imageLoader.memoryCache?.clear()
    }
}
