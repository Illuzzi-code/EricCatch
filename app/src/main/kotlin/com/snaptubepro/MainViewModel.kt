package com.snaptubepro

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.io.File

/** States the UI can be in */
sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Ready(val info: VideoInfo) : UiState()
    data class Downloading(val message: String) : UiState()
    data class Done(val file: File, val mimeType: String) : UiState()
    data class Error(val message: String) : UiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private var currentInfo: VideoInfo? = null

    // -------------------------------------------------------------------------
    // Actions called from MainActivity
    // -------------------------------------------------------------------------

    fun search(url: String) {
        if (url.isBlank()) {
            _uiState.value = UiState.Error("Por favor ingresa una URL")
            return
        }
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            YtDlpHelper.getVideoInfo(getApplication(), url)
                .onSuccess { info ->
                    currentInfo = info
                    _uiState.value = UiState.Ready(info)
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error(e.message ?: "Error desconocido")
                }
        }
    }

    fun downloadMp4() {
        val info = currentInfo ?: return
        _uiState.value = UiState.Downloading("Preparando descarga…")
        viewModelScope.launch {
            YtDlpHelper.downloadMp4(
                context = getApplication(),
                url = info.originalUrl,
                destDir = downloadsDir(),
                onProgress = { line ->
                    _uiState.postValue(UiState.Downloading(line))
                }
            )
                .onSuccess { file ->
                    _uiState.value = UiState.Done(file, "video/mp4")
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error(e.message ?: "Error al descargar MP4")
                }
        }
    }

    fun downloadMp3() {
        val info = currentInfo ?: return
        _uiState.value = UiState.Downloading("Preparando descarga…")
        viewModelScope.launch {
            YtDlpHelper.downloadMp3(
                context = getApplication(),
                url = info.originalUrl,
                destDir = downloadsDir(),
                onProgress = { line ->
                    _uiState.postValue(UiState.Downloading(line))
                }
            )
                .onSuccess { file ->
                    _uiState.value = UiState.Done(file, "audio/mpeg")
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error(e.message ?: "Error al descargar MP3")
                }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun downloadsDir(): File {
        // Prefer the public Downloads/SnapTubePro folder so files are visible
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(pub, "SnapTubePro")
        if (dir.mkdirs() || dir.exists()) return dir
        // Fallback to app-private storage
        return File(getApplication<Application>().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SnapTubePro")
            .also { it.mkdirs() }
    }
}
