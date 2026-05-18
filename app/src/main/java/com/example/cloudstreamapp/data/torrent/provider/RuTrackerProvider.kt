package com.example.cloudstreamapp.data.torrent.provider

import com.example.cloudstreamapp.data.torrent.auth.TorrentAuthStore
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuTrackerProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authStore: TorrentAuthStore,
) : TorrentSearchProvider {

    override val source: TorrentSource = TorrentSource.RUTRACKER

    private val baseUrl = "https://rutracker.org/forum"
    private val sessionMutex = Mutex()
    @Volatile private var sessionInitialized = false

    // Separate client with tight timeouts: RuTracker may be geo-blocked or slow,
    // and we don't want one failing source to block the entire parallel search for 10s+.
    private val client: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val ACCEPT_LANG = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
    }

    override suspend fun search(query: String, page: Int, category: ContentCategory): List<TorrentResult> =
        withContext(Dispatchers.IO) {
            ensureSession()
            // If login failed (e.g. geo-block / timeout), don't waste another timeout on the search call.
            if (!sessionInitialized) return@withContext emptyList()

            val start = (page - 1) * 50
            val cp1251 = Charset.forName("windows-1251")
            val formBody = FormBody.Builder(cp1251)
                .add("nm", query)
                .add("start", start.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/tracker.php")
                .post(formBody)
                .header("User-Agent", UA)
                .header("Accept", ACCEPT)
                .header("Accept-Language", ACCEPT_LANG)
                .header("Referer", "$baseUrl/tracker.php")
                .build()

            val html = runCatching {
                client.newCall(request).execute().use { resp ->
                    // Redirect to login.php means the session is not valid
                    if (resp.request.url.toString().contains("login.php")) {
                        sessionInitialized = false
                        return@use null
                    }
                    resp.body?.bytes()?.toString(cp1251)
                }
            }.getOrNull() ?: return@withContext emptyList()

            val doc = Jsoup.parse(html, baseUrl)
            // Anchor up from each title link to its <tr> — resilient to table structure changes.
            // RuTracker uses <a class="tLink" href="viewtopic.php?t=…"> for every result title.
            doc.select("a.tLink[href~=viewtopic\\.php]").mapNotNull { link ->
                val name = link.text().ifBlank { return@mapNotNull null }
                val topicUrl = link.absUrl("href").ifBlank { return@mapNotNull null }
                val row = link.parents().firstOrNull { it.tagName() == "tr" }
                    ?: return@mapNotNull null

                val sizeStr = row.selectFirst("td.tor-size u")?.text() ?: "0"
                val seeders = row.selectFirst("td.seedmed b")?.text()?.toIntOrNull() ?: 0
                val leechers = row.selectFirst("td.leechmed b")?.text()?.toIntOrNull() ?: 0

                TorrentResult(
                    name = name,
                    magnetUri = topicUrl,
                    infoHash = "",
                    sizeBytes = parseSizeBytes(sizeStr),
                    seeders = seeders,
                    leechers = leechers,
                    source = source.displayName,
                )
            }
        }

    /** Fetches the topic page and extracts the magnet link. */
    suspend fun resolveMagnet(topicUrl: String): String? = withContext(Dispatchers.IO) {
        ensureSession()
        if (!sessionInitialized) return@withContext null
        val cp1251 = Charset.forName("windows-1251")
        val html = runCatching {
            val req = Request.Builder()
                .url(topicUrl)
                .header("User-Agent", UA)
                .header("Accept", ACCEPT)
                .header("Accept-Language", ACCEPT_LANG)
                .header("Referer", "$baseUrl/tracker.php")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.request.url.toString().contains("login.php")) {
                    sessionInitialized = false
                    return@use null
                }
                resp.body?.bytes()?.toString(cp1251)
            }
        }.getOrNull() ?: return@withContext null
        Jsoup.parse(html).selectFirst("a.magnet-link")?.attr("href")
    }

    /**
     * Performs a full login to RuTracker. On success the OkHttpClient cookie jar
     * will hold the session cookie for subsequent requests.
     *
     * RuTracker's charset is Windows-1251 — Cyrillic must be encoded in cp1251,
     * not UTF-8, or the server won't recognise the credentials.
     */
    suspend fun login(username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cp1251 = Charset.forName("windows-1251")
                val formBody = FormBody.Builder(cp1251)
                    .add("login_username", username)
                    .add("login_password", password)
                    .add("login", "Вход")
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/login.php")
                    .post(formBody)
                    .header("User-Agent", UA)
                    .header("Accept", ACCEPT)
                    .header("Accept-Language", ACCEPT_LANG)
                    .header("Referer", "$baseUrl/login.php")
                    .header("Origin", "https://rutracker.org")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Сервер вернул ${response.code}")
                    }
                    // After a successful login RuTracker redirects away from login.php.
                    // If the final URL still points to login.php the credentials were wrong.
                    val finalUrl = response.request.url.toString()
                    if (finalUrl.contains("login.php")) {
                        error("Неверное имя пользователя или пароль")
                    }
                }

                sessionInitialized = true
            }
        }

    private suspend fun ensureSession() {
        if (sessionInitialized) return
        sessionMutex.withLock {
            if (sessionInitialized) return
            val credentials = authStore.getCredentials(TorrentSource.RUTRACKER)
            if (credentials != null) {
                val (u, p) = credentials
                val result = login(u, p)
                // Only mark session ready on success; leave false so next call retries
                if (result.isFailure) return
            } else {
                // Guest session — RuTracker sets bb_session cookie on first visit
                runCatching {
                    val req = Request.Builder()
                        .url("$baseUrl/tracker.php")
                        .header("User-Agent", UA)
                        .header("Accept", ACCEPT)
                        .header("Accept-Language", ACCEPT_LANG)
                        .build()
                    client.newCall(req).execute().close()
                }
                sessionInitialized = true
            }
        }
    }
}
