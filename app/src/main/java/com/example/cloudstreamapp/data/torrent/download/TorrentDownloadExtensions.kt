package com.example.cloudstreamapp.data.torrent.download

import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType

/**
 * Converts a completed download record to a [CloudItem] backed by [CloudType.LOCAL].
 * [CloudPath.sourceId] = absolute path to the local file on disk.
 * [CloudItem.id] uses "local:" prefix to avoid PK collision with torrent-stream entries.
 */
fun TorrentDownloadEntity.toCloudItem(): CloudItem = CloudItem(
    id = "local:$infoHash:$fileIndex",
    name = fileName,
    path = CloudPath(
        sourceId = localPath,
        relativePath = fileName,
        cloudType = CloudType.LOCAL,
    ),
    type = CloudItem.ItemType.FILE,
    sizeBytes = sizeBytes,
    cacheStatus = CacheStatus.CACHED,
)
