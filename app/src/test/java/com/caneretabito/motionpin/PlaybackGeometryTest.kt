package com.caneretabito.motionpin

import com.caneretabito.motionpin.player.PlaybackTimeline
import com.caneretabito.motionpin.player.VideoViewport
import com.caneretabito.motionpin.player.parseSecondsUs
import com.caneretabito.motionpin.player.viewportTransform
import org.junit.Assert.*
import org.junit.Test

class PlaybackGeometryTest {
    @Test fun bakedQuarterSpeedCacheUsesOneXAndSameSourceTimeAfterComparison() {
        val cached = PlaybackTimeline(10_000_000, 20_000_000, .25, cached = true)
        val original = PlaybackTimeline(10_000_000, 20_000_000)
        val sourceTime = cached.toSourceUs(24_000_000)
        assertEquals(16_000_000, sourceTime)
        assertEquals(1f, cached.effectivePlayerSpeed(.25f), 0f)
        assertEquals(.25f, original.effectivePlayerSpeed(.25f), 0f)
        assertEquals(sourceTime, original.toSourceUs(original.toPlayerUs(sourceTime)))
        assertEquals(sourceTime, cached.toSourceUs(cached.toPlayerUs(sourceTime)))
    }

    @Test fun clippedPlayerStartsAtZeroAndNeverStoresAnExclusiveEndAsAPoint() {
        val timeline = PlaybackTimeline(12_345_000, 15_678_000)
        assertEquals(0, timeline.toPlayerUs(12_345_000))
        assertEquals(12_345_000, timeline.toSourceUs(0))
        assertEquals(13_345_000, timeline.toSourceUs(1_000_000))
        assertEquals(15_677_999, timeline.toSourceUs(Long.MAX_VALUE))
        assertEquals(0, timeline.toPlayerUs(-1))
    }

    @Test fun normalizedVideoCenterSurvivesADeviceRotationWithoutUsingLetterboxCoordinates() {
        val saved = VideoViewport(zoom = 4f, centerX = .65f, centerY = .4f)
        val portrait = viewportTransform(1080f, 1920f, 16f / 9f, false, saved)
        val landscape = viewportTransform(1920f, 1080f, 16f / 9f, false, saved)
        assertEquals((.5f - saved.centerX), portrait.translateX / (portrait.baseWidth * saved.zoom), .00001f)
        assertEquals((.5f - saved.centerX), landscape.translateX / (landscape.baseWidth * saved.zoom), .00001f)
        // The stored coordinate is not rewritten to a pixel offset during either layout.
        assertEquals(.65f, saved.centerX, 0f)
        assertEquals(.4f, saved.centerY, 0f)
        val verticalVideo = viewportTransform(1920f, 1080f, 9f / 16f, false, VideoViewport())
        assertEquals(607.5f, verticalVideo.baseWidth, .001f)
        assertEquals(1080f, verticalVideo.baseHeight, .001f)
        assertEquals(0f, verticalVideo.translateX, 0f)
    }

    @Test fun decimalInputIsIntegerMicrosecondsAndRejectsInvalidOrOverflowValues() {
        assertEquals(12_345_000L, parseSecondsUs("12.345"))
        assertEquals(100_001L, parseSecondsUs("0.100001"))
        assertEquals(1_000_000L, (1..10).sumOf { parseSecondsUs("0.1")!! })
        assertNull(parseSecondsUs("-0.1"))
        assertNull(parseSecondsUs("NaN"))
        assertNull(parseSecondsUs("1.0000001"))
        assertNull(parseSecondsUs("9223372036854775807"))
    }
}
