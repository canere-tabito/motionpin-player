package com.caneretabito.motionpin

import com.caneretabito.motionpin.player.PlaybackTimeline
import com.caneretabito.motionpin.player.VideoViewport
import com.caneretabito.motionpin.player.ViewportGeometry
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

    @Test fun fitLetterboxAndFillCropHaveDifferentVisibleSourceFractions() {
        val fit = ViewportGeometry(1080f, 1920f, 16f / 9f, false)
        val fill = fit.copy(fill = true)
        assertEquals(1f, fit.visibleWidth(1f), .00001f)
        assertEquals(1f, fit.visibleHeight(1f), .00001f)
        assertEquals(1f, fit.visibleHeight(2f), .00001f) // zoom consumes vertical letterbox first
        assertEquals(.31640625f, fill.visibleWidth(1f), .00001f)
        assertEquals(1f, fill.visibleHeight(1f), .00001f)
        assertEquals(.5f, fill.visibleHeight(2f), .00001f)
    }

    @Test fun invalidStoredViewportAndTransientZeroLayoutNeverReturnNaNTransforms() {
        val invalid = VideoViewport(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        assertEquals(VideoViewport(), invalid.bounded())
        val transform = viewportTransform(0f, Float.NaN, Float.NaN, true, invalid)
        assertTrue(transform.baseWidth.isFinite() && transform.baseWidth > 0f)
        assertTrue(transform.baseHeight.isFinite() && transform.baseHeight > 0f)
        assertTrue(transform.translateX.isFinite() && transform.translateY.isFinite())
    }

    @Test fun sourceEdgesStayAtContainerEdgesAfterLargePanInFitOrFill() {
        for (fill in listOf(false, true)) for (edge in listOf(0f, 1f)) {
            val geometry = ViewportGeometry(1080f, 1920f, 16f / 9f, fill)
            val view = VideoViewport(4f, edge, edge)
            val transform = viewportTransform(geometry.width, geometry.height, geometry.aspect, fill, view)
            val sourceEdgeX = geometry.width / 2f + (edge - .5f) * transform.baseWidth * view.zoom + transform.translateX
            val sourceEdgeY = geometry.height / 2f + (edge - .5f) * transform.baseHeight * view.zoom + transform.translateY
            assertEquals(edge * geometry.width, sourceEdgeX, .001f)
            assertEquals(edge * geometry.height, sourceEdgeY, .001f)
        }
    }
}
