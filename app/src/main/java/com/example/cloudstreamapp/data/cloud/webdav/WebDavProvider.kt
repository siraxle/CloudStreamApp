package com.example.cloudstreamapp.data.cloud.webdav

import com.example.cloudstreamapp.data.cloud.CloudProvider
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.model.CloudType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.UUID
import javax.inject.Inject

class WebDavProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CloudProvider {

    override val type = CloudType.WEBDAV

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <propfind xmlns="DAV:">
            <prop>
                <displayname/><getcontentlength/><getcontenttype/><resourcetype/>
            </prop>
        </propfind>
    """.trimIndent()

    override fun isSupported(url: String): Boolean =
        url.startsWith("webdav://") || url.startsWith("webdavs://")

    override suspend fun resolve(url: String): CloudResult {
        val httpUrl = url.replace("webdav://", "http://").replace("webdavs://", "https://")
        val path = CloudPath(sourceId = url, relativePath = httpUrl, cloudType = CloudType.WEBDAV)
        return CloudResult.FolderResult(path = path, items = emptyList())
    }

    override fun listFolder(path: CloudPath): Flow<List<CloudItem>> = flow {
        val body = propfindBody.toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url(path.relativePath)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()
        val xml = okHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
        emit(parseWebDavXml(xml, path))
    }

    private fun parseWebDavXml(xml: String, basePath: CloudPath): List<CloudItem> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        return doc.select("response").drop(1).map { response ->
            val href = response.select("href").text()
            val name = response.select("displayname").text().ifBlank {
                href.trimEnd('/').substringAfterLast('/')
            }
            val size = response.select("getcontentlength").text().toLongOrNull()
            val mime = response.select("getcontenttype").text().ifBlank { null }
            val isDir = response.select("resourcetype collection").isNotEmpty()

            CloudItem(
                id = UUID.nameUUIDFromBytes(href.toByteArray()).toString(),
                name = name,
                path = CloudPath(
                    sourceId = basePath.sourceId,
                    relativePath = href,
                    cloudType = CloudType.WEBDAV,
                ),
                type = if (isDir) CloudItem.ItemType.DIRECTORY else CloudItem.ItemType.FILE,
                mimeType = mime,
                sizeBytes = size,
            )
        }
    }

    override suspend fun getStreamUrl(item: CloudItem): String = item.path.relativePath
}
