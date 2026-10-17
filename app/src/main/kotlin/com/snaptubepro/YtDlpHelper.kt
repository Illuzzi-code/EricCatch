package com.ericcatch

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object YtDlpHelper {

    private const val TAG = "YtDlpHelper"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Estrategia basada en la tabla oficial de PO Token enforcement (oct 2026):
     *
     *  web_embedded → No requiere PO Token, da formatos DASH con URLs directas.
     *                 Solo funciona en videos embebibles (>99% de YouTube).
     *  android_vr   → No requiere PO Token. Sin GVS token da solo format 18 (360p)
     *                 pero sirve como último fallback.
     *
     * Ref: https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
     */
    private fun extractorArgs() =
        "youtube:player_client=web_embedded,android_vr"

    /**
     * La librería youtubedl-android ya inyecta --js-runtimes quickjs internamente.
     * Este método es un no-op pero lo dejamos por claridad documental.
     */
    private fun YoutubeDLRequest.addJsRuntime(context: Context) {
        // youtubedl-android ya pasa --js-runtimes quickjs:libqjs.so en executeImpl()
        // No hay que agregarlo manualmente — hacerlo dos veces causa conflicto
        Log.d(TAG, "JS runtime handled by youtubedl-android library")
    }

    private fun YoutubeDLRequest.addCookiesIfAvailable(context: Context) {
        LoginActivity.getCookiesFile(context)?.let { f ->
            addOption("--cookies", f.absolutePath)
        }
    }

    // -------------------------------------------------------------------------
    // Búsqueda via Innertube API (HTTP directo, no usa yt-dlp)
    // -------------------------------------------------------------------------

    suspend fun search(context: Context, query: String): Result<List<SearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                    .openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("X-YouTube-Client-Name", "1")
                    setRequestProperty("X-YouTube-Client-Version", "2.20231121.01.00")
                }
                val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20231121.01.00","hl":"es","gl":"US"}},"query":"${query.replace("\"", "\\\"")}"}"""
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val json = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                conn.disconnect()
                parseInnertubeResults(json)
            }
        }

    private fun parseInnertubeResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val sections = JSONObject(json)
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (i in 0 until sections.length()) {
                val items = sections.getJSONObject(i)
                    .optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val vr = items.getJSONObject(j).optJSONObject("videoRenderer") ?: continue
                    val id = vr.optString("videoId").takeIf { it.length == 11 } ?: continue
                    val title = vr.optJSONObject("title")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Sin título"
                    val channel = vr.optJSONObject("ownerText")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                    val duration = vr.optJSONObject("lengthText")?.optString("simpleText") ?: ""
                    results.add(SearchResult(id, title,
                        "https://i.ytimg.com/vi/$id/hqdefault.jpg", duration, channel))
                    if (results.size >= 20) return results
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseInnertubeResults: ${e.message}")
        }
        return results
    }

    // -------------------------------------------------------------------------
    // Formatos disponibles
    // -------------------------------------------------------------------------

    suspend fun fetchFormats(context: Context, url: String): Result<List<FormatOption>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--dump-json")
                    addOption("--no-playlist")
                    addOption("--no-update")
                    addOption("--no-warnings")
                    addOption("--skip-download")
                    addOption("--extractor-args", extractorArgs())
                    addOption("--user-agent", UA)
                    addJsRuntime(context)
                    addCookiesIfAvailable(context)
                }
                val response = YoutubeDL.getInstance().execute(request, null, null)
                Log.d(TAG, "fetchFormats: out=${response.out?.length}b err=${response.err?.take(300)}")
                parseFormatsFromJson(response.out ?: "")
            }
        }

    private fun parseFormatsFromJson(raw: String): List<FormatOption> {
        val options = mutableListOf<FormatOption>()
        options.add(FormatOption(
            "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best",
            "⭐ Máxima calidad (auto)", FormatType.VIDEO))
        options.add(FormatOption("bestaudio/best", "⭐ Mejor audio (auto)", FormatType.AUDIO))

        try {
            val jsonLine = raw.lineSequence()
                .firstOrNull { it.trimStart().startsWith("{") } ?: return options

            val root = JSONObject(jsonLine)
            val formats = root.optJSONArray("formats") ?: return options
            Log.d(TAG, "Total formats in JSON: ${formats.length()}")

            val videoSeen = mutableSetOf<Int>()
            val audioSeen = mutableSetOf<String>()

            val formatList = (0 until formats.length())
                .map { formats.getJSONObject(it) }
                .sortedByDescending { it.optInt("height", 0) }

            for (fmt in formatList) {
                val id = fmt.optString("format_id")
                if (id.isBlank()) continue
                val vcodec = fmt.optString("vcodec", "none")
                val acodec = fmt.optString("acodec", "none")
                val height = fmt.optInt("height", 0)
                val ext = fmt.optString("ext", "")
                val tbr = fmt.optDouble("tbr", 0.0)
                val filesize = fmt.optLong("filesize", 0L)
                    .takeIf { it > 0 } ?: fmt.optLong("filesize_approx", 0L)

                val sizeLabel = when {
                    filesize > 1_073_741_824L -> "~%.1fGB".format(filesize / 1_073_741_824.0)
                    filesize > 1_048_576L     -> "~%.0fMB".format(filesize / 1_048_576.0)
                    filesize > 1_024L         -> "~%.0fKB".format(filesize / 1_024.0)
                    else                      -> ""
                }

                if (vcodec != "none" && height > 0) {
                    if (!videoSeen.contains(height)) {
                        videoSeen.add(height)
                        val label = buildString {
                            append("${height}p")
                            if (ext.isNotBlank() && ext != "none") append(" $ext")
                            if (tbr > 0) append(" · %.0fkbps".format(tbr))
                            if (sizeLabel.isNotBlank()) append(" · $sizeLabel")
                        }
                        options.add(FormatOption(id, label, FormatType.VIDEO))
                    }
                } else if (vcodec == "none" && acodec != "none") {
                    val abr = fmt.optDouble("abr", 0.0)
                    val abrKey = "%.0f".format(abr)
                    if (!audioSeen.contains(abrKey)) {
                        if (abr > 0) audioSeen.add(abrKey)
                        val label = buildString {
                            append("Audio $ext")
                            if (abr > 0) append(" · %.0fkbps".format(abr))
                            if (sizeLabel.isNotBlank()) append(" · $sizeLabel")
                        }
                        options.add(FormatOption(id, label, FormatType.AUDIO))
                    }
                }
            }
            Log.d(TAG, "Parsed ${options.size} options")
        } catch (e: Exception) {
            Log.e(TAG, "parseFormatsFromJson: ${e.message}")
        }
        return options
    }

    // -------------------------------------------------------------------------
    // Descarga
    // -------------------------------------------------------------------------

    suspend fun downloadWithFormat(
        context: Context,
        url: String,
        format: FormatOption,
        destDir: File,
        onProgress: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            val isAudio = format.type == FormatType.AUDIO
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-update")
                addOption("--extractor-args", extractorArgs())
                addOption("--user-agent", UA)
                addOption("--socket-timeout", "30")
                addOption("-R", "3")
                addOption("--fragment-retries", "3")
                addJsRuntime(context)
                addCookiesIfAvailable(context)

                if (isAudio) {
                    // web_embedded da streams DASH de audio separados con URLs directas.
                    // Pedimos el mejor audio disponible y ffmpeg lo convierte a MP3.
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                } else {
                    // web_embedded da DASH hasta 1080p sin PO Token.
                    // El selector pide mejor video + mejor audio m4a, con fallbacks.
                    val sel = when {
                        format.formatId.startsWith("bestvideo") ->
                            "${format.formatId}+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                        else ->
                            "${format.formatId}+bestaudio[ext=m4a]/${format.formatId}+bestaudio/best"
                    }
                    addOption("-f", sel)
                    addOption("--merge-output-format", "mp4")
                }
                addOption("-o", File(destDir, "%(title)s.%(ext)s").absolutePath)
            }

            YoutubeDL.getInstance().execute(request) { progress, _, line ->
                onProgress(line ?: "$progress%")
            }

            val ext = if (isAudio) "mp3" else "mp4"
            destDir.listFiles()
                ?.filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
                ?: throw IllegalStateException("No se encontró el archivo en $destDir")
        }
    }

    // -------------------------------------------------------------------------
    // Info de video (URLs directas)
    // -------------------------------------------------------------------------

    suspend fun getVideoInfo(context: Context, url: String): Result<VideoInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-update")
                    addOption("--extractor-args", extractorArgs())
                    addOption("--user-agent", UA)
                    addJsRuntime(context)
                    addCookiesIfAvailable(context)
                })
                VideoInfo(
                    title = info.title ?: "Sin título",
                    duration = info.duration?.toLong() ?: 0L,
                    thumbnailUrl = info.thumbnail ?: "",
                    originalUrl = url
                )
            }
        }
}
