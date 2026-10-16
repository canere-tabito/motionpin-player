package com.caneretabito.motionpin.tracking

import kotlin.math.abs

/**
 * Conservative discontinuity guard on a small RGB grid. It rejects strong spatial changes,
 * after subtracting global illumination changes, before any identity association is attempted.
 * This is a safety stop, not a complete scene-cut detector: similar-looking cuts may be missed.
 */
internal class TrackingSceneGuard {
    private var previous: FloatArray? = null

    fun reset(rgb: FloatArray? = null) { previous = rgb?.copyOf() }

    fun isCut(rgb: FloatArray): Boolean {
        require(rgb.size >= 12 && rgb.size % 3 == 0 && rgb.all { it.isFinite() && it in 0f..1f })
        val before = previous
        previous = rgb.copyOf()
        if (before == null || before.size != rgb.size) return false
        val cells = rgb.size / 3
        val globalDelta = FloatArray(3) { channel ->
            (0 until cells).sumOf { (rgb[it * 3 + channel] - before[it * 3 + channel]).toDouble() }
                .toFloat() / cells
        }
        var residual = 0f
        var changed = 0
        for (cell in 0 until cells) {
            var difference = 0f
            for (channel in 0..2) {
                val index = cell * 3 + channel
                difference += abs(rgb[index] - before[index] - globalDelta[channel]) / 3f
            }
            residual += difference / cells
            if (difference > .24f) changed++
        }
        return residual > .30f && changed.toFloat() / cells >= .65f
    }
}

/** Pure scheduling policy, shared by analysis and tests. All positions are source microseconds. */
internal object TrackingSampling {
    fun nextTimeUs(analyzedUntilUs: Long, lastAnchorUs: Long, stepUs: Long): Long {
        require(analyzedUntilUs >= 0L && lastAnchorUs >= 0L && stepUs > 0L)
        return Math.addExact(maxOf(analyzedUntilUs, lastAnchorUs), stepUs)
    }

    fun seekNeedsSelection(timeUs: Long, firstAnchorUs: Long, analyzedUntilUs: Long): Boolean =
        timeUs < firstAnchorUs || timeUs > analyzedUntilUs + 250_000L
}

/** Android Thermal API values: NONE=0, LIGHT=1, MODERATE=2, SEVERE=3. */
internal class TrackingResourceGate {
    enum class Reason { THERMAL, LOW_BATTERY, POWER_SAVE, PLAYBACK }
    private var cooling = false

    @Synchronized fun reason(thermalStatus: Int, lowBattery: Boolean,
        powerSave: Boolean, playbackPressure: Boolean): Reason? {
        if (thermalStatus >= 3) cooling = true
        else if (thermalStatus <= 1) cooling = false
        return when {
            cooling -> Reason.THERMAL
            lowBattery -> Reason.LOW_BATTERY
            powerSave -> Reason.POWER_SAVE
            playbackPressure -> Reason.PLAYBACK
            else -> null
        }
    }
}
