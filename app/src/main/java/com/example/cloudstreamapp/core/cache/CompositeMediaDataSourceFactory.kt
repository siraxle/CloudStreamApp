package com.example.cloudstreamapp.core.cache

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource

/**
 * Routes playback requests to the right cache:
 *   URI host == "offline.cache"  →  permanentCache  (no network; serves downloaded tracks)
 *   everything else              →  tempCache        (streams from network, writes to temp buffer)
 */
class CompositeMediaDataSourceFactory(
    private val permanentCacheFactory: CacheDataSource.Factory,
    private val tempCacheFactory: CacheDataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = CompositeMediaDataSource(
        permanentCacheFactory.createDataSource(),
        tempCacheFactory.createDataSource(),
    )
}

private class CompositeMediaDataSource(
    private val permanentSource: CacheDataSource,
    private val tempSource: CacheDataSource,
) : DataSource {

    private var activeSource: DataSource = tempSource

    override fun addTransferListener(transferListener: TransferListener) {
        permanentSource.addTransferListener(transferListener)
        tempSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        activeSource = if (dataSpec.uri.host == "offline.cache") permanentSource else tempSource
        return activeSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        activeSource.read(buffer, offset, length)

    override fun getUri(): Uri? = activeSource.uri

    override fun getResponseHeaders(): Map<String, List<String>> = activeSource.responseHeaders

    override fun close() = activeSource.close()
}
