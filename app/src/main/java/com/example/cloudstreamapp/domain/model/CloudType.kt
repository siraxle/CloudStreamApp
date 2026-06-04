package com.example.cloudstreamapp.domain.model

enum class CloudType {
    GDRIVE,
    YANDEX,
    DROPBOX,
    ONEDRIVE,
    HTTP,
    WEBDAV,
    TORRENT,
    LOCAL,    // offline-downloaded files; sourceId = absolute file path
}
