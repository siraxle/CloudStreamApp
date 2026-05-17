package com.example.cloudstreamapp.data.torrent

import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.data.torrent.engine.LibtorrentEngine
import com.example.cloudstreamapp.data.torrent.engine.TorrentHttpServer
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorrentCloudProvider @Inject constructor(
    private val engine: LibtorrentEngine,
    private val httpServer: TorrentHttpServer,
) : CloudProvider {

    override val type: CloudType = CloudType.TORRENT

    override fun isSupported(url: String): Boolean =
        url.startsWith("magnet:?") || url.startsWith("magnet:/?")

    /**
     * Resolves a magnet URI — adds the torrent to the engine, waits for metadata,
     * and returns a [CloudResult.FolderResult] whose items are the audio files.
     * Single-audio-file torrents are also returned as a folder (consistent UX).
     */
    override suspend fun resolve(url: String): CloudResult = try {
        val infoHash = engine.addMagnet(url)
        val path = CloudPath(sourceId = url, relativePath = infoHash, cloudType = CloudType.TORRENT)
        val items = buildItems(infoHash, path)
        CloudResult.FolderResult(path = path, items = items)
    } catch (e: Exception) {
        CloudResult.Error(
            message = e.message ?: "Could not connect to peers",
            cause = e,
        )
    }

    /**
     * Lists audio files inside a torrent.
     * [path.relativePath] = infoHash (set by [resolve]).
     * [path.sourceId]     = original magnet URI.
     *
     * If show-all-files filter is disabled in the future this is where to change it.
     */
    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        val infoHash = path.relativePath
        emit(buildItems(infoHash, path))
    }.flowOn(Dispatchers.IO)

    /**
     * Returns the local HTTP URL served by [TorrentHttpServer].
     * The item.id format is "{infoHash}:{fileIndex}" (set in [buildItems]).
     */
    override suspend fun getStreamUrl(item: CloudItem): String {
        val (infoHash, fileIndex) = item.id.split(":").let { it[0] to it[1].toInt() }
        return "http://127.0.0.1:${TorrentHttpServer.PORT}/stream/$infoHash/$fileIndex"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildItems(infoHash: String, path: CloudPath): List<CloudItem> =
        engine.listFiles(infoHash)
            .filter { it.name.isAudioFile() }
            .map { file ->
                CloudItem(
                    id          = "$infoHash:${file.index}",
                    name        = file.name,
                    path        = path,
                    type        = CloudItem.ItemType.FILE,
                    mimeType    = mimeFor(file.name),
                    sizeBytes   = file.sizeBytes,
                    cacheStatus = CacheStatus.REMOTE,
                )
            }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp3"  -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac"  -> "audio/aac"
        "ogg"  -> "audio/ogg"
        "opus" -> "audio/ogg; codecs=opus"
        "m4a"  -> "audio/mp4"
        "wav"  -> "audio/wav"
        else   -> "audio/mpeg"
    }
}
