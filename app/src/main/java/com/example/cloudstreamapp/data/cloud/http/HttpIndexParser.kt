package com.example.cloudstreamapp.data.cloud.http

import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import org.jsoup.Jsoup
import java.util.UUID

object HttpIndexParser {
    fun parse(html: String, baseUrl: String, sourcePath: CloudPath): List<CloudItem> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a[href]")
            .map { el -> el.attr("abs:href") to el.text().trim() }
            .filter { (href, _) ->
                href.isNotBlank() &&
                        !href.contains("?") &&
                        !href.endsWith("../") &&
                        href != baseUrl
            }
            .map { (href, name) ->
                val displayName = name.ifBlank { href.trimEnd('/').substringAfterLast('/') }
                val isDir = href.endsWith("/")
                CloudItem(
                    id = UUID.nameUUIDFromBytes(href.toByteArray()).toString(),
                    name = displayName,
                    path = CloudPath(
                        sourceId = sourcePath.sourceId,
                        relativePath = href,
                        cloudType = CloudType.HTTP,
                    ),
                    type = if (isDir) CloudItem.ItemType.DIRECTORY else CloudItem.ItemType.FILE,
                    cacheStatus = CacheStatus.REMOTE,
                )
            }
    }
}
