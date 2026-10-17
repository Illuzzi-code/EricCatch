package com.ericcatch

/**
 * Representa un formato de descarga disponible para un video.
 * @param formatId  ID interno de yt-dlp (ej. "137", "bestvideo")
 * @param label     Texto legible para el usuario (ej. "1080p MP4 · ~120 MB")
 * @param type      VIDEO o AUDIO
 */
data class FormatOption(
    val formatId: String,
    val label: String,
    val type: FormatType
)

enum class FormatType { VIDEO, AUDIO }
