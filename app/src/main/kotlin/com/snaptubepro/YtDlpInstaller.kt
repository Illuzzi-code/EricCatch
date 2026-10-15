package com.snaptubepro

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads the yt-dlp ARM64 binary and ffmpeg from GitHub Releases on first
 * run and caches them in [Context.filesDir].
 *
 * Paths exposed:
 *   - yt-dlp  → filesDir/yt-dlp
 *   - ffmpeg  → filesDir/ffmpeg
 */
object YtDlpInstaller {

    private const val TAG = "YtDlpInstaller"

    // Latest stable yt-dlp release — ARM64 Android binary (runs without Python)
    private const val YTDLP_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android"

    // ffmpeg static build for Android ARM64 — needed for MP3 conversion and format merging
    private const val FFMPEG_URL =
        "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-android-arm64-gpl.zip"

    /**
     * Ensures yt-dlp and ffmpeg are present and executable.
     * Safe to call on every app start; exits immediately if already installed.
     */
    suspend fun ensureInstalled(context: Context, onStatus: (String) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            ensureYtDlp(context, onStatus)
            ensureFfmpeg(context, onStatus)
            onStatus("Listo")
        }

    /** Returns the ffmpeg binary path, or null if not yet installed. */
    fun ffmpegPath(context: Context): String? {
        val f = File(context.filesDir, "ffmpeg")
        return if (f.exists() && f.canExecute()) f.absolutePath else null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun ensureYtDlp(context: Context, onStatus: (String) -> Unit) {
        val ytDlp = File(context.filesDir, "yt-dlp")
        if (!ytDlp.exists() || ytDlp.length() < 1_000_000L) {
            onStatus("Instalando yt-dlp…")
            Log.i(TAG, "Downloading yt-dlp binary")
            download(YTDLP_URL, ytDlp)
            ytDlp.setExecutable(true, false)
            Log.i(TAG, "yt-dlp installed at ${ytDlp.absolutePath} (${ytDlp.length()} bytes)")
        } else {
            Log.i(TAG, "yt-dlp already present")
        }
    }

    private fun ensureFfmpeg(context: Context, onStatus: (String) -> Unit) {
        val ffmpeg = File(context.filesDir, "ffmpeg")
        if (!ffmpeg.exists() || ffmpeg.length() < 1_000_000L) {
            onStatus("Instalando ffmpeg…")
            Log.i(TAG, "Downloading ffmpeg zip")
            val zipFile = File(context.filesDir, "ffmpeg.zip")
            try {
                download(FFMPEG_URL, zipFile)
                extractFfmpegFromZip(zipFile, ffmpeg)
                ffmpeg.setExecutable(true, false)
                Log.i(TAG, "ffmpeg installed at ${ffmpeg.absolutePath} (${ffmpeg.length()} bytes)")
            } finally {
                zipFile.delete()
            }
        } else {
            Log.i(TAG, "ffmpeg already present")
        }
    }

    /**
     * The ffmpeg zip contains bin/ffmpeg inside a top-level directory.
     * We extract only that binary.
     */
    private fun extractFfmpegFromZip(zipFile: File, dest: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Match paths like "ffmpeg-master-.../bin/ffmpeg" (no .exe suffix)
                if (!entry.isDirectory &&
                    entry.name.endsWith("/bin/ffmpeg") &&
                    !entry.name.endsWith(".exe")
                ) {
                    dest.outputStream().use { out -> zis.copyTo(out) }
                    zis.closeEntry()
                    return
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        check(dest.exists()) { "ffmpeg binary not found inside zip ${zipFile.name}" }
    }

    private fun download(urlStr: String, dest: File) {
        val url = URL(urlStr)
        var connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.connect()

        // Follow redirects manually for cross-protocol safety
        var responseCode = connection.responseCode
        var redirectCount = 0
        while ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == 307 || responseCode == 308) && redirectCount < 10
        ) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            connection = URL(location).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.connect()
            responseCode = connection.responseCode
            redirectCount++
        }

        check(responseCode == HttpURLConnection.HTTP_OK) {
            "HTTP $responseCode when downloading $urlStr"
        }

        dest.outputStream().use { out ->
            connection.inputStream.use { it.copyTo(out) }
        }
        connection.disconnect()
    }
}
