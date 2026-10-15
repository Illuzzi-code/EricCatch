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
 * The binary (and ffmpeg) must be installed via [YtDlpInstaller] before use.
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
                val ffmpegArgs = ffmpegLocationArgs(context)
                val output = exec(
                    binary,
                    *ffmpegArgs,
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
     * Requires ffmpeg for stream merging. Progress lines emitted via [onProgress].
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
            val ffmpegArgs = ffmpegLocationArgs(context)
            destDir.mkdirs()
            val template = File(destDir, "%(title)s.%(ext)s").absolutePath
            execWithProgress(
                binary = binary,
                args = arrayOf(
                    *ffmpegArgs,
                    "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
                    "--merge-output-format", "mp4",
                    "-o", template,
                    "--no-playlist",
                    "--print", "after_move:filepath",
                    url
                ),
                onLine = onProgress
            )
            // --print after_move:filepath emits the final path as the last output line
            resolveOutputFile(destDir, "mp4", onProgress)
        }
    }

    /**
     * Download best audio and convert to MP3 via the bundled ffmpeg.
     * Progress lines emitted via [onProgress].
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
            val ffmpegArgs = ffmpegLocationArgs(context)
            destDir.mkdirs()
            val template = File(destDir, "%(title)s.%(ext)s").absolutePath
            execWithProgress(
                binary = binary,
                args = arrayOf(
                    *ffmpegArgs,
                    "-f", "bestaudio/best",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", "0",
                    "-o", template,
                    "--no-playlist",
                    "--print", "after_move:filepath",
                    url
                ),
                onLine = onProgress
            )
            resolveOutputFile(destDir, "mp3", onProgress)
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers (internal so unit tests can access them)
    // -------------------------------------------------------------------------

    /**
     * Returns ["--ffmpeg-location", "<path>"] if ffmpeg is installed, empty array otherwise.
     */
    internal fun ffmpegLocationArgs(context: Context): Array<String> {
        val path = YtDlpInstaller.ffmpegPath(context) ?: return emptyArray()
        return arrayOf("--ffmpeg-location", path)
    }

    /**
     * Parses yt-dlp --dump-json output into a [VideoInfo].
     * Exposed as internal for unit testing.
     */
    internal fun parseVideoInfo(json: String, originalUrl: String): VideoInfo {
        // yt-dlp --dump-json returns one JSON object per entry.
        // Take only the first line in case of playlist leak.
        val firstLine = json.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalArgumentException("No JSON in yt-dlp output")
        val obj = gson.fromJson(firstLine, JsonObject::class.java)

        val title = obj.get("title")?.asString ?: "Unknown"
        val duration = obj.get("duration")?.asLong ?: 0L

        // Prefer the best thumbnail from the thumbnails array; fallback to top-level key
        val thumbnail: String = run {
            val arr = obj.getAsJsonArray("thumbnails")
            if (arr != null && arr.size() > 0) {
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

    /**
     * Builds the MP4 download argument list for a given url and output template.
     * Exposed as internal for unit testing.
     */
    internal fun buildMp4Args(url: String, template: String, ffmpegPath: String?): List<String> {
        val args = mutableListOf<String>()
        if (ffmpegPath != null) {
            args += listOf("--ffmpeg-location", ffmpegPath)
        }
        args += listOf(
            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
            "--merge-output-format", "mp4",
            "-o", template,
            "--no-playlist",
            "--print", "after_move:filepath",
            url
        )
        return args
    }

    /**
     * Builds the MP3 download argument list for a given url and output template.
     * Exposed as internal for unit testing.
     */
    internal fun buildMp3Args(url: String, template: String, ffmpegPath: String?): List<String> {
        val args = mutableListOf<String>()
        if (ffmpegPath != null) {
            args += listOf("--ffmpeg-location", ffmpegPath)
        }
        args += listOf(
            "-f", "bestaudio/best",
            "-x",
            "--audio-format", "mp3",
            "--audio-quality", "0",
            "-o", template,
            "--no-playlist",
            "--print", "after_move:filepath",
            url
        )
        return args
    }

    // -------------------------------------------------------------------------
    // Private helpers
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

    private fun execWithProgress(
        binary: File,
        args: Array<String>,
        onLine: (String) -> Unit
    ) {
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

    /**
     * After a download with --print after_move:filepath, the last progress line
     * should be the absolute file path. Fall back to newest file in [destDir].
     */
    private fun resolveOutputFile(
        destDir: File,
        extension: String,
        onProgress: (String) -> Unit
    ): File {
        // The onProgress collector in the ViewModel captures lines; we scan destDir directly
        return destDir.listFiles()
            ?.filter { it.isFile && it.extension.equals(extension, ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?: throw IllegalStateException("Download finished but no .$extension found in $destDir")
    }
}
