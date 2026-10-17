package com.ericcatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [YtDlpHelper] JSON parsing and argument construction.
 *
 * These run on the JVM (no Android framework needed) because the tested
 * functions only use Gson and plain Kotlin collections.
 */
class YtDlpHelperTest {

    // -------------------------------------------------------------------------
    // parseVideoInfo
    // -------------------------------------------------------------------------

    @Test
    fun `parseVideoInfo extracts title duration and thumbnail`() {
        val json = """
            {"title":"My Video","duration":305,"thumbnail":"https://example.com/thumb.jpg",
             "webpage_url":"https://example.com/watch?v=abc"}
        """.trimIndent()

        val info = YtDlpHelper.parseVideoInfo(json, "https://example.com/watch?v=abc")

        assertEquals("My Video", info.title)
        assertEquals(305L, info.duration)
        assertEquals("https://example.com/thumb.jpg", info.thumbnailUrl)
        assertEquals("https://example.com/watch?v=abc", info.originalUrl)
    }

    @Test
    fun `parseVideoInfo prefers last entry in thumbnails array`() {
        val json = """
            {"title":"Test","duration":60,
             "thumbnails":[
               {"url":"https://example.com/low.jpg"},
               {"url":"https://example.com/high.jpg"}
             ]}
        """.trimIndent()

        val info = YtDlpHelper.parseVideoInfo(json, "https://example.com/v")

        assertEquals("https://example.com/high.jpg", info.thumbnailUrl)
    }

    @Test
    fun `parseVideoInfo falls back to empty thumbnail when none present`() {
        val json = """{"title":"No Thumb","duration":10}"""

        val info = YtDlpHelper.parseVideoInfo(json, "https://example.com/v")

        assertEquals("", info.thumbnailUrl)
    }

    @Test
    fun `parseVideoInfo uses defaults for missing fields`() {
        val json = """{}"""

        val info = YtDlpHelper.parseVideoInfo(json, "https://fallback.url")

        assertEquals("Unknown", info.title)
        assertEquals(0L, info.duration)
        assertEquals("https://fallback.url", info.originalUrl)
    }

    @Test
    fun `parseVideoInfo handles multiline output by taking first JSON line`() {
        // Simulates a yt-dlp run that prints a log line before JSON
        val json = """
            some debug line
            {"title":"Real","duration":42,"thumbnail":"https://t.co/img.jpg"}
        """.trimIndent()

        val info = YtDlpHelper.parseVideoInfo(json, "https://example.com/v")

        assertEquals("Real", info.title)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseVideoInfo throws on empty output`() {
        YtDlpHelper.parseVideoInfo("", "https://example.com/v")
    }

    // -------------------------------------------------------------------------
    // buildMp4Args
    // -------------------------------------------------------------------------

    @Test
    fun `buildMp4Args includes ffmpeg-location when ffmpegPath is provided`() {
        val args = YtDlpHelper.buildMp4Args(
            url = "https://example.com/v",
            template = "/sdcard/%(title)s.%(ext)s",
            ffmpegPath = "/data/data/com.ericcatch/files/ffmpeg"
        )

        assertTrue("--ffmpeg-location should be present",
            args.contains("--ffmpeg-location"))
        assertEquals(
            "/data/data/com.ericcatch/files/ffmpeg",
            args[args.indexOf("--ffmpeg-location") + 1]
        )
    }

    @Test
    fun `buildMp4Args omits ffmpeg-location when ffmpegPath is null`() {
        val args = YtDlpHelper.buildMp4Args(
            url = "https://example.com/v",
            template = "/sdcard/%(title)s.%(ext)s",
            ffmpegPath = null
        )

        assertTrue("--ffmpeg-location must not appear", !args.contains("--ffmpeg-location"))
    }

    @Test
    fun `buildMp4Args always requests bestvideo+bestaudio and mp4 merge`() {
        val args = YtDlpHelper.buildMp4Args("https://example.com/v", "/tmp/out", null)

        val fIndex = args.indexOf("-f")
        assertTrue("-f flag must be present", fIndex >= 0)
        assertTrue("format selector must prefer bestvideo+bestaudio",
            args[fIndex + 1].contains("bestvideo+bestaudio"))
        assertTrue("--merge-output-format mp4 must be present",
            args.contains("--merge-output-format") &&
            args[args.indexOf("--merge-output-format") + 1] == "mp4")
    }

    @Test
    fun `buildMp4Args includes --print after_move filepath`() {
        val args = YtDlpHelper.buildMp4Args("https://example.com/v", "/tmp/out", null)

        assertTrue("--print must be present", args.contains("--print"))
        assertEquals(
            "after_move:filepath",
            args[args.indexOf("--print") + 1]
        )
    }

    // -------------------------------------------------------------------------
    // buildMp3Args
    // -------------------------------------------------------------------------

    @Test
    fun `buildMp3Args includes -x --audio-format mp3 --audio-quality 0`() {
        val args = YtDlpHelper.buildMp3Args("https://example.com/v", "/tmp/out", null)

        assertTrue("-x flag must be present", args.contains("-x"))
        assertTrue("--audio-format must be present", args.contains("--audio-format"))
        assertEquals("mp3", args[args.indexOf("--audio-format") + 1])
        assertTrue("--audio-quality must be present", args.contains("--audio-quality"))
        assertEquals("0", args[args.indexOf("--audio-quality") + 1])
    }

    @Test
    fun `buildMp3Args includes ffmpeg-location when provided`() {
        val args = YtDlpHelper.buildMp3Args(
            url = "https://example.com/v",
            template = "/sdcard/%(title)s.%(ext)s",
            ffmpegPath = "/data/app/ffmpeg"
        )

        assertTrue(args.contains("--ffmpeg-location"))
        assertEquals("/data/app/ffmpeg", args[args.indexOf("--ffmpeg-location") + 1])
    }

    @Test
    fun `buildMp3Args includes --print after_move filepath`() {
        val args = YtDlpHelper.buildMp3Args("https://example.com/v", "/tmp/out", null)

        assertTrue(args.contains("--print"))
        assertEquals("after_move:filepath", args[args.indexOf("--print") + 1])
    }

    // -------------------------------------------------------------------------
    // VideoInfo.formattedDuration
    // -------------------------------------------------------------------------

    @Test
    fun `formattedDuration formats seconds only`() {
        val info = VideoInfo("T", 75L, "", "")
        assertEquals("1:15", info.formattedDuration())
    }

    @Test
    fun `formattedDuration formats hours minutes seconds`() {
        val info = VideoInfo("T", 3661L, "", "")
        assertEquals("1:01:01", info.formattedDuration())
    }
}
