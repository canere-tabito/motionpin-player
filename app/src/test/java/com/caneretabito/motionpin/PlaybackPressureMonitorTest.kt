package com.caneretabito.motionpin

import com.caneretabito.motionpin.player.PlaybackPressureMonitor
import org.junit.Assert.*
import org.junit.Test

class PlaybackPressureMonitorTest {
    @Test fun sustainedDropsYieldAnalysisAndRecoverAfterCooldown() {
        val monitor = PlaybackPressureMonitor()
        assertFalse(monitor.sample(0, 0, 0, true, false))
        assertTrue(monitor.sample(3_000, 170, 10, true, false))
        assertTrue(monitor.sample(6_000, 350, 10, true, false))
        assertFalse(monitor.sample(13_000, 770, 10, true, false))
    }

    @Test fun bufferingYieldsImmediatelyEvenBeforeFrameCountersExist() {
        val monitor = PlaybackPressureMonitor()
        assertTrue(monitor.sample(0, 0, 0, false, true))
        assertFalse(monitor.sample(500, 1, 0, true, false))
    }

    @Test fun newMediaAndResetCountersCannotInheritOldDropWindow() {
        val monitor = PlaybackPressureMonitor()
        monitor.sample(0, 0, 0, true, false)
        assertTrue(monitor.sample(3_000, 170, 10, true, false))
        assertFalse(monitor.sample(3_100, 0, 0, true, false))
        assertFalse(monitor.sample(6_100, 180, 0, true, false))
        assertFalse(monitor.sample(9_100, 1, 0, true, false))
    }

    @Test fun pauseDoesNotCountOldFramesAsANewOverload() {
        val monitor = PlaybackPressureMonitor()
        monitor.sample(0, 0, 0, true, false)
        monitor.sample(1_000, 40, 4, false, false)
        assertFalse(monitor.sample(30_000, 40, 4, false, false))
        assertFalse(monitor.sample(33_000, 220, 4, true, false))
    }
}
