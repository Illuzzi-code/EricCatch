package com.snaptubepro

import android.content.Context

object YtDlpInstaller {
    suspend fun ensureInstalled(context: Context, onStatus: (String) -> Unit = {}) {
        // The library is already initialized in Application.onCreate()
        // Optionally update yt-dlp to latest on first run
        onStatus("Listo")
    }
}
