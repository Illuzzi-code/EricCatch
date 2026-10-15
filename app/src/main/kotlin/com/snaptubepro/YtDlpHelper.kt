package com.snaptubepro

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin wrapper around the yt-dlp binary bundled in the app's files directory.
 *
 * The binary must be copied to context.filesDir/yt-dlp before use.
 * [YtDlpInstaller] handles that step on first launch.
 */
object YtDlpHelper {

    private const val TAG = "YtDlpHelper"
    private val gson = Gson()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetch video metadata (title, duration, best thumbnail) without downloading.
     * Runs on [Dispatchers.IO].
     */
    suspend fun getVideoInfo(context: Context, url: String): Result<VideoInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val binary = binaryFile(context)
                val output = exec(
                    binary,
                    "--dump-json",
                    "--no-playlist",
                    "--no-warnings",
                    url
                )
                parseVideoInfo(output, url)
            }
        }

    /**
     * Download the best available MP4 (video+audio merged) to [destDir].
     * Progress lines are emitted via [onProgress].
     * Runs on [Dispatchers.IO].
     */
    suspend fun downloadMp4(
        context: Context,
        url: String,
        destDir: File,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val binary = binaryFile(context)
            destDir.mkdirs()
            val template = File(destDir, "%(title)s.%(ext)s").absolutePath
            execWithProgress(
                binary = binary,
                args = arrayOf(
                    "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
                    "--merge-output-format", "mp4",
                    "-o", template,
                    "--no-playlist",
                    url
                ),
                onLine = onProgress
            )
            // Return the newest file in destDir as the downloaded file
            destDir.listFiles()
                ?.filter { it.extension == "mp4" }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("Download finished but no MP4 found in $destDir")
        }
    }

    /**
     * Download best audio and convert to MP3 via the bundled ffmpeg.
     * Progress lines are emitted via [onProgress].
     * Runs on [Dispatchers.IO].
     */
    suspend fun downloadMp3(
        context: Context,
        url: String,
        destDir: File,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val binary = binaryFile(context)
            destDir.mkdirs()
            val template = File(destDir, "%(title)s.%(ext)s").absolutePath
            execWithProgress(
                binary = binary,
                args = arrayOf(
                    "-f", "bestaudio/best",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "-o", template,
                    "--no-playlist",
                    url
                ),
                onLine = onProgress
            )
            destDir.listFiles()
                ?.filter { it.extension == "mp3" }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("Download finished but no MP3 found in $destDir")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun binaryFile(context: Context): File {
        val f = File(context.filesDir, "yt-dlp")
        check(f.exists()) { "yt-dlp binary not found at ${f.absolutePath}. Run YtDlpInstaller first." }
        check(f.canExecute()) { "yt-dlp binary is not executable." }
        return f
    }

    private fun exec(binary: File, vararg args: String): String {
        val cmd = mutableListOf(binary.absolutePath).also { it.addAll(args) }
        Log.d(TAG, "exec: ${cmd.joinToString(" ")}")
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw RuntimeException("yt-dlp exited $code:\n$output")
        return output
    }

    private fun execWithProgress(binary: File, args: Array<String>, onLine: (String) -> Unit) {
        val cmd = mutableListOf(binary.absolutePath).also { it.addAll(args) }
        Log.d(TAG, "execWithProgress: ${cmd.joinToString(" ")}")
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().forEachLine { line ->
            Log.d(TAG, line)
            onLine(line)
        }
        val code = proc.waitFor()
        if (code != 0) throw RuntimeException("yt-dlp exited with code $code")
    }

    private fun parseVideoInfo(json: String, originalUrl: String): VideoInfo {
        // yt-dlp --dump-json returns one JSON object per entry.
        // Take only the first line in case of playlist leak.
        val firstLine = json.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalArgumentException("No JSON in yt-dlp output")
        val obj = gson.fromJson(firstLine, JsonObject::class.java)

        val title = obj.get("title")?.asString ?: "Unknown"
        val duration = obj.get("duration")?.asLong ?: 0L

        // Prefer the best thumbnail from the thumbnails array, fallback to top-level
        val thumbnail: String = run {
            val arr = obj.getAsJsonArray("thumbnails")
            if (arr != null && arr.size() > 0) {
                // yt-dlp orders thumbnails; last one is usually highest resolution
                arr.last().asJsonObject.get("url")?.asString
            } else null
        } ?: obj.get("thumbnail")?.asString ?: ""

        return VideoInfo(
            title = title,
            duration = duration,
            thumbnailUrl = thumbnail,
            originalUrl = originalUrl
        )
    }
}
