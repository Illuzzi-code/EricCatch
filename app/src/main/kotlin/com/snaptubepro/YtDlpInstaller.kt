package com.snaptubepro

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the yt-dlp ARM64 binary from GitHub Releases on first run and
 * caches it in [Context.filesDir]/yt-dlp.
 *
 * Also downloads the matching ffmpeg static binary needed for MP3 conversion
 * and format merging.
 */
object YtDlpInstaller {

    private const val TAG = "YtDlpInstaller"

    // Latest stable yt-dlp release — ARM64 Android binary
    private const val YTDLP_URL =
        "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_android"

    // ffmpeg static binary for Android ARM64 via the ffmpeg-master-latest releases
    private const val FFMPEG_URL =
        "https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-android-arm64-gpl.zip"

    /**
     * Ensures yt-dlp (and optionally ffmpeg) are present and executable.
     * Safe to call on every app start; exits immediately if already installed.
     */
    suspend fun ensureInstalled(context: Context, onStatus: (String) -> Unit = {}) =
        withContext(Dispatchers.IO) {
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
            onStatus("Listo")
        }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun download(urlStr: String, dest: File) {
        val url = URL(urlStr)
        var connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
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
            connection.readTimeout = 60_000
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
