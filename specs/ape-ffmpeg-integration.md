# APE Playback via media3-decoder-ffmpeg

Adds native APE (Monkey's Audio) decoding inside ExoPlayer/Media3 by wiring in the
official FFmpeg decoder extension with pre-built native libraries.

---

## Why this is needed

ExoPlayer/Media3 does not include an APE extractor or decoder. The `media3-decoder-ffmpeg`
module provides a JNI bridge to FFmpeg that can act as a fallback for any format
ExoPlayer cannot handle natively — including APE. The Maven artifact
(`androidx.media3:media3-decoder-ffmpeg`) contains only the Java/Kotlin wrapper;
the pre-built native `.so` files must be obtained separately.

---

## Step 1 — Get the pre-built native libraries

### Option A: Download from the ExoPlayer demo release (simplest)

The ExoPlayer team publishes pre-built FFmpeg `.so` files as part of their demo app
releases on GitHub. They support the most common lossless codecs including APE.

1. Go to https://github.com/google/ExoPlayer/releases and find the release that
   matches Media3 1.3.1 (ExoPlayer 2.19.x era). The mapping is:
   - Media3 1.3.x ≈ ExoPlayer 2.19.x
2. Download `demo-ffmpeg-extension-debug.apk` or the corresponding release archive.
3. Extract the APK (it is a zip). The `.so` files are inside `lib/`:
   ```
   lib/arm64-v8a/libffmpeg.so
   lib/armeabi-v7a/libffmpeg.so
   lib/x86/libffmpeg.so
   lib/x86_64/libffmpeg.so
   ```
4. Copy them into the project:
   ```
   app/src/main/jniLibs/arm64-v8a/libffmpeg.so
   app/src/main/jniLibs/armeabi-v7a/libffmpeg.so
   app/src/main/jniLibs/x86/libffmpeg.so
   app/src/main/jniLibs/x86_64/libffmpeg.so
   ```

### Option B: Build from source (full control over enabled codecs)

Follow the official guide:
https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md

Key steps:
```bash
# Clone Media3 at the right tag
git clone --branch 1.3.1 https://github.com/androidx/media.git

cd media/libraries/decoder_ffmpeg/src/main/jni

# Download FFmpeg (revision pinned in the README)
./configure_ffmpeg.sh

# Build for arm64-v8a (repeat for other ABIs as needed)
cd ../../../../../..
./gradlew :lib-decoder-ffmpeg:assembleWithJNI \
    -Pandroid.abiFilters=arm64-v8a
```

The resulting `.so` is in
`libraries/decoder_ffmpeg/build/intermediates/merged_native_libs/`.

> Ensure NDK r25+ is installed and `ANDROID_NDK_HOME` is set before building.

---

## Step 2 — Add the Gradle dependency

In `gradle/libs.versions.toml`:
```toml
[versions]
# existing
media3 = "1.3.1"   # already present — do not change

[libraries]
# existing media3 entries ...
media3-decoder-ffmpeg = { group = "androidx.media3", name = "media3-decoder-ffmpeg", version.ref = "media3" }
```

In `app/build.gradle.kts`:
```kotlin
implementation(libs.media3.decoder.ffmpeg)
```

---

## Step 3 — Configure ExoPlayer to use FFmpeg as fallback

In `service/PlaybackService.kt`, replace the current
`DefaultMediaSourceFactory(mediaDataSourceFactory)` setup with one that includes
the FFmpeg extractor:

```kotlin
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer

// In createPlayer() (or wherever ExoPlayer is built):

// 1. Renderer factory that adds the FFmpeg audio renderer as an extension
val renderersFactory = DefaultRenderersFactory(this)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

// 2. Extractor factory — DefaultExtractorsFactory covers all built-in formats;
//    FfmpegAudioExtractor handles the rest (APE, TrueAudio, WavPack, etc.)
val extractorsFactory = DefaultExtractorsFactory()

val player = ExoPlayer.Builder(this)
    .setRenderersFactory(renderersFactory)
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(mediaDataSourceFactory, extractorsFactory)
    )
    .setLoadControl(loadControl)
    .setAudioAttributes(audioAttributes, handleAudioFocus = true)
    .setHandleAudioBecomingNoisy(true)
    .build()
```

`EXTENSION_RENDERER_MODE_PREFER` tells ExoPlayer to prefer the FFmpeg renderer over
the built-in MediaCodec renderer for formats that both can handle (safe default).
For APE, only FFmpeg can handle it, so the mode does not matter for that format.

---

## Step 4 — Verify APE codec is included in libffmpeg.so

If you built the `.so` from source, confirm APE support was compiled in:

```bash
nm -D libffmpeg.so | grep ape
# Should print symbols like: ape_decoder, ff_ape_decoder, etc.
```

If using the pre-built `.so` from the ExoPlayer demo, APE (`monkey's audio`) is
included by default.

---

## Step 5 — Test

1. Push an `.ape` file (or a CUE + APE pair) to a test cloud source or torrent.
2. Open the app → Browser → tap the file or the `.cue` sheet.
3. Expected: playback starts normally, seek works, track transitions work.
4. Check logcat for `FfmpegAudioRenderer` — it should log the codec being opened.

If ExoPlayer still falls back to an error, check:
- `libffmpeg.so` is in the correct `jniLibs` ABI directory.
- The `EXTENSION_RENDERER_MODE_PREFER` flag is set.
- Run `adb logcat | grep -i ffmpeg` to see initialization errors.

---

## Files to change summary

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | Add `media3-decoder-ffmpeg` library alias |
| `app/build.gradle.kts` | Add `implementation(libs.media3.decoder.ffmpeg)` |
| `app/src/main/jniLibs/*/libffmpeg.so` | Add pre-built native libs (not committed to git — use Git LFS or download script) |
| `service/PlaybackService.kt` | Set `EXTENSION_RENDERER_MODE_PREFER` + pass `DefaultExtractorsFactory` |

---

## Notes

- `libffmpeg.so` is 8–25 MB per ABI. Add `jniLibs/` to `.gitignore` and provide
  a `scripts/download_ffmpeg_libs.sh` for CI/other developers.
- APE compression levels 1–4 (Fast–High) decode in real time on modern devices.
  Level 5 (Insane) may cause stuttering on older hardware.
- The same FFmpeg extension also enables: TrueAudio (.tta), WavPack (.wv),
  AIFF, AC-3, E-AC-3, and more — no extra work needed.
