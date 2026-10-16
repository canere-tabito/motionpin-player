package com.caneretabito.motionpin.tracking

import org.junit.Assert.*
import org.junit.Test

class TrackingAnalysisSafetyTest {
    private fun grid(value: (Int, Int) -> Float) = FloatArray(12 * 8 * 3) {
        value(it / 3, it % 3)
    }

    @Test fun missedSamplesAreNotDecodedAgainWhenTheNextBatchStarts() {
        assertEquals(1_750_000L, TrackingSampling.nextTimeUs(1_500_000L, 750_000L, 250_000L))
        assertEquals(1_000_000L, TrackingSampling.nextTimeUs(750_000L, 750_000L, 250_000L))
    }

    @Test fun discontinuousSeekRequiresReselectionOutsideMeasuredCoverage() {
        assertTrue(TrackingSampling.seekNeedsSelection(499_999L, 500_000L, 4_000_000L))
        assertTrue(TrackingSampling.seekNeedsSelection(8_000_000L, 500_000L, 4_000_000L))
        assertFalse(TrackingSampling.seekNeedsSelection(3_000_000L, 500_000L, 4_000_000L))
        assertFalse(TrackingSampling.seekNeedsSelection(4_200_000L, 500_000L, 4_000_000L))
    }

    @Test fun globalStageLightingChangeDoesNotBecomeASceneCut() {
        val guard = TrackingSceneGuard()
        assertFalse(guard.isCut(grid { cell, channel -> .15f + (cell % 4) * .07f + channel * .02f }))
        assertFalse(guard.isCut(grid { cell, channel -> .40f + (cell % 4) * .07f + channel * .02f }))
    }

    @Test fun localDancerMotionDoesNotBecomeASceneCut() {
        val guard = TrackingSceneGuard()
        guard.reset(grid { _, _ -> .20f })
        assertFalse(guard.isCut(grid { cell, _ -> if (cell in 30..44) .9f else .20f }))
    }

    @Test fun strongSpatialCutStopsBeforeReusingASameLookingPerson() {
        val guard = TrackingSceneGuard()
        guard.reset(grid { cell, _ -> if (cell % 2 == 0) .1f else .9f })
        assertTrue(guard.isCut(grid { cell, _ -> if (cell % 2 == 0) .9f else .1f }))
    }

    @Test fun newSelectionDoesNotInheritPreviousScenes() {
        val guard = TrackingSceneGuard()
        guard.reset(grid { cell, _ -> if (cell % 2 == 0) .1f else .9f })
        guard.reset()
        assertFalse(guard.isCut(grid { cell, _ -> if (cell % 2 == 0) .9f else .1f }))
    }

    @Test fun thermalRecoveryNeedsCoolingBelowModerate() {
        val gate = TrackingResourceGate()
        assertNull(gate.reason(2, false, false, false))
        assertEquals(TrackingResourceGate.Reason.THERMAL, gate.reason(3, false, false, false))
        assertEquals(TrackingResourceGate.Reason.THERMAL, gate.reason(2, false, false, false))
        assertNull(gate.reason(1, false, false, false))
    }

    @Test fun lowBatteryPowerSaveAndPlaybackPressurePauseWithoutChangingQuality() {
        val gate = TrackingResourceGate()
        assertEquals(TrackingResourceGate.Reason.LOW_BATTERY, gate.reason(0, true, false, false))
        assertEquals(TrackingResourceGate.Reason.POWER_SAVE, gate.reason(0, false, true, false))
        assertEquals(TrackingResourceGate.Reason.PLAYBACK, gate.reason(0, false, false, true))
        assertNull(gate.reason(0, false, false, false))
    }
}
