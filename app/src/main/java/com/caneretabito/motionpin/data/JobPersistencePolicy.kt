package com.caneretabito.motionpin.data

import com.caneretabito.motionpin.domain.PinBounds

/** A stale source must not prevent recording failure and releasing the serial GPU queue. */
internal object JobPersistencePolicy {
    private val cleanupStates = setOf(JobStatus.FAILED, JobStatus.INTERRUPTED, JobStatus.CANCELLED, JobStatus.WAITING)
    private val allStates = cleanupStates + setOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.COMPLETED)

    fun validate(proposed: AiJobEntity, existing: AiJobEntity?, currentDurationUs: Long) {
        require(proposed.status in allStates) { "Unknown AI job state" }
        require(proposed.startUs >= 0 && proposed.endUs > proposed.startUs)
        require(proposed.speedNumerator > 0 && proposed.speedDenominator > 0 && proposed.fpsNumerator > 0 && proposed.fpsDenominator > 0)
        require(proposed.progress.isFinite() && proposed.progress in 0f..1f)
        val samePersistedRequest = existing != null && proposed.id == existing.id &&
            proposed.videoId == existing.videoId && proposed.pinId == existing.pinId &&
            proposed.startUs == existing.startUs && proposed.endUs == existing.endUs &&
            proposed.speedNumerator == existing.speedNumerator && proposed.speedDenominator == existing.speedDenominator &&
            proposed.fpsNumerator == existing.fpsNumerator && proposed.fpsDenominator == existing.fpsDenominator &&
            proposed.sourceRevision == existing.sourceRevision
        if (proposed.status in cleanupStates && samePersistedRequest && proposed.outputPath == null) return
        // Fresh, runnable and completed work must always fit the currently associated source.
        PinBounds.validate(proposed.startUs, proposed.endUs, currentDurationUs)
    }
}
