package com.example.cloudstreamapp.data.torrent

import com.example.cloudstreamapp.core.utils.isAudioFile
import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.data.torrent.engine.LibtorrentEngine
import com.example.cloudstreamapp.data.torrent.engine.TorrentHttpServer
import com.example.cloudstreamapp.data.torrent.provider.extractInfoHash
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.torrent.TorrentFile
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
        engine.isAvailable && (url.startsWith("magnet:?") || url.startsWith("magnet:/?"))

    /**
     * Resolves a magnet URI — fetches metadata, starts download, and returns a
     * [CloudResult.FolderResult] with root-level items (folders and/or audio files).
     */
    override suspend fun resolve(url: String): CloudResult = try {
        val infoHash = engine.addMagnet(url)
        val path = CloudPath(sourceId = url, relativePath = infoHash, cloudType = CloudType.TORRENT)
        val items = listFolderItems(infoHash, "", url)
        CloudResult.FolderResult(path = path, items = items)
    } catch (e: Exception) {
        CloudResult.Error(
            message = e.message ?: "Could not connect to peers",
            cause = e,
        )
    }

    /**
     * Lists items inside a torrent folder.
     * [path.sourceId]     = original magnet URI
     * [path.relativePath] = folder path within the torrent ("" = root, "Album/Disc1" = subfolder)
     */
    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        val sourceId = path.sourceId
        // sourceId is either a magnet: URI or "torrent:{infoHash}" (loaded from .torrent file)
        val infoHash = extractInfoHash(sourceId)
            ?: if (sourceId.startsWith("torrent:")) sourceId.removePrefix("torrent:")
               else path.relativePath
        val folderPath = if (path.relativePath == infoHash) "" else path.relativePath
        emit(listFolderItems(infoHash, folderPath, sourceId))
    }.flowOn(Dispatchers.IO)

    override suspend fun getStreamUrl(item: CloudItem): String {
        val parts = item.id.split(":")
        val infoHash = parts[0]
        val fileIndex = parts[1].toInt()
        return "http://127.0.0.1:${TorrentHttpServer.PORT}/stream/$infoHash/$fileIndex"
    }

    /**
     * Loads a .torrent file from [bytes], starts download, and returns a
     * [CloudResult.FolderResult] with root-level items. No DHT fetch needed.
     */
    suspend fun resolveTorrentBytes(bytes: ByteArray, fileName: String): CloudResult = try {
        val infoHash = engine.addTorrentBytes(bytes)
        val sourceId = "torrent:$infoHash"
        val path = CloudPath(sourceId = sourceId, relativePath = infoHash, cloudType = CloudType.TORRENT)
        val items = listFolderItems(infoHash, "", sourceId)
        CloudResult.FolderResult(path = path, items = items)
    } catch (e: Exception) {
        CloudResult.Error(e.message ?: "Could not read torrent file", cause = e)
    }

    /**
     * Returns directory entries and audio files at [folderPath] within the torrent.
     * Directories are listed first (sorted), followed by audio files (sorted).
     */
    fun listFolderItems(infoHash: String, folderPath: String, magnetUri: String): List<CloudItem> =
        buildFolderItems(engine.listFiles(infoHash), folderPath, infoHash, magnetUri)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildFolderItems(
        files: List<TorrentFile>,
        folderPath: String,
        infoHash: String,
        magnetUri: String,
    ): List<CloudItem> {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        val audioFiles = files.filter { it.name.isAudioFile() }

        val seenDirs = linkedSetOf<String>()
        val dirs = mutableListOf<CloudItem>()
        val fileItems = mutableListOf<CloudItem>()

        for (file in audioFiles) {
            if (prefix.isNotEmpty() && !file.relativePath.startsWith(prefix)) continue

            val remaining = if (prefix.isEmpty()) file.relativePath
                            else file.relativePath.removePrefix(prefix)
            val slashIdx = remaining.indexOf('/')

            if (slashIdx < 0) {
                // Direct audio file in this folder.
                // relativePath = folderPath so PlayerViewModel's listFolder call
                // lands back in this same subfolder (not the torrent root).
                fileItems.add(
                    CloudItem(
                        id = "$infoHash:${file.index}",
                        name = remaining,
                        path = CloudPath(sourceId = magnetUri, relativePath = folderPath, cloudType = CloudType.TORRENT),
                        type = CloudItem.ItemType.FILE,
                        mimeType = mimeFor(remaining),
                        sizeBytes = file.sizeBytes,
                        cacheStatus = CacheStatus.REMOTE,
                    )
                )
            } else {
                // File lives in a subdirectory — emit a directory entry
                val dirName = remaining.substring(0, slashIdx)
                val dirFullPath = if (folderPath.isEmpty()) dirName else "$folderPath/$dirName"
                if (seenDirs.add(dirFullPath)) {
                    dirs.add(
                        CloudItem(
                            id = "dir:$infoHash:$dirFullPath",
                            name = dirName,
                            path = CloudPath(
                                sourceId = magnetUri,
                                relativePath = dirFullPath,
                                cloudType = CloudType.TORRENT,
                            ),
                            type = CloudItem.ItemType.DIRECTORY,
                            sizeBytes = null,
                            cacheStatus = CacheStatus.REMOTE,
                        )
                    )
                }
            }
        }

        return dirs.sortedBy { it.name.lowercase() } + fileItems.sortedBy { it.name.lowercase() }
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
