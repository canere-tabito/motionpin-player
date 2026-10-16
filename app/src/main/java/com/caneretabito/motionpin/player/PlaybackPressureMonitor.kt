package com.caneretabito.motionpin.player

/** Admission control for optional analysis, never a request to reduce playback quality. */
class PlaybackPressureMonitor {
    private var sampledAtMs: Long? = null
    private var rendered = 0
    private var dropped = 0
    private var blockedUntilMs = 0L

    fun sample(nowMs: Long, renderedFrames: Int, droppedFrames: Int,
               playing: Boolean, buffering: Boolean): Boolean {
        // Loop/seek events are not decoder restarts. A three-second replay must still
        // accumulate a full observation window and retain an existing cooldown.
        val countersReset = renderedFrames < rendered || droppedFrames < dropped
        val reset = sampledAtMs == null || nowMs < checkNotNull(sampledAtMs) || countersReset
        if (countersReset) {
            blockedUntilMs = 0L
        }
        if (reset || !playing || buffering) {
            sampledAtMs = nowMs
            rendered = renderedFrames
            dropped = droppedFrames
        } else if (nowMs - checkNotNull(sampledAtMs) >= 3_000L) {
            val completed = renderedFrames - rendered
            val skipped = droppedFrames - dropped
            val total = completed.toLong() + skipped
            if (total >= 50L && skipped >= 4 && skipped.toDouble() / total > .025) {
                blockedUntilMs = nowMs + 10_000L
            }
            sampledAtMs = nowMs
            rendered = renderedFrames
            dropped = droppedFrames
        }
        // Buffer starvation is an immediate reason to yield the optional decoder.
        return buffering || (playing && nowMs < blockedUntilMs)
    }
}
