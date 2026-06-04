# Торрент-стриминг: план реализации

Документ описывает четыре проблемы, выявленные в ходе отладки, и пошаговый план их решения.

---

## Контекст

Воспроизведение торрентов устроено так:
- `LibtorrentEngine` управляет libtorrent-сессией, хранит состояния в `states: ConcurrentHashMap<String, ActiveTorrent>`
- `TorrentHttpServer` (NanoHTTPD на `127.0.0.1:18384`) отдаёт ExoPlayer куски файла через HTTP Range-запросы
- `TorrentInputStream` блокируется в `waitForPiece()` до прихода нужного куска
- ExoPlayer читает через `OkHttpDataSource` → `CompositeMediaDataSource` → `tempCacheFactory`

---

## Проблема 1 — Торрент скачивается целиком при открытии (главный баг)

**Симптом:** «скачать папку» завершается мгновенно даже через 10 минут после открытия торрента — файлы уже лежат в `cacheDir/torrents`.

**Корневая причина:** `addMagnet()` / `addTorrentBytes()` сразу включают `SEQUENTIAL_DOWNLOAD` и буст первых/последних 5 кусков → libtorrent качает весь торрент последовательно ещё до того, как пользователь что-то нажал. «Скачать на устройство» — лишь копирование файла.

Нужно чтоб при запуске трека в папке запускался процесс скачивания в кэш конкретно той папки в которой лежит трек. Все файлы и только эта папка помечаются что сейчас идет их кэширования. По завершению поместить в статус в "в кэше". 

---

### Шаг 1.1 — `LibtorrentEngine`: отложенный старт скачивания

Файл: `data/torrent/engine/LibtorrentEngine.kt`

**В `addMagnet()` и `addTorrentBytes()`** — убрать блок с `SEQUENTIAL_DOWNLOAD` и заменить на:
```kotlin
val numFiles = ti.files().numFiles()
if (numFiles > 0) handle.prioritizeFiles(Array(numFiles) { Priority.IGNORE })
```

Добавить поле отслеживания активированных файлов:
```kotlin
private val activatedFileIndices = ConcurrentHashMap<String, MutableSet<Int>>()
```

**В `removeTorrent()`** — добавить очистку:
```kotlin
activatedFileIndices.remove(infoHash)
```

---

### Шаг 1.2 — `LibtorrentEngine`: новый приватный метод `startFolderDownload()`

Вызывается один раз при первом `seekTo()` для данного файла.

```kotlin
private fun startFolderDownload(state: ActiveTorrent, infoHash: String, fileIndex: Int) {
    val activated = activatedFileIndices[infoHash] ?: emptySet()
    if (fileIndex in activated) return           // папка уже скачивается

    val fs = state.info.files()
    val targetParent = fs.filePath(fileIndex).substringBeforeLast("/", "")

    // Все файлы в той же непосредственной папке
    val folderIndices = (0 until fs.numFiles()).filter { i ->
        fs.filePath(i).substringBeforeLast("/", "") == targetParent
    }
    val newIndices = folderIndices.filter { it !in activated }
    if (newIndices.isEmpty()) return

    // Устанавливаем DEFAULT-приоритет кускам каждого нового файла
    for (idx in newIndices) {
        val range = filePieceRange(state, idx) ?: continue
        for (piece in range.first..range.second) {
            if (piece !in state.downloadedPieces)
                state.handle.piecePriority(piece, Priority.DEFAULT)
        }
    }

    // Буст первых 5 кусков воспроизводимого файла
    val (startPiece, _) = filePieceRange(state, fileIndex) ?: return
    val total = state.info.numPieces()
    for (p in startPiece until minOf(startPiece + 5, total))
        state.handle.piecePriority(p, Priority.TOP_PRIORITY)

    state.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    activatedFileIndices.getOrPut(infoHash) { mutableSetOf() }.addAll(newIndices)
    Log.i(TAG, "Folder download started: '$targetParent' (${newIndices.size} files)")
}
```

**В `seekTo()`** — вызвать в начале метода:
```kotlin
startFolderDownload(state, infoHash, fileIndex)
```
Убрать старую строку `handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)` из конца `seekTo()`.

---

### Шаг 1.3 — `LibtorrentEngine.boostFilePriority()`: регистрация явного скачивания

Чтобы `startFolderDownload()` не перезатёр приоритеты файла, который пользователь скачивает вручную:
```kotlin
activatedFileIndices.getOrPut(infoHash) { mutableSetOf() }.add(fileIndex)
```
Добавить в начало `boostFilePriority()` до расчёта диапазона кусков.

---

## Проблема 2 — Нет управления стриминговым кэшем в Настройках

**Задача:** Отображать размер `cacheDir/torrents` в Настройках и давать кнопку ручной очистки. Кэш не ограничен и не очищается автоматически — только по решению пользователя.

---

### Шаг 2.1 — `LibtorrentEngine`: публичные методы кэша

```kotlin
fun streamingCacheSizeBytes(): Long =
    savePath.walkTopDown().filter { it.isFile }.sumOf { it.length() }

fun clearStreamingCache() {
    states.keys.toList().forEach { hash ->
        val state = states.remove(hash) ?: return@forEach
        session?.remove(state.handle)
        pieceWaiters.remove(hash)
        activatedFileIndices.remove(hash)
    }
    savePath.deleteRecursively()
    savePath.mkdirs()
    Log.i(TAG, "Streaming cache cleared")
}
```

---

### Шаг 2.2 — `SettingsViewModel`

- Добавить `private val torrentEngine: LibtorrentEngine` в конструктор
- Добавить `private val _torrentStreamingCacheBytes = MutableStateFlow(0L)` + публичный `StateFlow`
- В `refreshCacheUsage()` — стриминг считать отдельно:
  ```kotlin
  val streamingBytes = withContext(Dispatchers.IO) { torrentEngine.streamingCacheSizeBytes() }
  _torrentStreamingCacheBytes.value = streamingBytes
  ```
- Добавить функцию:
  ```kotlin
  fun clearTorrentStreamingCache() {
      viewModelScope.launch(Dispatchers.IO) {
          torrentEngine.clearStreamingCache()
          _torrentStreamingCacheBytes.value = 0L
      }
  }
  ```

---

### Шаг 2.3 — `SettingsScreen`

- В `totalUsedBytes` — **не** включать `torrentStreamingCacheBytes` (кэш не лимитирован)
- В строке breakdown — добавить третий компонент:
  ```
  Кэш: X MB  •  Скачано: Y MB  •  Стриминг: Z MB
  ```
- Добавить новый `ListItem` «Кэш торрент-стриминга» с кнопкой «Очистить» (задизаблена если 0)
- Добавить `AlertDialog` подтверждения очистки стримингового кэша

---

## Проблема 3 — Нет визуальных индикаторов кэша в браузере торрента

**Задача:** Зелёный цвет для закэшированных папок и метка «В кэше» для файлов.

---

### Шаг 3.1 — `LibtorrentEngine`: запросы состояния кэша

```kotlin
fun getActivatedFileIndices(infoHash: String): Set<Int> =
    activatedFileIndices[infoHash]?.toSet() ?: emptySet()

fun getStreamingCachedFolderPaths(infoHash: String): Set<String> {
    val activated = activatedFileIndices[infoHash]?.toList() ?: return emptySet()
    val state = states[infoHash] ?: return emptySet()
    val fs = state.info.files()
    val result = mutableSetOf<String>()
    for (idx in activated) {
        val segments = fs.filePath(idx).split("/").dropLast(1)
        for (n in 1..segments.size) result.add(segments.take(n).joinToString("/"))
    }
    return result
}
```

---

### Шаг 3.2 — `TorrentBrowserViewModel`

- Добавить `private val engine: LibtorrentEngine` в конструктор
- Добавить два `MutableStateFlow<Set<*>>`: `streamingCachedFileIndices`, `streamingCachedFolderPaths`
- В `init {}` — запустить polling-корутину (1 секунда):
  ```kotlin
  viewModelScope.launch {
      while (true) {
          val fl = _uiState.value as? UiState.FileList
          if (fl != null) {
              _streamingCachedFileIndices.value = engine.getActivatedFileIndices(fl.infoHash)
              _streamingCachedFolderPaths.value = engine.getStreamingCachedFolderPaths(fl.infoHash)
          } else {
              _streamingCachedFileIndices.value = emptySet()
              _streamingCachedFolderPaths.value = emptySet()
          }
          delay(1_000)
      }
  }
  ```

---

### Шаг 3.3 — `TorrentBrowserScreen`

Определить константу цвета:
```kotlin
private val CachedGreen = Color(0xFF388E3C)
```

**`FolderItem`** — параметр `isCached: Boolean`:
- иконка `Folder` меняет tint на `CachedGreen`
- поверх иконки — маленький `OfflinePin` (10dp) в `CachedGreen` через `Box`

**`FileItem`** — параметр `isCached: Boolean`:
- в `supportingContent` рядом с размером — `OfflinePin` (12dp) + текст «В кэше» цветом `CachedGreen`
- показывать только если `dlProgress !is DownloadProgress.Done`

**Снекбар при первом воспроизведении:**
- Добавить `Event.FolderCachingStarted` в `TorrentBrowserViewModel.Event`
- Добавить `fun notifyFilePlayed(item: CloudItem)` — эмитит событие если файл ещё не в `streamingCachedFileIndices`
- В `TorrentBrowserScreen` при нажатии на файл:
  ```kotlin
  viewModel.notifyFilePlayed(item)
  onPlayFile(item, magnetUri, infoHash)
  ```
- Показывать снекбар: _«Файлы папки кэшируются — следующие треки откроются быстрее»_

---

## Порядок реализации

| # | Задача | Файлы |
|---|--------|-------|
| 1 | Отложенный старт + `Priority.IGNORE` при добавлении | `LibtorrentEngine.kt` |
| 2 | `startFolderDownload()` приватный метод | `LibtorrentEngine.kt` |
| 3 | `boostFilePriority()` регистрирует в `activatedFileIndices` | `LibtorrentEngine.kt` |
| 4 | `streamingCacheSizeBytes()` + `clearStreamingCache()` | `LibtorrentEngine.kt` |
| 5 | `SettingsViewModel` — стриминговый кэш | `SettingsViewModel.kt` |
| 6 | `SettingsScreen` — UI кэша стриминга | `SettingsScreen.kt` |
| 7 | `getActivatedFileIndices()` + `getStreamingCachedFolderPaths()` | `LibtorrentEngine.kt` |
| 8 | `TorrentBrowserViewModel` — polling StateFlows + `notifyFilePlayed()` | `TorrentBrowserViewModel.kt` |
| 9 | `TorrentBrowserScreen` — зелёные папки, метка «В кэше», снекбар | `TorrentBrowserScreen.kt` |

Шаги 1–3 составляют единый коммит (атомарный баг-фикс). Шаги 4–6 — второй коммит. Шаги 7–9 — третий.
