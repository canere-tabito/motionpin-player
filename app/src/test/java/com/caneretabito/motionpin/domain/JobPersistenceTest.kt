package com.caneretabito.motionpin.domain

import com.caneretabito.motionpin.data.AiJobEntity
import com.caneretabito.motionpin.data.JobPersistencePolicy
import com.caneretabito.motionpin.data.JobStatus
import org.junit.Assert.assertThrows
import org.junit.Test

class JobPersistenceTest {
    private val original = AiJobEntity(videoId = "source-id", pinId = "pin-id", startUs = 4_000_000, endUs = 9_000_000,
        status = JobStatus.RUNNING, sourceRevision = 1)

    @Test fun shortenedSourceStillAllowsFailureCancellationAndInterruptedCleanup() {
        for (status in listOf(JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.INTERRUPTED, JobStatus.WAITING)) {
            JobPersistencePolicy.validate(original.copy(status = status, outputPath = null, error = "Source changed"), original, 5_000_000)
        }
    }

    @Test fun staleSourceCannotQueueRunOrCompleteTheOldInterval() {
        for (status in listOf(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.COMPLETED)) {
            assertThrows(IllegalArgumentException::class.java) {
                JobPersistencePolicy.validate(original.copy(status = status), original, 5_000_000)
            }
        }
    }

    @Test fun cleanupExceptionCannotCreateOrRewriteAnInvalidRequest() {
        val failure = original.copy(status = JobStatus.FAILED)
        assertThrows(IllegalArgumentException::class.java) { JobPersistencePolicy.validate(failure, null, 5_000_000) }
        assertThrows(IllegalArgumentException::class.java) { JobPersistencePolicy.validate(failure.copy(endUs = 10_000_000), original, 5_000_000) }
        assertThrows(IllegalArgumentException::class.java) { JobPersistencePolicy.validate(failure.copy(sourceRevision = 2), original, 5_000_000) }
        assertThrows(IllegalArgumentException::class.java) { JobPersistencePolicy.validate(failure.copy(outputPath = "/cache/old.mp4"), original, 5_000_000) }
        assertThrows(IllegalArgumentException::class.java) { JobPersistencePolicy.validate(failure.copy(progress = Float.NaN), original, 5_000_000) }
    }
}
