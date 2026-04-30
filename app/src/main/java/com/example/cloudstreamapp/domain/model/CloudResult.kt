package com.example.cloudstreamapp.domain.model

sealed class CloudResult {
    data class FolderResult(
        val path: CloudPath,
        val items: List<CloudItem>
    ) : CloudResult()

    data class FileResult(
        val item: CloudItem,
        val streamUrl: String
    ) : CloudResult()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : CloudResult()
}
