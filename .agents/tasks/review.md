# SnapTube Pro — Android Kotlin yt-dlp Downloader

This app lets users paste a video URL, see a thumbnail preview with title and duration, then download either the best MP4 or best MP3 quality using the yt-dlp binary downloaded on first run. The architecture is straightforward: a ViewModel drives a `UiState` sealed class observed by `MainActivity`, with `VideoRepository` / `YtDlpHelper` handling the subprocess work.

**Watch for:** Two parallel implementations of the entire app coexist in `src/main/java/` and `src/main/kotlin/` with different class contracts — the Kotlin compiler will compile both and the resulting class name collisions will cause a build failure. The `java/` tree's `UiState` (7-case StateFlow version) is incompatible with the `kotlin/` tree's `UiState` (6-case LiveData version); the `java/` tree references `YtDlpManager` and `DownloadFormat` classes that do not exist in `kotlin/`, while `kotlin/` references `YtDlpInstaller` and `YtDlpHelper` that don't exist in `java/`. The project cannot compile as-is. Additionally, the yt-dlp binary URL in `YtDlpManager.kt` downloads the wrong binary for Android (the generic Linux x86-64 build instead of `yt-dlp_android`).

**Verdict**: NEEDS_CHANGES

---

## High-level view

The project has two entirely separate implementations of every class in `src/main/java/` and `src/main/kotlin/`. Both directories are on the default Android source set, so Gradle compiles them together. Duplicate `package com.snaptubepro` declarations for `MainActivity`, `MainViewModel`, `VideoInfo`, and `UiState` will cause a "duplicate class" build error before a single line of app logic runs. One implementation must be deleted entirely.

The `java/` tree (`MainViewModel` + `VideoRepository` + `YtDlpManager`) uses StateFlow and references `YtDlpManager.getBinaryPath()`, `YtDlpManager.ensureBinaryExists()`, and `DownloadFormat`. The `kotlin/` tree (`MainViewModel` + `YtDlpHelper` + `YtDlpInstaller`) uses LiveData and references entirely different helper objects. These are not compatible versions of each other; they are two different designs. The `java/` version is more complete (has `DownloadFormat` enum, `VideoRepository`, proper output-path detection via `--print after_move:filepath`), but `YtDlpManager` downloads the wrong binary. The `kotlin/` version gets the binary URL right (`yt-dlp_android`) but the `MainViewModel` calls `YtDlpHelper` which doesn't exist in `java/`.

`YtDlpManager.kt` (java tree) downloads from `yt-dlp/releases/latest/download/yt-dlp`, which is the generic Linux ELF binary and will not run on Android ARM64. `YtDlpInstaller.kt` (kotlin tree) correctly uses `yt-dlp_android`. If the `java/` tree is kept, this URL must be fixed.

The `file_paths.xml` FileProvider config declares both an `external-files-path` (scoped to the app's own external storage) and an `external-path` (broad access to `Downloads/SnapTubePro/`). The `java/` tree saves files to `app-specific external dirs` on API 29+, but the `kotlin/` tree saves to the public `Downloads/` folder on all API levels. If the public path is used and shared via FileProvider, the `external-path` entry is correct — but this needs to match whichever implementation survives.

The `WRITE_EXTERNAL_STORAGE` permission is declared with `maxSdkVersion="29"`, which is correct. The manifest also requests `POST_NOTIFICATIONS` but there is no notification code in either implementation — the permission is declared but never used. On Android 13+ the system will request it from the user unnecessarily.

The `AndroidManifest.xml` hardcodes the FileProvider authority as `com.snaptubepro.fileprovider`. The `kotlin/` tree's `MainActivity` uses `${packageName}.fileprovider` (correct, dynamic), while the `java/` tree hardcodes `com.snaptubepro.fileprovider` (correct, but brittle to an applicationId change).

There are no tests of any kind — no unit tests for JSON parsing, no instrumented tests for the ViewModel state machine, no tests for the yt-dlp argument construction. For a downloader app where the subprocess invocation and JSON parsing are the two most failure-prone paths, this is a significant coverage gap.

---

<details>
<summary>Issues (7)</summary>

1. **Duplicate class collision — build-breaking** — Both `src/main/java/` and `src/main/kotlin/` contain `MainActivity`, `MainViewModel`, `VideoInfo`, and `UiState` in the same package. The Kotlin compiler compiles both directories by default and will emit "duplicate class" errors. One tree must be deleted entirely before the project can build.

2. **Wrong yt-dlp binary URL** — `YtDlpManager.kt` downloads `yt-dlp` (generic Linux x86-64 ELF), which will not execute on Android ARM64. The correct URL is `yt-dlp_android` (as used in `YtDlpInstaller.kt`). Change `YTDLP_URL` in `YtDlpManager.kt` to `https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android`.

3. **Cross-tree class references — compile error** — `java/MainViewModel.kt` calls `YtDlpManager` and `DownloadFormat` (defined only in `java/`), while `kotlin/MainViewModel.kt` calls `YtDlpHelper` and `YtDlpInstaller` (defined only in `kotlin/`). After resolving the duplicate-class issue by picking one tree, all cross-references to the deleted tree must be cleaned up or the compiler will still fail with "unresolved reference".

4. **`UiState` contract mismatch** — The `java/` tree defines a 7-state `UiState` (StateFlow) including `PreparingBinary` and `DownloadComplete(filePath: String)`. The `kotlin/` tree defines a 6-state `UiState` (LiveData) including `Ready(info: VideoInfo)` and `Done(file: File, mimeType: String)`. These are incompatible. Whichever tree survives must have its `UiState` fully handled in the surviving `MainActivity` — partial handling means the `when` expression will produce an unchecked branch warning and the unhandled state will silently no-op.

5. **`POST_NOTIFICATIONS` permission declared but never used** — Neither implementation sends any notification. Android 13+ will prompt the user for this permission at runtime unnecessarily. Remove the `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` declaration from the manifest, or add a download-complete notification to justify it.

6. **ffmpeg not installed by either implementation** — `YtDlpInstaller.kt` declares a `FFMPEG_URL` constant pointing to a `.zip` archive but never downloads or unpacks it. Neither `YtDlpManager` nor `YtDlpHelper` specifies `--ffmpeg-location` when invoking yt-dlp. MP3 extraction (`-x --audio-format mp3`) and MP4 format merging (`--merge-output-format mp4`) both require ffmpeg. Without it, yt-dlp will either fall back to a single-stream download or fail entirely, silently defeating the "max quality" requirement.

7. **No tests** — There are no unit or instrumented tests. The JSON parsing logic in `VideoRepository.fetchVideoInfo` and `YtDlpHelper.parseVideoInfo` is the most likely source of runtime failures (yt-dlp output format changes, missing keys, playlist JSON), and neither path has any test coverage. Add unit tests for at least the JSON parsing and the yt-dlp argument construction for both MP4 and MP3 formats.

</details>

---

<details>
<summary>Details</summary>

## Duplicate source trees — build-breaking collision

Android's default Gradle source set includes both `src/main/java/` and `src/main/kotlin/` as Kotlin source roots. Every class in `com.snaptubepro` is defined twice:

```
src/main/java/com/snaptubepro/
  MainActivity.kt       ← different from kotlin/ version
  MainViewModel.kt      ← different UiState, StateFlow, calls YtDlpManager
  VideoInfo.kt          ← different fields (webpage_url, ext vs originalUrl)
  VideoRepository.kt    ← only in java/
  YtDlpManager.kt       ← only in java/

src/main/kotlin/com/snaptubepro/
  MainActivity.kt       ← LiveData observer, calls YtDlpInstaller
  MainViewModel.kt      ← different UiState, LiveData, calls YtDlpHelper
  VideoInfo.kt          ← different fields
  YtDlpHelper.kt        ← only in kotlin/
  YtDlpInstaller.kt     ← only in kotlin/
```

The Kotlin compiler will report duplicate class definitions for `MainActivity`, `MainViewModel`, `VideoInfo`, and `UiState`. The build will not succeed. The resolution is to pick one tree and delete the other. The `java/` tree has the better download-path detection (`--print after_move:filepath`) and a cleaner `VideoRepository` abstraction, but has the wrong binary URL. The `kotlin/` tree has the correct binary URL and a cleaner installer. Either is fixable, but both cannot coexist.

## Wrong yt-dlp binary for Android (java tree)

`YtDlpManager.YTDLP_URL` points to:
```
https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp
```
This is the generic Linux ELF binary compiled for x86-64. Android ARM64 devices will get `Exec format error` when yt-dlp is invoked. The correct artifact is `yt-dlp_android`, which is the statically linked ARM64 build that runs on Android without a system Python:
```
https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android
```
`YtDlpInstaller.kt` (kotlin tree) already has the correct URL.

## ffmpeg gap — MP3 and best-quality MP4 silently degrade

`YtDlpInstaller.ensureInstalled` declares:
```kotlin
private const val FFMPEG_URL =
    "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-android-arm64-gpl.zip"
```
but never downloads or unpacks it. Neither invocation path passes `--ffmpeg-location` to yt-dlp.

Without ffmpeg:
- `-x --audio-format mp3` will fail on sites that only serve non-native formats (yt-dlp needs ffmpeg to transcode to MP3).
- `-f bestvideo+bestaudio` will fail because merging separate video and audio streams requires ffmpeg; yt-dlp falls back to a single-stream download which is typically 360p or 480p.

The "max quality" requirement is the core feature. This gap defeats it silently — yt-dlp exits with an error or falls back without a clear user-facing message.

## `VideoInfo` field incompatibility between trees

`java/VideoInfo` has fields `webpage_url` and `ext`. `kotlin/VideoInfo` has `originalUrl` and no `ext`. `java/MainViewModel.download()` accesses `info.webpage_url`. `kotlin/MainViewModel.downloadMp4()` accesses `info.originalUrl`. Whichever tree survives, the `VideoInfo` model used throughout must be consistent. This is confirmed by reading both files — the field names differ and are not interchangeable.

## FileProvider path scope

`file_paths.xml` declares:
```xml
<external-files-path name="downloads" path="Downloads/" />
<external-path name="external_downloads" path="Downloads/SnapTubePro/" />
```

The `java/` tree saves to `getExternalFilesDir(DIRECTORY_DOWNLOADS)/SnapTubePro/` on API 29+, which is covered by `external-files-path`. The `kotlin/` tree always saves to `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/SnapTubePro/`, which is covered by `external-path`. Both paths are declared, so whichever implementation survives, the FileProvider will be able to serve the file. However, `external-path` grants broad read access to the public Downloads directory, which is wider than necessary if the `java/` tree's scoped path is used.

## `open` string resource referenced incorrectly

`kotlin/MainActivity.kt` calls `getString(R.string.open)` to label the "open file" intent chooser. In Java, `open` is a reserved keyword; the resource name `open` is legal in XML but compiles to `R.string.open` in Kotlin without issue — this is fine. The `java/MainActivity.kt` also uses `getString(R.string.open)` correctly. Confirmed in `strings.xml`.

</details>

---

<details>
<summary>File map</summary>

| File | What's there |
|------|-------------|
| `app/src/main/java/com/snaptubepro/MainActivity.kt` | StateFlow-based Activity; references `DownloadFormat`, `YtDlpManager`; has `PreparingBinary` + `DownloadComplete` states |
| `app/src/main/java/com/snaptubepro/MainViewModel.kt` | StateFlow ViewModel; 7-state `UiState`; calls `VideoRepository` + `YtDlpManager` |
| `app/src/main/java/com/snaptubepro/VideoInfo.kt` | Data class with `webpage_url`, `ext` fields |
| `app/src/main/java/com/snaptubepro/VideoRepository.kt` | Subprocess wrapper; `fetchVideoInfo` + `downloadVideo`; uses `--print after_move:filepath` |
| `app/src/main/java/com/snaptubepro/YtDlpManager.kt` | Binary downloader; **wrong URL** (Linux ELF, not `yt-dlp_android`) |
| `app/src/main/kotlin/com/snaptubepro/MainActivity.kt` | LiveData-based Activity; references `YtDlpInstaller`; 6-state `UiState` handler |
| `app/src/main/kotlin/com/snaptubepro/MainViewModel.kt` | LiveData ViewModel; calls `YtDlpHelper` + `YtDlpInstaller` |
| `app/src/main/kotlin/com/snaptubepro/VideoInfo.kt` | Data class with `originalUrl` field; no `ext` |
| `app/src/main/kotlin/com/snaptubepro/YtDlpHelper.kt` | Subprocess wrapper; `getVideoInfo`, `downloadMp4`, `downloadMp3` |
| `app/src/main/kotlin/com/snaptubepro/YtDlpInstaller.kt` | Binary downloader; correct `yt-dlp_android` URL; `FFMPEG_URL` declared but never used |
| `app/src/main/res/layout/activity_main.xml` | Full UI with toolbar, URL input, video info card, download buttons, progress bar |
| `app/src/main/AndroidManifest.xml` | INTERNET, storage permissions, FileProvider; `POST_NOTIFICATIONS` unused |
| `app/src/main/res/xml/file_paths.xml` | Both scoped and public external paths declared |
| `app/build.gradle.kts` | AGP 8.3.2, Kotlin 1.9.23, viewBinding enabled; all lib aliases resolve correctly |
| `gradle/libs.versions.toml` | All referenced aliases present; `gsongson` version ref for Gson |

</details>
