package com.example.cloudstreamapp.data.cloud

import com.example.cloudstreamapp.data.cloud.dropbox.DropboxProvider
import com.example.cloudstreamapp.data.cloud.gdrive.GoogleDriveProvider
import com.example.cloudstreamapp.data.cloud.http.HttpIndexProvider
import com.example.cloudstreamapp.data.cloud.local.LocalFileCloudProvider
import com.example.cloudstreamapp.data.cloud.onedrive.OneDriveProvider
import com.example.cloudstreamapp.data.cloud.webdav.WebDavProvider
import com.example.cloudstreamapp.data.cloud.yandex.YandexDiskProvider
import com.example.cloudstreamapp.data.torrent.TorrentCloudProvider
import com.example.cloudstreamapp.domain.model.CloudType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudProviderRegistry @Inject constructor(
    private val gdrive: GoogleDriveProvider,
    private val yandex: YandexDiskProvider,
    private val dropbox: DropboxProvider,
    private val onedrive: OneDriveProvider,
    private val http: HttpIndexProvider,
    private val webdav: WebDavProvider,
    private val torrent: TorrentCloudProvider,
    private val local: LocalFileCloudProvider,
) {
    private val all: List<CloudProvider> = listOf(gdrive, yandex, dropbox, onedrive, http, webdav, torrent, local)

    fun forUrl(url: String): CloudProvider? = all.firstOrNull { it.isSupported(url) }
    fun forType(type: CloudType): CloudProvider? = all.firstOrNull { it.type == type }
    fun all(): List<CloudProvider> = all
}
