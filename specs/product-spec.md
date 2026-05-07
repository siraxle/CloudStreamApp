# CLAUDE.md — Спецификация: CloudStream Media Player (Android)

> **Версия:** 1.0  
> **Дата:** 2026-04-30  
> **Статус:** Ready for Development

---

## 1. Обзор продукта

**CloudStream** — Android-приложение для потоковой передачи аудио и видео файлов из облачных хранилищ (Google Drive, Dropbox, Yandex Disk, OneDrive, прямые HTTP-ссылки) **без регистрации в приложении**. Пользователь вставляет публичную ссылку → приложение монтирует директорию → воспроизводит медиафайлы.

### Ключевые ценности
- **Zero-auth UX**: никаких аккаунтов — только ссылки
- **Offline-first**: агрессивное кэширование для нестабильного интернета
- **Navigation-first**: навигация по папкам как в файловом менеджере
- **Playlist-centric**: плейлисты как первоклассный объект

---

## 2. Анализ конкурентов

| Приложение | Облака | Без регистрации | Кэш | Плейлисты | Папки |
|---|---|---|---|---|---|
| **VLC for Android** | ✅ SMB/FTP/HTTP | ✅ | ❌ нет | ✅ | ✅ |
| **nPlayer** | ✅ много | ⚠️ частично | ⚠️ слабый | ✅ | ✅ |
| **Infuse (iOS only)** | ✅ | ✅ | ✅ отличный | ✅ | ✅ |
| **MX Player** | ❌ только локально | ❌ | ❌ | ✅ | ✅ |
| **Stremio** | ✅ торренты | ✅ | ✅ | ✅ | ❌ |
| **FE File Explorer** | ✅ | ✅ | ⚠️ | ❌ | ✅ |
| **CloudBeats** | ✅ | ❌ OAuth | ❌ | ✅ | ✅ |

### Выводы из анализа
1. **Главный gap**: нет приложения с удобной zero-auth навигацией + умным кэшем + плейлистами
2. **VLC** — ближайший аналог, но UX устаревший, кэш слабый
3. **Infuse** — лучший UX, но только iOS и требует iCloud/аккаунт
4. **Наша ниша**: пользователи, которые шарят ссылки на папки Google Drive/Yandex с музыкой/видео

---

## 3. Целевая аудитория

- Пользователи, которые хранят медиатеки в облаке и слушают на ходу
- Люди с нестабильным/дорогим интернетом (метро, поезда, путешествия)
- Любители самоорганизованных коллекций (не стриминговых сервисов)
- Технически грамотные пользователи, которые делятся ссылками

---

## 4. Технический стек

### Обязательно
```
Language:       Kotlin (100%)
Min SDK:        API 26 (Android 8.0) — ~95% устройств
Target SDK:     API 35 (Android 15)
Architecture:   MVVM + Clean Architecture + Repository Pattern
DI:             Hilt
Async:          Coroutines + Flow
Navigation:     Jetpack Navigation Component
UI:             Jetpack Compose (Material 3)
```

### Медиа движок
```
Primary:        Media3 (ExoPlayer 3.x) — основной плеер
Fallback:       ExoPlayer RTMP extension — для потоков
Format support: MP3, FLAC, AAC, OGG, WAV, M4A, OPUS (аудио)
                MP4, MKV, AVI, MOV, WEBM, TS (видео)
Subtitles:      SRT, VTT, ASS через Media3
```

### Сеть и кэш
```
HTTP Client:    OkHttp 4.x + Retrofit 2
Cache Layer:    OkHttp Cache (HTTP-кэш, 500 MB)
Pre-buffer:     Media3 CacheDataSource + SimpleCache
File Cache:     DiskLruCache (кастомный, 2 GB лимит)
Range requests: ✅ обязательно (RFC 7233)
Retry logic:    Exponential backoff (1s → 2s → 4s → 8s → fail)
```

### Облачные адаптеры
```
Google Drive:   Google Drive Sharing API (без OAuth для публичных папок)
Yandex Disk:    Yandex Disk Public API
Dropbox:        Dropbox Paper/Shared Links API
OneDrive:       OneDrive Share Links (OData)
Direct HTTP:    Crawler на основе HTML-парсинга (nginx autoindex, apache)
WebDAV:         WebDAV клиент (для самохостинга)
```

### Хранилище
```
Database:       Room 2.x (SQLite)
Preferences:    DataStore Proto
Media Store:    ContentResolver (для экспорта в локальное хранилище)
```

---

## 5. Архитектура приложения

```
app/
├── core/
│   ├── network/          # OkHttp, Retrofit, interceptors
│   ├── cache/            # DiskCache, ExoPlayer cache
│   ├── database/         # Room DAOs, entities
│   └── utils/            # Extensions, helpers
├── data/
│   ├── cloud/            # CloudProvider interface + implementations
│   │   ├── gdrive/       # Google Drive adapter
│   │   ├── yandex/       # Yandex Disk adapter
│   │   ├── dropbox/      # Dropbox adapter
│   │   ├── onedrive/     # OneDrive adapter
│   │   ├── http/         # Direct HTTP + autoindex parser
│   │   └── webdav/       # WebDAV adapter
│   ├── playlist/         # PlaylistRepository
│   └── settings/         # SettingsRepository
├── domain/
│   ├── model/            # MediaItem, CloudFolder, Playlist, etc.
│   ├── usecase/          # Business logic use cases
│   └── port/             # Repository interfaces
└── ui/
    ├── home/             # Главный экран / недавние
    ├── browser/          # Навигация по папкам
    ├── player/           # Аудио/видео плеер
    ├── playlist/         # Управление плейлистами
    ├── search/           # Поиск по индексу
    └── settings/         # Настройки
```

### Интерфейс CloudProvider
```kotlin
interface CloudProvider {
    val type: CloudType  // GDRIVE, YANDEX, DROPBOX, ONEDRIVE, HTTP, WEBDAV

    suspend fun resolve(url: String): CloudResult  // Определяем тип ссылки
    suspend fun listFolder(path: CloudPath): Flow<List<CloudItem>>
    suspend fun getStreamUrl(item: CloudItem): String  // Прямой URL для ExoPlayer
    suspend fun getMetadata(item: CloudItem): MediaMetadata
    fun isSupported(url: String): Boolean
}
```

---

## 6. Функциональные требования и критерии приёмки

---

### F-01: Добавление источника по ссылке

**Описание:** Пользователь вставляет публичную ссылку на папку или файл в облаке.

**User Story:**  
_Как пользователь, я хочу вставить ссылку из буфера обмена, чтобы мгновенно получить доступ к медиафайлам без регистрации._

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-01.1 | Пользователь вставляет публичную ссылку Google Drive на папку | Папка открывается в браузере за ≤ 3 сек |
| AC-01.2 | Пользователь вставляет ссылку Yandex Disk | Определяется провайдер, показывается содержимое |
| AC-01.3 | Вставлена ссылка на одиночный файл | Немедленно запускается воспроизведение |
| AC-01.4 | Ссылка недоступна / истекла | Показывается ошибка с кнопкой "Попробовать снова" |
| AC-01.5 | Ссылка не распознана | Предлагается ввести вручную тип провайдера |
| AC-01.6 | Повторное добавление той же ссылки | Предупреждение: "Уже добавлено. Обновить?" |
| AC-01.7 | Ссылка скопирована в буфер — приложение в фокусе | Баннер "Вставить скопированную ссылку?" |

**Поддерживаемые форматы ссылок:**
```
Google Drive:  https://drive.google.com/drive/folders/{id}
               https://drive.google.com/file/d/{id}/view
Yandex Disk:   https://disk.yandex.ru/d/{hash}
               https://yadi.sk/d/{hash}
Dropbox:       https://www.dropbox.com/sh/{id}
OneDrive:      https://1drv.ms/{id}
               https://onedrive.live.com/share?...
HTTP Index:    http(s)://any.server/path/ (nginx/apache autoindex)
WebDAV:        webdav://server/path
               https://server/dav/path
```

---

### F-02: Навигация по папкам

**Описание:** Файловый браузер с иерархической навигацией внутри облачной директории.

**User Story:**  
_Как пользователь, я хочу просматривать структуру папок как в обычном файловом менеджере, с возможностью быстро переходить назад._

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-02.1 | Открытие корневой папки | Список файлов и подпапок, отсортированный по имени |
| AC-02.2 | Тап на подпапку | Переход внутрь, хлебные крошки обновляются |
| AC-02.3 | Кнопка "Назад" / свайп | Возврат на уровень выше |
| AC-02.4 | Тап на элемент хлебной крошки | Прыжок на любой уровень иерархии |
| AC-02.5 | Папка содержит > 200 элементов | Пагинация / infinite scroll, загрузка по 50 |
| AC-02.6 | Смешанный контент (файлы + папки) | Папки — сверху, файлы — снизу; иконки разные |
| AC-02.7 | Тап на медиафайл | Немедленное воспроизведение |
| AC-02.8 | Долгое нажатие на файл | Контекстное меню: Воспроизвести / Добавить в плейлист / Кэшировать / Поделиться |
| AC-02.9 | Смена вида: сетка ↔ список | Переключатель в тулбаре, настройка сохраняется |
| AC-02.10 | Сортировка | По имени / дате / размеру / типу; по убыванию и возрастанию |
| AC-02.11 | Нет интернета, есть кэш | Показывается последний известный список с меткой "Оффлайн" |
| AC-02.12 | Поиск внутри папки | Фильтрация по имени в реальном времени |

**UI элементы браузера:**
```
Toolbar:
  [← Back] [Breadcrumbs scrollable] [Search] [View: Grid/List] [Sort] [⋮]

List item:
  [Thumbnail/Icon] [Name] [Size · Duration] [Cached indicator] [⋮]

Grid item:
  [Thumbnail large]
  [Name truncated]
  [Duration badge]
```

---

### F-03: Аудиоплеер

**Описание:** Полнофункциональный аудиоплеер с управлением из уведомления и поддержкой фоновых процессов.

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-03.1 | Запуск аудиофайла | Воспроизведение начинается ≤ 2 сек (с буфером) |
| AC-03.2 | Сворачивание приложения | Воспроизведение не прерывается, уведомление появляется |
| AC-03.3 | Управление через уведомление | ⏮ ⏯ ⏭ работают без открытия приложения |
| AC-03.4 | Блокировка экрана | Отображается обложка, название, кнопки управления |
| AC-03.5 | Наушники вынуты | Пауза автоматически |
| AC-03.6 | Входящий звонок | Пауза, возобновление после окончания звонка |
| AC-03.7 | Перемотка перетаскиванием | Seeks с предварительной загрузкой ближайших данных |
| AC-03.8 | Режим повтора: трек / плейлист / выкл | Переключается кнопкой, состояние сохраняется |
| AC-03.9 | Перемешивание | Shuffle с запоминанием истории (можно вернуться) |
| AC-03.10 | Скорость воспроизведения | 0.5× 0.75× 1× 1.25× 1.5× 2× |
| AC-03.11 | Эквалайзер | 5-полосный EQ + пресеты (Rock, Pop, Classical, Custom) |
| AC-03.12 | Sleep timer | 15 / 30 / 45 / 60 мин или до конца трека |
| AC-03.13 | ReplayGain | Если тег присутствует — нормализация громкости |
| AC-03.14 | Crossfade | Настраиваемый 0–10 сек (только между треками) |

**Мини-плеер (persistent bottom bar):**
```
[Thumbnail] [Title · Artist] [Progress bar] [⏮] [⏯] [⏭] [♥]
```

---

### F-04: Видеоплеер

**Описание:** Видеоплеер с поддержкой субтитров, управлением жестами и выбором звуковой дорожки.

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-04.1 | Открытие видеофайла | Плеер открывается в полноэкранном режиме |
| AC-04.2 | Двойной тап слева/справа | Перемотка на −10 / +10 секунд |
| AC-04.3 | Свайп вертикально слева | Управление яркостью экрана |
| AC-04.4 | Свайп вертикально справа | Управление громкостью |
| AC-04.5 | Свайп горизонтально | Перемотка с превью кадра |
| AC-04.6 | Щипок для зума | Zoom in/out видео (до 2×) |
| AC-04.7 | Субтитры SRT/VTT в той же папке | Автоматически подхватываются по имени файла |
| AC-04.8 | Выбор звуковой дорожки | Меню при наличии нескольких дорожек в MKV |
| AC-04.9 | Picture-in-Picture | Поддержка Android PiP (API 26+) |
| AC-04.10 | Поворот экрана | Авто + ручная фиксация ориентации |
| AC-04.11 | Масштаб: Fill / Fit / Stretch | Переключатель в оверлее |
| AC-04.12 | Скриншот кадра | Кнопка → сохраняется в галерею |

---

### F-05: Плейлисты

**Описание:** Создание, управление и воспроизведение плейлистов из треков разных облачных источников.

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-05.1 | Создание плейлиста | Имя + опциональная обложка (авто из треков) |
| AC-05.2 | Добавление трека в плейлист | Из браузера / плеера / поиска через контекстное меню |
| AC-05.3 | Добавление всей папки | "Добавить папку" → все медиафайлы рекурсивно |
| AC-05.4 | Перестановка треков | Drag & drop в списке плейлиста |
| AC-05.5 | Удаление трека из плейлиста | Свайп влево + подтверждение |
| AC-05.6 | Плейлист из разных облаков | ✅ Mix Google Drive + Yandex + HTTP |
| AC-05.7 | Экспорт плейлиста | M3U8 файл → на устройство или шаринг |
| AC-05.8 | Импорт M3U8 | Из локального файла или URL |
| AC-05.9 | Умный плейлист | Авто-плейлист по критериям: жанр, темп, папка |
| AC-05.10 | "Воспроизвести папку как плейлист" | Тап "▶ Воспроизвести всё" в браузере |
| AC-05.11 | Длина плейлиста | Отображается суммарное время и количество треков |
| AC-05.12 | Сортировка в плейлисте | По имени / дате добавления / кастомная |

---

### F-06: Кэш и оффлайн-режим

**Описание:** Агрессивное предзагрузчик для нестабильного соединения.

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-06.1 | Трек начал воспроизводиться | Следующие 2 трека в очереди начинают предзагружаться |
| AC-06.2 | Буфер при воспроизведении | Минимум 30 сек вперёд всегда в буфере |
| AC-06.3 | Соединение пропало на 5 сек | Воспроизведение продолжается из буфера без пауз |
| AC-06.4 | Соединение пропало на 30 сек | Пауза + анимация буферизации, автовозобновление |
| AC-06.5 | "Скачать для оффлайна" | Файл сохраняется полностью в кэш (прогрессбар) |
| AC-06.6 | Кэш заполнен (> лимита) | LRU вытеснение: удаляются наименее востребованные |
| AC-06.7 | Пользователь задал лимит кэша | 500 MB / 1 GB / 2 GB / 5 GB / без ограничений |
| AC-06.8 | Предзагрузка плейлиста | "Скачать плейлист" → все треки последовательно |
| AC-06.9 | Индикатор кэша | Иконка ☁ (в облаке) / ⬇ (частично) / ✓ (полностью) |
| AC-06.10 | Ручная очистка кэша | Настройки → Кэш → Очистить всё / по источнику |
| AC-06.11 | WiFi-only режим | Предзагрузка только по WiFi, стриминг всегда |
| AC-06.12 | Resumed download | При обрыве загрузки возобновление с точки остановки |

**Стратегия кэширования:**
```
Структура кэша:
  /cache/
    http/           — OkHttp HTTP-кэш (метаданные, 50 MB)
    media/          — ExoPlayer SimpleCache (медиаданные, до N GB)
    thumbs/         — Миниатюры (100 MB, WebP)
    metadata/       — ID3/метаданные JSON (10 MB)

Приоритеты предзагрузки:
  1. Следующий трек в очереди (высший приоритет)
  2. Трек через один
  3. Обложки видимых элементов в браузере
  4. Явно "скачанные" пользователем файлы
```

---

### F-07: Поиск

**Описание:** Поиск по проиндексированным источникам и метаданным.

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-07.1 | Поиск по имени файла | Результаты в реальном времени по мере ввода |
| AC-07.2 | Поиск по метаданным | ID3-теги: исполнитель, альбом, жанр |
| AC-07.3 | Поиск по всем источникам | Объединённые результаты с указанием источника |
| AC-07.4 | Фильтр по типу | Только аудио / только видео |
| AC-07.5 | Фильтр по источнику | Только из конкретного облака |
| AC-07.6 | История поиска | Последние 20 запросов, можно очистить |
| AC-07.7 | Нет результатов | "Ничего не найдено" + предложение расширить поиск |

---

### F-08: Управление источниками

**Описание:** Библиотека добавленных ссылок с управлением.

**Критерии приёмки:**

| # | Сценарий | Ожидаемый результат |
|---|---|---|
| AC-08.1 | Список источников | Иконка провайдера + имя + дата добавления + статус |
| AC-08.2 | Переименование источника | Кастомное имя вместо URL |
| AC-08.3 | Удаление источника | Подтверждение + опция "Очистить кэш этого источника" |
| AC-08.4 | Обновление индекса | Pull-to-refresh или кнопка "Синхронизировать" |
| AC-08.5 | Статус доступности | Зелёный (доступен) / Жёлтый (кэш) / Красный (недоступен) |
| AC-08.6 | Пин-код / биометрия для источника | Опционально для приватных ссылок |

---

## 7. Нефункциональные требования

### Производительность

| Метрика | Цель |
|---|---|
| Cold start | ≤ 2 сек до главного экрана |
| Открытие папки (кэш) | ≤ 300 мс |
| Открытие папки (сеть) | ≤ 3 сек (LTE) |
| Начало воспроизведения | ≤ 2 сек |
| Потребление RAM | ≤ 150 MB в фоне |
| Потребление RAM (видео) | ≤ 300 MB |
| CPU в фоне (аудио) | ≤ 3% |

### Надёжность

- Retry logic: exponential backoff, максимум 5 попыток
- Graceful degradation: всегда показывать кэшированные данные при ошибке сети
- Crash-free rate: ≥ 99.5% (Firebase Crashlytics)
- ANR rate: ≤ 0.1%

### Безопасность

- Ссылки хранятся в зашифрованном виде (EncryptedSharedPreferences)
- Кэш медиа — plaintext (нет смысла шифровать публичный контент)
- Сетевые запросы только через HTTPS (кроме явного WebDAV http://)
- Нет аналитики поведения пользователя без явного согласия

### Доступность

- Поддержка TalkBack
- Минимальный размер tap target: 48×48dp
- Контрастность текста: WCAG AA
- Поддержка динамических шрифтов

---

## 8. Экраны и навигация

```
Bottom Navigation Bar:
  [🏠 Главная] [📁 Браузер] [🎵 Плейлисты] [🔍 Поиск] [⚙️ Настройки]

Навигационный граф:
  Home
    └─ SourceDetail → Browser → MediaPlayer
  Browser
    └─ FolderView → FolderView (nested) → MediaPlayer
  Playlists
    ├─ PlaylistList → PlaylistDetail → MediaPlayer
    └─ CreatePlaylist → PlaylistDetail
  Search
    └─ SearchResults → MediaPlayer / FolderView
  Settings
    ├─ CacheSettings
    ├─ PlayerSettings
    ├─ NetworkSettings
    └─ About

Persistent:
  MiniPlayer (snaps to bottom, above NavBar)
```

---

## 9. База данных (Room)

```sql
-- Источники
CREATE TABLE sources (
    id          TEXT PRIMARY KEY,
    url         TEXT NOT NULL,
    name        TEXT,
    provider    TEXT NOT NULL,     -- GDRIVE, YANDEX, DROPBOX...
    added_at    INTEGER NOT NULL,
    last_sync   INTEGER,
    is_pinned   INTEGER DEFAULT 0
);

-- Кэш файловых листингов
CREATE TABLE folder_cache (
    id          TEXT PRIMARY KEY,
    source_id   TEXT NOT NULL,
    path        TEXT NOT NULL,
    items_json  TEXT NOT NULL,     -- JSON массив CloudItem
    cached_at   INTEGER NOT NULL,
    etag        TEXT,
    FOREIGN KEY (source_id) REFERENCES sources(id)
);

-- Медиаметаданные
CREATE TABLE media_metadata (
    id          TEXT PRIMARY KEY,
    source_id   TEXT NOT NULL,
    path        TEXT NOT NULL,
    title       TEXT,
    artist      TEXT,
    album       TEXT,
    genre       TEXT,
    duration_ms INTEGER,
    size_bytes  INTEGER,
    mime_type   TEXT,
    thumb_path  TEXT,
    fetched_at  INTEGER NOT NULL
);

-- Плейлисты
CREATE TABLE playlists (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    cover_path  TEXT,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    is_smart    INTEGER DEFAULT 0,
    smart_rules TEXT               -- JSON для умных плейлистов
);

-- Треки в плейлистах
CREATE TABLE playlist_items (
    id          TEXT PRIMARY KEY,
    playlist_id TEXT NOT NULL,
    media_id    TEXT NOT NULL,
    position    INTEGER NOT NULL,
    added_at    INTEGER NOT NULL,
    FOREIGN KEY (playlist_id) REFERENCES playlists(id)
);

-- История воспроизведения
CREATE TABLE play_history (
    id          TEXT PRIMARY KEY,
    media_id    TEXT NOT NULL,
    played_at   INTEGER NOT NULL,
    duration_ms INTEGER,           -- сколько прослушали
    position_ms INTEGER            -- где остановились
);
```

---

## 10. Зависимости (Gradle)

```kotlin
// build.gradle.kts (app)

// Core
implementation("androidx.core:core-ktx:1.13.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")

// Compose
implementation(platform("androidx.compose:compose-bom:2024.05.00"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.navigation:navigation-compose:2.7.7")

// Media
implementation("androidx.media3:media3-exoplayer:1.3.1")
implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
implementation("androidx.media3:media3-ui:1.3.1")
implementation("androidx.media3:media3-session:1.3.1")
implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
implementation("androidx.media3:media3-extractor:1.3.1")

// Network
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.retrofit2:converter-gson:2.11.0")

// DI
implementation("com.google.dagger:hilt-android:2.51.1")
ksp("com.google.dagger:hilt-compiler:2.51.1")

// Database
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// Images
implementation("io.coil-kt:coil-compose:2.6.0")
implementation("io.coil-kt:coil-video:2.6.0")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.1.1")

// Security
implementation("androidx.security:security-crypto:1.1.0-alpha06")

// WorkManager (для фоновой загрузки)
implementation("androidx.work:work-runtime-ktx:2.9.0")
implementation("androidx.hilt:hilt-work:1.2.0")

// Crashlytics
implementation("com.google.firebase:firebase-crashlytics-ktx:19.0.0")
```

---

## 11. Поддержка облачных провайдеров — детали реализации

### Google Drive (публичные папки)

```kotlin
// Без OAuth — используем веб-скрапинг публичного API
// https://drive.google.com/drive/folders/{folderId}
// Или через неофициальный endpoint:

suspend fun listGDriveFolder(folderId: String): List<CloudItem> {
    // 1. GET https://drive.google.com/drive/folders/{id}
    // 2. Парсим HTML или используем:
    val url = "https://drive.google.com/list?id=$folderId&pageToken=..."
    // 3. Для прямого стриминга:
    // https://drive.google.com/uc?export=download&id={fileId}
    // Обработка redirect и confirm-токена для больших файлов
}
```

**Важно:** Google Drive может показывать Captcha/блокировать без OAuth.  
**Fallback:** Попросить пользователя использовать "Открытый доступ по ссылке" и предоставить direct download URL.

### Yandex Disk

```kotlin
// Официальный публичный API (без ключа):
// GET https://cloud-api.yandex.net/v1/disk/public/resources
//     ?public_key={encodedUrl}&path=/&limit=100&offset=0

data class YandexItem(
    val name: String,
    val type: String,        // "dir" | "file"
    val path: String,
    val size: Long?,
    val mime_type: String?,
    val file: String?        // Прямая ссылка для скачивания
)
// Для получения прямой ссылки на файл:
// GET /v1/disk/public/resources/download?public_key={key}&path={path}
```

### Dropbox

```kotlin
// Dropbox Shared Links — конвертируем в прямую ссылку
fun dropboxToDirectUrl(shareUrl: String): String {
    return shareUrl
        .replace("www.dropbox.com", "dl.dropboxusercontent.com")
        .replace("?dl=0", "?dl=1")
        .replace("?dl=1", "?dl=1") // уже прямая
}
// Для папок: используем Dropbox API v2 (без OAuth, только public folders)
// POST https://api.dropboxapi.com/2/sharing/get_shared_link_metadata
```

### HTTP Autoindex (nginx/apache)

```kotlin
// Парсим HTML страницу с листингом
// nginx autoindex формат:
// <a href="filename.mp3">filename.mp3</a>  2024-01-01 12:00    10M
// apache format: similar with <td> structure
// Используем Jsoup для парсинга

implementation("org.jsoup:jsoup:1.17.2")

suspend fun parseHttpIndex(url: String): List<CloudItem> {
    val html = okhttp.get(url).body?.string() ?: return emptyList()
    val doc = Jsoup.parse(html, url)
    return doc.select("a[href]")
        .filter { !it.attr("href").startsWith("?") && it.attr("href") != "../" }
        .map { link -> CloudItem(name = link.text(), url = link.absUrl("href")) }
}
```

---

## 12. Обработка сетевых ошибок

```kotlin
sealed class NetworkState {
    object Connected : NetworkState()
    object Slow : NetworkState()          // < 512 kbps
    object Unstable : NetworkState()      // > 20% packet loss
    object Offline : NetworkState()
}

// ExoPlayer LoadControl для адаптивного буферирования
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        minBufferMs = 15_000,       // минимум 15 сек в буфере
        maxBufferMs = 60_000,       // максимум 60 сек
        bufferForPlaybackMs = 2_500,            // начинаем при 2.5 сек
        bufferForPlaybackAfterRebufferMs = 5_000 // после rebuffer — 5 сек
    )
    .build()

// Retry policy
val retryPolicy = object : LoadErrorHandlingPolicy {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        return when (loadErrorInfo.errorCount) {
            1 -> 1_000L
            2 -> 2_000L
            3 -> 4_000L
            4 -> 8_000L
            else -> C.TIME_UNSET  // прекращаем попытки
        }
    }
}
```

---

## 13. Фазы разработки

### Phase 1 — MVP (6–8 недель)
- [ ] Добавление HTTP-ссылок и WebDAV
- [ ] Базовый браузер папок (список)
- [ ] Аудиоплеер с фоновым режимом
- [ ] Базовый кэш (OkHttp + ExoPlayer)
- [ ] Уведомление с управлением

### Phase 2 — Core Features (4–6 недель)
- [ ] Yandex Disk адаптер
- [ ] Dropbox адаптер
- [ ] Плейлисты (создание, управление)
- [ ] Видеоплеер с жестами
- [ ] Grid view + сортировка
- [ ] Поиск

### Phase 3 — Polish (4–5 недель)
- [ ] Google Drive адаптер
- [ ] OneDrive адаптер
- [ ] Умные плейлисты
- [ ] Эквалайзер
- [ ] Предзагрузка плейлистов
- [ ] Импорт/экспорт M3U8

### Phase 4 — Advanced (3–4 недели)
- [ ] Sleep timer, crossfade
- [ ] PiP для видео
- [ ] Субтитры
- [ ] Биометрическая защита
- [ ] Widgets (Android 12+)
- [ ] Auto/WearOS поддержка

---

## 14. Тестирование

### Unit Tests
- Все CloudProvider адаптеры — мок HTTP-ответов
- Парсер URL-ссылок
- LRU Cache логика
- Playlist use cases

### Integration Tests
- Room database миграции
- ExoPlayer + CacheDataSource
- WorkManager задачи

### UI Tests (Compose Testing)
- Навигация между экранами
- Браузер папок
- Создание плейлиста

### Manual QA Checklist
- [ ] Тест на медленном 2G (200 kbps)
- [ ] Тест с потерей пакетов 30%
- [ ] Тест с переключением WiFi ↔ Mobile Data
- [ ] Тест Battery Optimization (фоновое воспроизведение 2+ часа)
- [ ] Тест на Android 8.0 (minSDK)
- [ ] Тест на Android 15 (targetSDK)

---

## 15. Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<!-- Android 13+ для уведомлений -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- Для сохранения в галерею (скриншот кадра) -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

---

## 16. Глоссарий

| Термин | Определение |
|---|---|
| **CloudProvider** | Интерфейс-адаптер для конкретного облачного сервиса |
| **CloudItem** | Модель файла или папки в облаке |
| **CloudPath** | Составной путь: source_id + relative_path |
| **SimpleCache** | ExoPlayer кэш для медиаданных |
| **LRU** | Least Recently Used — стратегия вытеснения кэша |
| **Autoindex** | Функция nginx/apache — HTML-листинг директории |
| **M3U8** | Формат плейлиста, поддерживается как HLS и как статический список |
| **Range Request** | HTTP-запрос с заголовком Range для загрузки части файла |
| **ReplayGain** | Стандарт нормализации громкости в аудиофайлах |

---

*Документ является живой спецификацией. Обновляется по мере уточнения требований.*