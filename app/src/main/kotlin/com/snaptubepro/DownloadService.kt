package com.ericcatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * ForegroundService que ejecuta descargas en background.
 * La Activity se suscribe via Binder para recibir actualizaciones en tiempo real.
 * Si la Activity se destruye, la descarga sigue corriendo y notifica por notificación.
 */
class DownloadService : Service() {

    // ── Canal y constantes ────────────────────────────────────────────────────
    companion object {
        const val CHANNEL_ID        = "ericcatch_downloads"
        const val NOTIF_ID          = 1001
        const val ACTION_CANCEL     = "com.ericcatch.CANCEL_DOWNLOAD"
        private const val TAG       = "DownloadService"

        // Regex para parsear la línea de progreso de yt-dlp:
        // "[download]  45.3% of 23.45MiB at 1.23MiB/s ETA 00:15"
        private val PROGRESS_RE =
            Regex("""(\d+\.?\d*)%\s+of\s+([\d.]+\s*\w+)(?:\s+at\s+([\d.]+\s*\w+/s))?""")
    }

    // ── Binder ────────────────────────────────────────────────────────────────
    inner class DownloadBinder : Binder() {
        fun getService() = this@DownloadService
    }
    private val binder = DownloadBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── Estado ────────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    /** Callback hacia la Activity (null cuando no está visible) */
    var progressCallback: ((DownloadProgress) -> Unit)? = null

    private lateinit var notifManager: NotificationManager
    private lateinit var notifBuilder: NotificationCompat.Builder

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelDownload()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── API pública ───────────────────────────────────────────────────────────

    fun startDownload(
        url: String,
        format: FormatOption,
        title: String,
        destDir: File
    ) {
        if (downloadJob?.isActive == true) return   // ya hay una descarga en curso

        val cancelIntent = Intent(this, DownloadService::class.java)
            .apply { action = ACTION_CANCEL }
        val cancelPi = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        notifBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_title_downloading))
            .setContentText(title)
            .setOngoing(true)
            .setProgress(100, 0, true)   // indeterminado al inicio
            .addAction(android.R.drawable.ic_delete,
                getString(R.string.notif_action_cancel), cancelPi)
            .setOnlyAlertOnce(true)

        startForeground(NOTIF_ID, notifBuilder.build())

            downloadJob = scope.launch {
                YtDlpHelper.downloadWithFormat(
                    context    = this@DownloadService,
                    url        = url,
                    format     = format,
                    destDir    = destDir,
                    onProgress = { line ->
                        val progress = parseProgress(line, title)
                        progressCallback?.invoke(progress)
                        updateNotification(progress)
                    }
                ).onSuccess { file ->
                    val done = DownloadProgress.Done(
                        file,
                        if (format.type == FormatType.AUDIO) "audio/mpeg" else "video/mp4"
                    )
                    progressCallback?.invoke(done)
                    notifyDone(title, file)
                }.onFailure { e ->
                    val err = DownloadProgress.Error(e.message ?: "Error desconocido")
                    progressCallback?.invoke(err)
                    notifyError(title, e.message)
                }
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        progressCallback?.invoke(DownloadProgress.Cancelled)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Download cancelled by user")
    }

    fun isDownloading() = downloadJob?.isActive == true

    // ── Parseo de progreso ────────────────────────────────────────────────────

    private fun parseProgress(line: String, title: String): DownloadProgress {
        // Línea especial de conversión de ffmpeg
        if (line.contains("[ExtractAudio]")) return DownloadProgress.Converting(title)
        if (line.contains("[Merger]") || line.contains("[ffmpeg]"))
            return DownloadProgress.Merging(title)

        val match = PROGRESS_RE.find(line)
        if (match != null) {
            val pct    = match.groupValues[1].toFloatOrNull() ?: 0f
            val size   = match.groupValues[2]
            val speed  = match.groupValues[3].takeIf { it.isNotBlank() } ?: ""
            return DownloadProgress.InProgress(
                percent = pct.toInt(),
                size    = size,
                speed   = speed,
                title   = title
            )
        }
        return DownloadProgress.InProgress(percent = 0, size = "", speed = "", title = title)
    }

    // ── Notificaciones ────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setSound(null, null)
                enableVibration(false)
            }
            notifManager.createNotificationChannel(ch)
        }
    }

    private fun updateNotification(progress: DownloadProgress) {
        val (text, pct, indeterminate) = when (progress) {
            is DownloadProgress.InProgress -> {
                val t = buildString {
                    append("${progress.percent}%")
                    if (progress.size.isNotBlank()) append(" · ${progress.size}")
                    if (progress.speed.isNotBlank()) append(" · ${progress.speed}")
                }
                Triple(t, progress.percent, false)
            }
            is DownloadProgress.Converting -> Triple("Convirtiendo a MP3…", 100, true)
            is DownloadProgress.Merging    -> Triple("Fusionando pistas…",   100, true)
            else -> return
        }
        val notif = notifBuilder
            .setContentText(text)
            .setProgress(100, pct, indeterminate)
            .build()
        notifManager.notify(NOTIF_ID, notif)
    }

    private fun notifyDone(title: String, file: File) {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_title_done))
            .setContentText(file.name)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        notifManager.notify(NOTIF_ID + 1, notif)
    }

    private fun notifyError(title: String, msg: String?) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.notif_title_error))
            .setContentText(msg ?: "Error desconocido")
            .setAutoCancel(true)
            .build()
        notifManager.notify(NOTIF_ID + 2, notif)
    }
}

// ── Sealed class de progreso ──────────────────────────────────────────────────

sealed class DownloadProgress {
    data class InProgress(
        val percent: Int,
        val size:    String,
        val speed:   String,
        val title:   String
    ) : DownloadProgress()
    data class Converting(val title: String) : DownloadProgress()
    data class Merging(val title: String)    : DownloadProgress()
    data class Done(val file: File, val mimeType: String) : DownloadProgress()
    data class Error(val message: String)    : DownloadProgress()
    object Cancelled                         : DownloadProgress()
}
