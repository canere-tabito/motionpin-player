package com.caneretabito.motionpin.domain

import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test fun quarterSpeedTenSecondClipProducesFortySecondsAnd2400Frames() {
        val mapping = TimeMapping(2_000_000, 12_000_000, Rational.QUARTER)
        assertEquals(40_000_000, mapping.outputDurationUs)
        assertEquals(2400, mapping.outputFrameCount(Rational.FPS_60))
        assertEquals(4_500_000, mapping.sourceTimeUs(10_000_000))
        assertEquals(10_000_000, mapping.outputTimeUs(4_500_000))
        assertEquals(1f, mapping.playerSpeed(cacheIsBaked = true))
        assertEquals(.25f, mapping.playerSpeed(cacheIsBaked = false))
    }

    @Test fun fractionalFrameRateDoesNotAccumulateRoundedMicroseconds() {
        val mapping = TimeMapping(0, 3_600_000_000, Rational.ONE)
        assertEquals(1_001_000_000, mapping.outputPtsUs(60_000, Rational.FPS_5994))
        assertEquals(1_001_000_000, mapping.sourceTimeForFrameUs(60_000, Rational.FPS_5994))
        assertEquals(215785, mapping.outputFrameCount(Rational.FPS_5994))
    }

    @Test fun intermediateSourceTimestampUsesSingleRationalCalculation() {
        val mapping = TimeMapping(100, 10_000_100, Rational.QUARTER)
        // n=1: source is 100 + floor(1e6 / 60 / 4), with no rounded output timestamp in between.
        assertEquals(4266, mapping.sourceTimeForFrameUs(1, Rational.FPS_60))
        assertEquals(100, mapping.sourceTimeForFrameUs(0, Rational.FPS_60))
    }

    @Test fun vfrSelectionUsesPresentationTimestampsAndNeverCrossesCut() {
        val pts = listOf(0L, 16_000L, 49_000L, 90_000L)
        val selection = FrameSelector.select(pts, 30_000) as FrameSelection.Interpolate
        assertEquals(1, selection.before); assertEquals(2, selection.after)
        assertEquals(14_000, selection.numerator); assertEquals(33_000, selection.denominator)
        val cut = FrameSelector.select(pts, 30_000, setOf(2)) as FrameSelection.Original
        assertEquals(1, cut.index)
    }

    @Test fun exactDuplicateAndBoundaryPtsUseOriginalFrames() {
        val pts = listOf(10L, 10L, 20L, 50L)
        assertEquals(0, (FrameSelector.select(pts, 0) as FrameSelection.Original).index)
        assertEquals(1, (FrameSelector.select(pts, 10) as FrameSelection.Original).index)
        assertEquals(3, (FrameSelector.select(pts, 99) as FrameSelection.Original).index)
        assertEquals(.5f, (FrameSelector.select(pts, 15) as FrameSelection.Interpolate).alpha)
    }

    @Test(expected = IllegalArgumentException::class) fun decodeOrderCannotStandInForPresentationOrder() {
        FrameSelector.select(listOf(0, 100, 50), 75)
    }

    @Test fun pinsUseHalfOpenSourceBounds() {
        PinBounds.validate(0, 10_000, 10_000)
        PinBounds.validate(9_999, null, 10_000)
        assertThrows(IllegalArgumentException::class.java) { PinBounds.validate(10_000, null, 10_000) }
        assertThrows(IllegalArgumentException::class.java) { PinBounds.validate(100, 100, 10_000) }
        assertThrows(IllegalArgumentException::class.java) { PinBounds.validate(-1, 100, 10_000) }
    }

    @Test fun normalizedZoomDoesNotDependOnScreenOrientationAndPanClamps() {
        val saved = ZoomState(3f, .2f, .7f)
        val restored = ZoomState(saved.scale, saved.centerX, saved.centerY)
        assertEquals(saved, restored)
        assertEquals(0f, saved.pan(10f, 0f).centerX)
        assertEquals(1f, saved.pan(-10f, 0f).centerX)
        assertThrows(IllegalArgumentException::class.java) { ZoomState(Float.NaN) }
    }

    @Test fun longRationalCalculationAvoidsIntermediateOverflow() {
        assertEquals(4_000_000_000_000_000_000, Rational(2, 4).scaleFloor(8_000_000_000_000_000_000))
        assertEquals(Rational(60_000, 1001), Rational(120_000, 2002).normalized())
    }

    @Test fun api29ExactConversionKeepsTimestampBoundsAndRejectsOverflow() {
        assertEquals(0L, Rational.ONE.scaleFloor(0))
        assertEquals(Long.MAX_VALUE, Rational.ONE.scaleFloor(Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { Rational.ONE.scaleFloor(Long.MIN_VALUE) }
        assertThrows(ArithmeticException::class.java) { Rational(2).scaleFloor(Long.MAX_VALUE) }
        assertThrows(ArithmeticException::class.java) { TimeMapping(0, Long.MAX_VALUE, Rational.QUARTER).outputDurationUs }
        assertThrows(ArithmeticException::class.java) { TimeMapping(0, Long.MAX_VALUE, Rational.ONE).outputPtsUs(Long.MAX_VALUE, Rational.ONE) }
    }
}
