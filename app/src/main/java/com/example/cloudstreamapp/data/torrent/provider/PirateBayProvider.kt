package com.example.cloudstreamapp.data.torrent.provider

import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PirateBayProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.PIRATE_BAY

    override suspend fun search(query: String, page: Int): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://apibay.org/q.php?q=$encodedQuery&cat=0"
            val request = Request.Builder().url(url).build()
            val body = okHttpClient.newCall(request).execute().use { it.body?.string() }
                ?: return@withContext emptyList()

            val arr = runCatching { JSONArray(body) }.getOrNull()
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val infoHash = obj.optString("info_hash", "")
                    // apibay returns a dummy entry with all-zero hash when no results
                    if (infoHash.isBlank() || infoHash.all { it == '0' }) continue
                    val name = obj.optString("name", "Unknown")
                    add(
                        TorrentResult(
                            name = name,
                            magnetUri = buildMagnet(infoHash, name),
                            infoHash = infoHash.lowercase(),
                            sizeBytes = obj.optLong("size", 0L),
                            seeders = obj.optInt("seeders", 0),
                            leechers = obj.optInt("leechers", 0),
                            source = source.displayName,
                        )
                    )
                }
            }
        }
}
