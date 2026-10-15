package com.snaptubepro

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object YtDlpHelper {

    suspend fun getVideoInfo(context: Context, url: String): Result<VideoInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                })
                VideoInfo(
                    title = info.title ?: "Sin título",
                    duration = info.duration?.toLong() ?: 0L,
                    thumbnailUrl = info.thumbnail ?: "",
                    originalUrl = url
                )
            }
        }

    suspend fun downloadMp4(
        context: Context,
        url: String,
        destDir: File,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best")
                addOption("--merge-output-format", "mp4")
                addOption("-o", File(destDir, "%(title)s.%(ext)s").absolutePath)
            }
            YoutubeDL.getInstance().execute(request) { progress, _, line ->
                onProgress(line ?: "$progress%")
            }
            // Return the most recently modified mp4 in destDir
            destDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("No se encontró el archivo MP4 descargado")
        }
    }

    suspend fun downloadMp3(
        context: Context,
        url: String,
        destDir: File,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("-o", File(destDir, "%(title)s.%(ext)s").absolutePath)
            }
            YoutubeDL.getInstance().execute(request) { progress, _, line ->
                onProgress(line ?: "$progress%")
            }
            destDir.listFiles()
                ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("No se encontró el archivo MP3 descargado")
        }
    }
}
