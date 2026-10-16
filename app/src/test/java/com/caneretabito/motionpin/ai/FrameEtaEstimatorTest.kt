package com.caneretabito.motionpin.ai

import org.junit.Assert.*
import org.junit.Test

class FrameEtaEstimatorTest {
    @Test fun waitsForMeasuredProcessingRate() {
        val estimator = FrameEtaEstimator()
        assertNull(estimator.observe(1, 100, 1000))
        assertNull(estimator.observe(4, 100, 1500))
        assertEquals(19000L, estimator.observe(5, 100, 2000))
    }

    @Test fun progressRestartInvalidatesOldRate() {
        val estimator = FrameEtaEstimator()
        estimator.observe(1, 100, 1000)
        assertNotNull(estimator.observe(21, 100, 3000))
        assertNull(estimator.observe(1, 100, 4000))
        assertNull(estimator.observe(2, 100, 4500))
        assertEquals(0L, estimator.observe(100, 100, 5000))
    }

    @Test fun rejectsInvalidSamples() {
        val estimator = FrameEtaEstimator()
        assertNull(estimator.observe(0, 100, 0))
        assertNull(estimator.observe(101, 100, 1000))
        assertNull(estimator.observe(1, 0, 1000))
    }
}
