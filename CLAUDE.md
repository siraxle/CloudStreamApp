# CloudStream — Android Media Player

> Full product spec (features, acceptance criteria, DB schema, Gradle deps): [specs/product-spec.md](specs/product-spec.md)
> Cross-session memory (patterns, traps, open questions): see ~/.claude/projects/.../memory/

---

## Product

Android app for streaming audio/video from cloud storage (Google Drive, Dropbox, Yandex Disk, OneDrive, HTTP indexes) **without user registration**. User pastes a public link → app mounts the directory → plays media.

Core values: zero-auth UX, offline-first caching, folder navigation, playlist-centric.

---

## Tech Stack

```
Language:     Kotlin 100%
Min SDK:      API 26 (Android 8.0)
Target SDK:   API 35 (Android 15)
Architecture: MVVM + Clean Architecture + Repository Pattern
DI:           Hilt
Async:        Coroutines + Flow
Navigation:   Jetpack Navigation Component
UI:           Jetpack Compose (Material 3)
Player:       Media3 / ExoPlayer 3.x
HTTP:         OkHttp 4.x + Retrofit 2
DB:           Room 2.x (SQLite)
Prefs:        DataStore
Images:       Coil
```

**Locked Gradle versions** (do NOT upgrade without testing — prior combos failed):
- AGP 9.1.1, Hilt 2.59.2, Room 2.7.0, KSP 2.2.10-2.0.2

---

## Architecture

```
app/
├── core/         # network, cache, database, utils
├── data/
│   ├── cloud/    # CloudProvider implementations (gdrive, yandex, dropbox, onedrive, http, webdav)
│   ├── playlist/ # PlaylistRepository
│   └── settings/ # SettingsRepository
├── domain/       # models, use cases, port interfaces (no Android deps)
└── ui/           # browser, player, playlist, search, settings screens
```

**CloudProvider interface** — see [specs/product-spec.md](specs/product-spec.md) §5.

---

## Key Constraints

- Single-module app for now (no multi-module until complexity demands it)
- Hilt everywhere — no manual DI
- Domain layer must have zero Android dependencies
- All network calls HTTPS only (except explicit WebDAV http://)
- Cache: OkHttp (50 MB metadata) + ExoPlayer SimpleCache (up to N GB media) + Coil (100 MB thumbs)
- ExoPlayer buffer: min 15s / max 60s; start playback at 2.5s buffer
- Retry: exponential backoff 1s → 2s → 4s → 8s → give up

---

## Cloud Providers

| Provider    | Auth | API / method                                          |
|-------------|------|-------------------------------------------------------|
| Yandex Disk | none | `cloud-api.yandex.net/v1/disk/public/resources`       |
| Dropbox     | none | convert share URL to `dl.dropboxusercontent.com`      |
| Google Drive| none | HTML scraping (may hit Captcha — fallback: direct link)|
| OneDrive    | none | OData shared link                                     |
| HTTP Index  | none | Jsoup parse nginx/apache autoindex HTML               |
| WebDAV      | none | WebDAV client                                         |

---

## Screens & Navigation

```
Bottom Nav: [Browser] [Playlists] [Search] [Settings]
Persistent: MiniPlayer bar above NavBar

Browser → FolderView (nested) → MediaPlayer
Playlists → PlaylistDetail → MediaPlayer
Search → MediaPlayer / FolderView
```

---

## Performance Targets

| Metric              | Goal       |
|---------------------|------------|
| Cold start          | ≤ 2s       |
| Folder open (cache) | ≤ 300ms    |
| Folder open (net)   | ≤ 3s (LTE) |
| Playback start      | ≤ 2s       |
| RAM (background)    | ≤ 150 MB   |
| RAM (video)         | ≤ 300 MB   |
| CPU (bg audio)      | ≤ 3%       |

---

## Context Management (read before starting work)

- **New task = new session.** Use `/clear` when switching topics — keeps files, clears chat history.
- **Compact at 60%.** Use `/compact` manually when context is ~60% full. Don't wait for auto-compact at 95%.
- **Group related questions** into one message instead of sending them separately.
- **Use Plan Mode** (`/plan`) before large refactors or multi-file changes — plan first, then execute.
- **Heavy agent tasks** (large refactors, multi-file generation): run before 15:00 or after 21:00 MSK to avoid Anthropic peak load.

---

## VS Code JRE issue (recurring)

If Gradle fails with "jlink does not exist": VS Code extension has overwritten Gradle's executionHistory.
Fix: set `java.jdt.ls.java.home` in `.vscode/settings.json` to point to the correct JDK.
Full fix: `~/.claude/projects/.../memory/vscode_jdk_jlink_fix.md`
