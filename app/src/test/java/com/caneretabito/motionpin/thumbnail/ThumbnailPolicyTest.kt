package com.caneretabito.motionpin.thumbnail

import org.junit.Assert.*
import org.junit.Test

class ThumbnailPolicyTest {
    @Test fun relinkAndChangedSourceCannotReuseOldCachedPixels() {
        val original = ThumbnailRequest("video-id", "content://media/external/video/media/7", 3L,
            modifiedSeconds = 100L, sizeBytes = 2000L, durationUs = 4_000_000L,
            width = 1920, height = 1080)
        val cached = hashMapOf(original to "old thumbnail")
        assertEquals("old thumbnail", cached[original.copy()])
        val changed = listOf(original.copy(uri = "content://media/external/video/media/8"),
            original.copy(revision = 4L), original.copy(modifiedSeconds = 101L),
            original.copy(sizeBytes = 2001L), original.copy(rotationDegrees = 90),
            original.copy(fileName = "changed.mkv"), original.copy(mimeType = "video/x-matroska"),
            original.copy(maxWidth = 160, maxHeight = 90))
        changed.forEach { assertNull("Changed source/layout must miss cache: $it", cached[it]) }
    }

    @Test fun knownDurationSamplesAwayFromBlackLeadWithBoundedWork() {
        val times = ThumbnailPolicy.frameTimesUs(30L * 60L * 1_000_000L)
        assertTrue(times.first() > 0L)
        assertTrue(times.any { it >= 10_000_000L })
        assertEquals(0L, times.last())
        assertTrue(times.size <= 4)
        assertEquals(times.distinct(), times)
        assertTrue("Must not seek through a whole 30-minute video", times.all { it <= 30_000_000L })
    }

    @Test fun tinyAndHugeDurationsNeverOverflowOrSamplePastEnd() {
        for (duration in listOf(1L, 2L, 9L, 100L, 99_999L, Long.MAX_VALUE)) {
            val times = ThumbnailPolicy.frameTimesUs(duration)
            assertTrue(times.isNotEmpty() && times.size <= 4)
            assertTrue("duration=$duration timestamps=$times", times.all { it >= 0L && it < duration })
            assertEquals(times.distinct(), times)
        }
    }

    @Test fun missingDurationStillHasRepresentativeAndFirstFrameAttempts() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            val times = ThumbnailPolicy.frameTimesUs(duration)
            assertTrue(times.size in 2..4)
            assertTrue(times.first() > 0L)
            assertEquals(0L, times.last())
            assertTrue(times.all { it in 0L..30_000_000L })
        }
    }

    @Test fun landscapeAndPortraitThumbnailBoundsPreserveAspect() {
        assertEquals(ThumbnailSize(320, 180), ThumbnailPolicy.scaledSize(3840, 2160))
        val portrait = ThumbnailPolicy.scaledSize(2160, 3840)
        assertEquals(180, portrait.height)
        assertEquals(2160.0 / 3840.0, portrait.width.toDouble() / portrait.height, .005)
        assertEquals(ThumbnailSize(64, 32), ThumbnailPolicy.scaledSize(64, 32))
    }

    @Test fun malformedSizesStayPositiveAndNeverRequestLargeDecodes() {
        for ((width, height) in listOf(0 to 0, -1 to -1, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            for ((maxWidth, maxHeight) in listOf(0 to -1, Int.MAX_VALUE to Int.MAX_VALUE, 320 to 180)) {
                val result = ThumbnailPolicy.scaledSize(width, height, maxWidth, maxHeight)
                assertTrue(result.width in 1..512 && result.height in 1..512)
            }
        }
    }

    @Test fun flatOrTransparentLeadScoresBelowVisibleDetail() {
        val lowDetail = listOf(IntArray(192) { 0xff000000.toInt() },
            IntArray(192) { 0xffffffff.toInt() }, IntArray(192) { 0x00ffffff }, intArrayOf())
        lowDetail.forEach { assertTrue(ThumbnailPolicy.detailScore(it) < ThumbnailPolicy.MIN_DETAIL_SCORE) }
        val pattern = IntArray(192) { if (it % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        val score = ThumbnailPolicy.detailScore(pattern)
        assertTrue(score.isFinite() && score in ThumbnailPolicy.MIN_DETAIL_SCORE..1f)
    }
}
