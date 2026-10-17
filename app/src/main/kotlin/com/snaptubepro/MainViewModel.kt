package com.ericcatch

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class SearchResults(val results: List<SearchResult>) : UiState()
    data class Ready(val info: VideoInfo) : UiState()
    data class LoadingFormats(val info: VideoInfo) : UiState()
    data class SelectingQuality(val info: VideoInfo, val formats: List<FormatOption>) : UiState()
    data class Downloading(val message: String) : UiState()
    data class Done(val file: File, val mimeType: String) : UiState()
    data class Error(val message: String) : UiState()
}

data class SearchResult(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String,
    val duration: String,
    val channelName: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private var currentInfo: VideoInfo? = null

    // -------------------------------------------------------------------------
    // Búsqueda en YouTube (sin API key — usa el endpoint de sugerencias)
    // -------------------------------------------------------------------------

    fun search(query: String) {
        if (query.isBlank()) {
            _uiState.value = UiState.Error("Escribe algo para buscar")
            return
        }

        // Si es URL directa, ir directo a obtener info
        if (query.startsWith("http://") || query.startsWith("https://") ||
            query.startsWith("youtu.be") || query.startsWith("www.youtube")
        ) {
            fetchInfo(query)
            return
        }

        // Búsqueda usando yt-dlp ytsearch10: — estable, no depende de HTML scraping
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            YtDlpHelper.search(getApplication(), query)
                .onSuccess { results ->
                    if (results.isEmpty()) {
                        _uiState.value = UiState.Error("Sin resultados para \"$query\"")
                    } else {
                        _uiState.value = UiState.SearchResults(results)
                    }
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("Error al buscar: ${e.message}")
                }
        }
    }

    fun selectSearchResult(result: SearchResult) {
        // Construimos VideoInfo directamente con los datos del resultado de búsqueda
        // Esto evita llamar a yt-dlp para "obtener info" y el 429 que produce
        val url = "https://www.youtube.com/watch?v=${result.videoId}"
        val info = VideoInfo(
            title = result.title,
            duration = parseDurationToSeconds(result.duration),
            thumbnailUrl = result.thumbnailUrl,
            originalUrl = url
        )
        currentInfo = info
        _uiState.value = UiState.Ready(info)
    }

    private fun parseDurationToSeconds(duration: String): Long {
        if (duration.isBlank()) return 0L
        val parts = duration.split(":").map { it.toLongOrNull() ?: 0L }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    fun fetchInfo(url: String) {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            YtDlpHelper.getVideoInfo(getApplication(), url)
                .onSuccess { info ->
                    currentInfo = info
                    _uiState.value = UiState.Ready(info)
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("Error al obtener info: ${e.message}")
                }
        }
    }

    fun downloadMp4() {
        val info = currentInfo ?: return
        _uiState.value = UiState.LoadingFormats(info)
        viewModelScope.launch {
            YtDlpHelper.fetchFormats(getApplication(), info.originalUrl)
                .onSuccess { formats ->
                    val videoFormats = formats.filter { it.type == FormatType.VIDEO }
                    _uiState.value = UiState.SelectingQuality(info, videoFormats)
                }
                .onFailure {
                    // Si falla fetchFormats, mostrar opciones estáticas comunes
                    _uiState.value = UiState.SelectingQuality(info, staticVideoFormats())
                }
        }
    }

    fun downloadMp3() {
        val info = currentInfo ?: return
        _uiState.value = UiState.LoadingFormats(info)
        viewModelScope.launch {
            YtDlpHelper.fetchFormats(getApplication(), info.originalUrl)
                .onSuccess { formats ->
                    val audioFormats = formats.filter { it.type == FormatType.AUDIO }
                    _uiState.value = UiState.SelectingQuality(info, audioFormats)
                }
                .onFailure {
                    _uiState.value = UiState.SelectingQuality(info, staticAudioFormats())
                }
        }
    }

    private fun staticVideoFormats() = listOf(
        FormatOption("bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best[ext=mp4]/best",
                                                             "⭐ Máxima calidad (auto)",  FormatType.VIDEO),
        FormatOption("bestvideo[height<=2160][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=2160]+bestaudio/best",
                                                             "2160p 4K",                 FormatType.VIDEO),
        FormatOption("bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best",
                                                             "1080p Full HD",            FormatType.VIDEO),
        FormatOption("bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best",
                                                             "720p HD",                  FormatType.VIDEO),
        FormatOption("bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best",
                                                             "480p",                     FormatType.VIDEO),
        FormatOption("bestvideo[height<=360][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=360]+bestaudio/best",
                                                             "360p",                     FormatType.VIDEO),
    )

    private fun staticAudioFormats() = listOf(
        FormatOption("bestaudio/best",                    "⭐ Mejor calidad (auto)", FormatType.AUDIO),
        FormatOption("bestaudio[abr<=320]/best",          "Alta calidad ~320kbps",   FormatType.AUDIO),
        FormatOption("bestaudio[abr<=192]/best",          "Media calidad ~192kbps",  FormatType.AUDIO),
        FormatOption("bestaudio[abr<=128]/best",          "Baja calidad ~128kbps",   FormatType.AUDIO),
    )

    fun downloadWithFormat(format: FormatOption) {
        val info = currentInfo ?: return
        downloadDirect(info, format)
    }

    private fun downloadDirect(info: VideoInfo, format: FormatOption) {
        _uiState.value = UiState.Downloading("Preparando descarga…")
        viewModelScope.launch {
            val mimeType = if (format.type == FormatType.AUDIO) "audio/mpeg" else "video/mp4"
            YtDlpHelper.downloadWithFormat(
                context = getApplication(),
                url = info.originalUrl,
                format = format,
                destDir = downloadsDir(),
                onProgress = { line -> _uiState.postValue(UiState.Downloading(line)) }
            ).onSuccess { file ->
                _uiState.value = UiState.Done(file, mimeType)
            }.onFailure { e ->
                _uiState.value = UiState.Error("Error al descargar: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Directorio de descargas (público para que DownloadService lo use)
    // -------------------------------------------------------------------------

    fun getDownloadsDir(): File = downloadsDir()

    private fun downloadsDir(): File {
        // Guardar siempre en la carpeta Downloads pública — visible en cualquier explorador
        // Android 10+ permite escribir ahí sin permiso si usamos MediaStore,
        // pero para simplificar usamos getExternalStoragePublicDirectory que funciona en todos
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(pub, "EricCatch")
        if (dir.mkdirs() || dir.exists()) return dir
        // Fallback a carpeta privada si falla (ej. sin tarjeta SD)
        return File(
            getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "EricCatch"
        ).also { it.mkdirs() }
    }
}
