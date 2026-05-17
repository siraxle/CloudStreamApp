# Torrent Streaming Integration — Design Document

> Status: Draft | Last updated: 2026-05-17
> Scope: add torrent-site browsing + sequential streaming to CloudStream
> Providers: The Pirate Bay · 1337x · Nyaa.si · RuTracker · Torrentz2

---

## 1. Goal

Allow users to paste a torrent-site URL or magnet link → app parses the torrent → streams audio files sequentially via ExoPlayer **without saving the full file**. The feature fits CloudStream's zero-auth UX: no accounts, no installation of a separate torrent client.

---

## 2. Similar Apps Analysis

| App | Approach | Strengths | Weaknesses |
|-----|----------|-----------|------------|
| **Stremio** | Addon system (WebTorrent JS), local HTTP proxy to player | Great UX, plugin ecosystem | Node.js addon arch, closed source core |
| **FrostWire** | libtorrent4j + built-in player | Full-featured, open source | Heavy (50 MB APK), ad-supported |
| **LibreTorrent** | libtorrent4j, sequential download, REST API mode | Lightweight, MIT license | No built-in media player |
| **TorrDroid** | HTTP scraping of torrent sites + download | Simple UX | No streaming, only download |
| **Vuze/BiglyBT** | Azureus codebase, Android port | Feature-complete | Outdated UI, complex integration |
| **Torrent Stream Server** | Go binary, local HTTP server, libtorrent | Dead-simple streaming API | Not Android-native |

**Key takeaway:** the proven pattern is **libtorrent → local HTTP server → ExoPlayer**. Stremio and LibreTorrent both use this. We adopt it.

---

## 3. Core Libraries

### 3.1 Torrent Engine

**[libtorrent4j](https://github.com/aldenml/libtorrent4j)**
- Java/Kotlin bindings for libtorrent (C++, battle-tested since 2003)
- Sequential download mode — essential for streaming
- Supports magnet links, .torrent files, DHT, PEX, LSD
- Maintained by Alden Melton (BiglyBT author); LibreTorrent uses it
- AAR + prebuilt `.so` for arm64-v8a, armeabi-v7a, x86_64
- License: MIT

```kotlin
// gradle
implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-31")
implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")
```

> Alternative: **TorrentStream-Android** (GitHub: adrielcafe/TorrentStream-Android) — simpler API but unmaintained since 2019. Use only for PoC.

### 3.2 Local HTTP Server

**[NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)**
- Tiny embedded HTTP server (~50 KB)
- Serves torrent piece data as a seekable HTTP stream with `Range` header support
- ExoPlayer/Media3 natively supports HTTP streams → zero player changes needed

```kotlin
implementation("org.nanohttpd:nanohttpd:2.3.1")
```

### 3.3 Torrent File / Magnet Parsing

libtorrent4j covers both. For .torrent file metadata without starting a download:
```kotlin
val torrentInfo = TorrentInfo(File(torrentPath))
val files = torrentInfo.files()
```

### 3.4 Torrent Site Scraping

Five built-in providers, same pattern as existing `CloudProvider` implementations:

| Provider | Integration | Library | Notes |
|----------|-------------|---------|-------|
| **The Pirate Bay** | REST JSON (`apibay.org/q.php`) | Retrofit | No scraping; stable public API |
| **1337x** | HTML scraping | Jsoup | Search → detail page → magnet link |
| **Nyaa.si** | RSS feed (`nyaa.si/?page=rss`) | Jsoup/Retrofit | Best for anime + music |
| **RuTracker** | HTML scraping | Jsoup | Requires guest session cookie; huge music catalog |
| **Torrentz2** | HTML scraping | Jsoup | Meta-search: aggregates results from 60+ sites |

All five are togglable in Settings — user enables only what they want.

---

## 4. Torrent Provider Details

Each provider implements `TorrentProvider` interface: `search(query, page) → List<TorrentResult>`.

### 4.1 The Pirate Bay
```
Base URL:  https://apibay.org/q.php?q={query}&cat=0
Response:  JSON array — name, info_hash, seeders, leechers, size
Magnet:    construct from info_hash + hardcoded tracker list
Auth:      none
```
Simplest to implement — pure Retrofit, no HTML parsing.

### 4.2 1337x
```
Search:    https://www.1337x.to/search/{query}/{page}/
Detail:    https://www.1337x.to{href}  (Jsoup: a.magnet href)
Magnet:    extracted from detail page
Auth:      none
```
Two-step: search page → detail page → magnet. Use Jsoup CSS selectors.

### 4.3 Nyaa.si
```
RSS:       https://nyaa.si/?page=rss&q={query}&c=2_0  (c=2_0 = Audio category)
Fields:    title, link (.torrent URL), seeders, size via RSS item tags
Magnet:    parse from <nyaa:infoHash> RSS tag or derive from .torrent URL
Auth:      none
```
RSS is stable — preferred over HTML scraping for Nyaa.

### 4.4 RuTracker
```
Search:    https://rutracker.org/forum/tracker.php?nm={query}
Session:   HTTP POST login once → save cookie in DataStore (guest mode possible for browsing)
Detail:    parse magnet link from topic page  
Auth:      optional login (improves results); guest browsing works without account
```
Largest Russian-language music catalog. Requires handling cookie session.

### 4.5 Torrentz2
```
Search:    https://torrentz2.nz/search?q={query}
Response:  HTML list of results with info_hash + tracker links
Magnet:    reconstructed from info_hash + tracker list on result page
Auth:      none
```
Meta-search: aggregates 60+ trackers, good as a fallback when others return nothing.

---

### User Flow
```
TorrentBrowserScreen
  ├── Search bar (query input)
  ├── Source filter chips: [TPB] [1337x] [Nyaa] [RuTracker] [Torrentz2]
  ├── Results list (name, size, seeders, source badge)
  └── Tap result → TorrentPlayerScreen (sequential stream starts)
```

User enables/disables providers in Settings → Torrent Sources.

---

## 5. Architecture

```
domain/
└── torrent/
    ├── TorrentProvider.kt       # interface: search(query), resolve(magnet/url) → TorrentFile
    ├── TorrentFile.kt           # model: name, size, files[], magnetUri
    └── TorrentStreamSession.kt  # model: localUrl, progress, seeders

data/
└── torrent/
    ├── engine/
    │   ├── LibtorrentEngine.kt      # wraps libtorrent4j, manages session lifecycle
    │   └── TorrentHttpServer.kt     # NanoHTTPD: serves piece data on 127.0.0.1:PORT
    ├── provider/
    │   ├── TorrentProviderConfig.kt # enabled flags per provider, stored in DataStore
    │   ├── PirateBayProvider.kt     # REST JSON via Retrofit
    │   ├── Provider1337x.kt         # HTML scraping via Jsoup (2-step)
    │   ├── NyaaProvider.kt          # RSS feed via Jsoup/Retrofit
    │   ├── RuTrackerProvider.kt     # HTML scraping + session cookie
    │   └── Torrentz2Provider.kt     # HTML meta-search scraping
    └── TorrentRepository.kt         # fan-out search across enabled providers, dedup by hash

ui/
└── torrent/
    ├── TorrentBrowserScreen.kt  # search + results list
    └── TorrentPlayerScreen.kt   # reuses existing PlayerScreen, passes localUrl
```

**Key design decisions:**
- `LibtorrentEngine` is a singleton (one libtorrent session per process — requirement of the library)
- `TorrentHttpServer` listens on `127.0.0.1:18384` (loopback only, not network-exposed)
- Streaming URL passed to ExoPlayer: `http://127.0.0.1:18384/stream/{torrentHash}/{fileIndex}`
- Sequential piece download: set file priority = NORMAL, enable `sequential_download` flag
- Piece prioritization: first + last 5 pieces set to CRITICAL priority (for metadata + seek-to-end)

---

## 6. Streaming Flow

```
User pastes magnet / taps result
        │
        ▼
TorrentRepository.openStream(magnet)
        │
        ├─► LibtorrentEngine.addTorrent(magnet)
        │         │
        │         ├─ DHT + trackers → peer discovery
        │         ├─ fetch metadata (torrent_info)
        │         └─ start sequential download
        │
        ├─► TorrentHttpServer.registerStream(torrentHash, fileIndex)
        │         └─ returns http://127.0.0.1:18384/stream/...
        │
        └─► Emit TorrentStreamSession(localUrl, bufferProgress)
                  │
                  ▼
        PlayerScreen receives localUrl
                  │
                  ▼
        Media3 / ExoPlayer → plays via HTTP (Range requests)
```

**Buffer gate:** don't call `player.play()` until `bufferProgress >= 3%` (≈ first 30s of audio at 128 kbps). Show a "Connecting to peers…" state before that.

---

## 7. NanoHTTPD Range-Request Handler

```kotlin
class TorrentHttpServer(private val engine: LibtorrentEngine) : NanoHTTPD("127.0.0.1", PORT) {

    override fun serve(session: IHTTPSession): Response {
        val (hash, fileIndex) = parseUri(session.uri) // /stream/{hash}/{index}
        val rangeHeader = session.headers["range"]     // "bytes=0-" or "bytes=N-M"
        val (start, end) = parseRange(rangeHeader, engine.fileSize(hash, fileIndex))

        val inputStream = engine.openInputStream(hash, fileIndex, start)
        return newChunkedResponse(Response.Status.PARTIAL_CONTENT, mimeFor(fileIndex), inputStream)
            .apply { addHeader("Content-Range", "bytes $start-$end/${engine.fileSize(hash, fileIndex)}") }
    }
}
```

ExoPlayer will send multiple `Range` requests as the user seeks. `LibtorrentEngine.openInputStream` must re-prioritize pieces around the new seek offset.

---

## 8. Piece Re-prioritization on Seek

```kotlin
fun seekTo(hash: String, byteOffset: Long) {
    val handle = activeTorrents[hash] ?: return
    val pieceSize = handle.torrentFile().pieceLength()
    val targetPiece = (byteOffset / pieceSize).toInt()
    val windowPieces = 20 // pre-buffer ~20 pieces ahead

    // Reset all priorities, then boost window
    handle.prioritizeAllPieces(Priority.IGNORE)
    (targetPiece until minOf(targetPiece + windowPieces, handle.numPieces()))
        .forEachIndexed { i, piece ->
            handle.piecePriority(piece, if (i < 5) Priority.SEVEN else Priority.NORMAL)
        }
    handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
}
```

---

## 9. Permissions & Manifest

```xml
<!-- Already present -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Add for torrent DHT (UDP) — no extra permission needed on Android -->
<!-- NanoHTTPD binds to loopback — no FOREGROUND_SERVICE needed unless background seeding -->

<!-- Optional: background seeding as a foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Torrent engine must be stopped when the user leaves the player screen to avoid background bandwidth consumption. Add lifecycle-aware cleanup in `PlayerViewModel.onCleared()`.

---

## 10. Storage & Caching

| Layer | What | Size |
|-------|------|------|
| libtorrent piece cache | Downloaded pieces (in-memory + temp files) | Configurable, default 64 MB |
| Temp dir `cacheDir/torrents/` | Pieces spilled to disk | Clean on app exit |
| `filesDir/torrents/` | Partially downloaded files (if user opts in) | User-controlled |
| Room DB | `TorrentHistoryEntity(hash, name, magnetUri, lastPlayed)` | Tiny |

**No permanent seeding by default.** Data is removed from `cacheDir` when the session ends. Add a Settings toggle "Seed while on Wi-Fi" for future scope.

---

## 11. Legal Notes

- App functionality: parse + stream. Equivalent to what a web browser + media player does.
- All five providers are public sites accessible from any browser — no special access.
- Include a one-time disclaimer on first launch of Torrent screen: "This feature connects to public torrent indexers. You are responsible for complying with your local laws."
- **All providers disabled by default** — user explicitly enables each one in Settings.
- Audio-only filter: show only files with extensions `mp3, flac, aac, ogg, opus, m4a` by default; user can disable the filter to see all files.
- RuTracker: if login is implemented, credentials are stored encrypted in EncryptedSharedPreferences (Jetpack Security); never in plaintext.

---

## 12. Implementation Phases

### Phase 1 — Streaming engine (2 weeks)
- [ ] Add libtorrent4j dependency + ABI splits in `build.gradle`
- [ ] `LibtorrentEngine` — session lifecycle, sequential download, piece events
- [ ] `TorrentHttpServer` (NanoHTTPD) — Range request handler
- [ ] `TorrentPlayerScreen` — reuse existing Player UI, pass `localUrl`
- [ ] Intent filter: handle `magnet:` URI scheme (paste from browser)
- [ ] Unit tests: range parsing, piece prioritization

### Phase 2 — Five providers + browser UI (3 weeks)
- [ ] `TorrentResult` domain model (name, size, seeders, magnetUri, source)
- [ ] `PirateBayProvider` — Retrofit + JSON (start here, simplest)
- [ ] `NyaaProvider` — RSS parsing
- [ ] `Provider1337x` — Jsoup 2-step scraping
- [ ] `Torrentz2Provider` — Jsoup meta-search scraping
- [ ] `RuTrackerProvider` — Jsoup + session cookie + optional login
- [ ] `TorrentBrowserScreen` — search bar, source filter chips, results list
- [ ] Settings screen: "Torrent Sources" section with toggles per provider
- [ ] `TorrentRepository.search()` — parallel fan-out across enabled providers, dedup by `infoHash`

### Phase 3 — Polish (1 week)
- [ ] Buffer progress indicator ("Connecting to peers… 3%")
- [ ] Peer count + download speed badge in player overlay
- [ ] Audio-only file filter toggle
- [ ] History (`TorrentHistoryEntity` in Room)
- [ ] One-time legal disclaimer dialog

---

## 13. Key Risks

| Risk | Mitigation |
|------|------------|
| No peers found (dead torrent) | Show clear error after 30s timeout |
| Seek to un-downloaded region | Re-prioritize pieces + show buffering spinner |
| APK size increase (libtorrent .so) | Use ABI splits: +8 MB per ABI |
| libtorrent session crash (native crash) | Run in isolated process (`android:process=":torrent"`) |
| ISP blocking DHT/UDP | Fallback: HTTP tracker only; show warning |
| App killed while seeding | No background seeding in v1; foreground service in v2 |

---

## 14. References

- libtorrent4j: https://github.com/aldenml/libtorrent4j
- LibreTorrent (reference implementation): https://github.com/proninyaroslav/libretorrent
- NanoHTTPD: https://github.com/NanoHttpd/nanohttpd
- libtorrent sequential download: https://libtorrent.org/manual-ref.html#sequential-download
- apibay.org API (The Pirate Bay): https://apibay.org/q.php?q=test&cat=0
- Nyaa RSS docs: https://github.com/nicehash/nyaa/wiki/API
