package com.ericcatch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YtDlpInstaller {

    private const val TAG = "YtDlpInstaller"
    private const val PREF_NAME = "ericcatch_prefs"
    private const val KEY_LAST_UPDATE = "yt_dlp_last_update"
    // Actualizar como máximo una vez cada 24 horas
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

    suspend fun ensureInstalled(context: Context, onStatus: (String) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
            val now = System.currentTimeMillis()

            if (now - lastUpdate > UPDATE_INTERVAL_MS) {
                onStatus("Actualizando yt-dlp…")
                try {
                    val result = YoutubeDL.getInstance()
                        .updateYoutubeDL(context, UpdateChannel.STABLE)
                    when (result) {
                        YoutubeDL.UpdateStatus.DONE ->  {
                            Log.i(TAG, "yt-dlp actualizado correctamente")
                            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
                            onStatus("yt-dlp actualizado ✓")
                        }
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                            Log.i(TAG, "yt-dlp ya está al día")
                            prefs.edit().putLong(KEY_LAST_UPDATE, now).apply()
                            onStatus("Listo")
                        }
                        else -> {
                            Log.w(TAG, "Estado de actualización: $result")
                            onStatus("Listo")
                        }
                    }
                } catch (e: Exception) {
                    // No crashear si falla la actualización — la app sigue funcionando
                    Log.w(TAG, "No se pudo actualizar yt-dlp: ${e.message}")
                    onStatus("Listo (sin actualización)")
                }
            } else {
                onStatus("Listo")
            }
        }
}
