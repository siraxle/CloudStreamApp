package com.example.cloudstreamapp.data.torrent.di

import android.util.Log
import com.example.cloudstreamapp.data.torrent.engine.LibtorrentEngine
import com.example.cloudstreamapp.data.torrent.engine.TorrentHttpServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TorrentModule {

    /**
     * [LibtorrentEngine] carries the global libtorrent session — only one per process.
     * Hilt creates it lazily on first injection and reuses the singleton everywhere.
     */
    @Provides
    @Singleton
    fun provideTorrentHttpServer(engine: LibtorrentEngine): TorrentHttpServer {
        val server = TorrentHttpServer(engine)
        try {
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: Exception) {
            Log.e("TorrentModule", "Failed to start HTTP server on port ${TorrentHttpServer.PORT}", e)
        }
        return server
    }
}
