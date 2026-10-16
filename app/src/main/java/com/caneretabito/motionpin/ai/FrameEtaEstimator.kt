package com.caneretabito.motionpin.ai

/**
 * ETA for the frame-generation phase only. Uses actual monotonic processing samples,
 * never a fixed device benchmark. Source probing and final mux verification are excluded.
 * The estimate is deliberately transient: restarting a job starts a new measurement.
 */
internal class FrameEtaEstimator {
    private var sampleFrames: Long? = null
    private var sampleAtMs = 0L
    private var smoothedMsPerFrame: Double? = null

    fun observe(frames: Long, total: Long, monotonicMs: Long): Long? {
        if (total <= 0 || frames <= 0 || frames > total || monotonicMs < 0) {
            reset()
            return null
        }
        val previousFrames = sampleFrames
        if (previousFrames == null || frames < previousFrames || monotonicMs < sampleAtMs) {
            sampleFrames = frames
            sampleAtMs = monotonicMs
            smoothedMsPerFrame = null
            return if (frames == total) 0L else null
        }
        if (frames == total) return 0L

        val advanced = frames - previousFrames
        val elapsedMs = monotonicMs - sampleAtMs
        if (advanced >= 4 && elapsedMs >= 1_000L) {
            val latestMsPerFrame = elapsedMs.toDouble() / advanced
            smoothedMsPerFrame = smoothedMsPerFrame?.let { it * .65 + latestMsPerFrame * .35 } ?: latestMsPerFrame
            sampleFrames = frames
            sampleAtMs = monotonicMs
        }

        val estimateMs = smoothedMsPerFrame?.times((total - frames).toDouble()) ?: return null
        // Do not expose invalid or wildly extrapolated rates as a trustworthy ETA.
        if (!estimateMs.isFinite() || estimateMs < 0 || estimateMs > 7.0 * 24 * 60 * 60 * 1_000) return null
        return estimateMs.toLong().coerceAtLeast(1L)
    }

    private fun reset() {
        sampleFrames = null
        sampleAtMs = 0
        smoothedMsPerFrame = null
    }
}

/** Transient progress shared across tabs/rotation without changing Room schema v1. */
data class AiGenerationEta(
    val jobId: String,
    val outputFrames: Long,
    val totalFrames: Long,
    val remainingMs: Long?
)
