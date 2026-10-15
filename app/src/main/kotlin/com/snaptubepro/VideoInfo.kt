package com.snaptubepro

data class VideoInfo(
    val title: String,
    val duration: Long,       // seconds
    val thumbnailUrl: String,
    val originalUrl: String
) {
    /** Duration formatted as HH:MM:SS or MM:SS */
    fun formattedDuration(): String {
        val h = duration / 3600
        val m = (duration % 3600) / 60
        val s = duration % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }
}
