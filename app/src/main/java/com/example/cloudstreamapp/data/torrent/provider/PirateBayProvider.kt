package com.example.cloudstreamapp.data.torrent.provider

import android.util.Log
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PirateBayProvider @Inject constructor() : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.PIRATE_BAY

    // Dedicated client — short timeout, no RetryInterceptor.
    // RetryInterceptor on blocked domains burns 40+ seconds (4 attempts × 10s timeout).
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String, page: Int, category: ContentCategory): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val cat = if (category == ContentCategory.AUDIO) 100 else 0
            val path = "/q.php?q=$encodedQuery&cat=$cat"
            val body = fetchJson(path) ?: return@withContext emptyList()

            val arr = runCatching { JSONArray(body) }.getOrNull()
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val infoHash = obj.optString("info_hash", "")
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

    private fun fetchJson(path: String): String? {
        for (base in BASE_URLS) {
            val result = runCatching {
                httpClient.newCall(
                    Request.Builder()
                        .url("$base$path")
                        .header("User-Agent", USER_AGENT)
                        .build()
                ).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrElse { e ->
                Log.d(TAG, "fetch failed for $base: ${e.message}")
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        private const val TAG = "PirateBayProvider"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        private val BASE_URLS = listOf(
            "https://apibay.org",
            "https://pirateproxy.live/apibay",
        )
    }
}
