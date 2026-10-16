package com.caneretabito.motionpin.tracking

import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Deterministic identity-labelled observations, not a substitute for real-video detection tests. */
class MatcherScenarioTest {
    private fun person(x: Float, y: Float = .2f, w: Float = .18f, h: Float = .55f,
                       color: Float = .45f) = PersonBox(x, y, x + w, y + h, .91f, List(36) { color })

    @Test fun smoothIdentityIsMaintainedWithOneTwoFourEightAndTwelveCandidates() {
        for (count in listOf(1, 2, 4, 8, 12)) {
            val targetId = count / 2
            fun frame(index: Int) = (0 until count).map { id ->
                val x = .04f + .84f * id / count + .012f * sin(index * .2f) + .0005f * index
                person(x, y = .25f, w = min(.12f, .50f / count), h = .35f)
            }
            val seed = frame(0)
            val matcher = StablePersonMatcher(seed[targetId], 0, seed)
            for (index in 1..40) {
                val people = frame(index)
                // Detector result order is not a track ID and may change every sample.
                val shuffled = if (index % 2 == 0) people.reversed() else people
                val result = matcher.step(index * 250_000L, shuffled)
                assertEquals("count=$count frame=$index", MatchStatus.MATCHED, result.status)
                assertEquals("identity count=$count frame=$index", people[targetId], result.box)
            }
        }
    }

    @Test fun sameCostumeReversalCannotStealKnownRival() {
        val matcher = StablePersonMatcher(person(.20f), 0)
        val target = person(.30f)
        assertEquals(target, matcher.step(250_000, listOf(target, person(.50f))).box)
        // Constant-velocity greedy matching preferred the rival at .37 with a large margin.
        val result = matcher.step(500_000, listOf(person(.20f), person(.37f)))
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
        assertNull(result.box)
    }

    @Test fun overlappingUniformsDoNotTurnIntoFalseSingletonRecovery() {
        val matcher = StablePersonMatcher(person(.20f), 0)
        assertEquals(MatchStatus.AMBIGUOUS,
            matcher.step(250_000, listOf(person(.22f), person(.24f))).status)
        assertEquals(MatchStatus.AMBIGUOUS, matcher.step(500_000, listOf(person(.23f))).status)
        assertEquals(MatchStatus.LOST, matcher.step(1_250_000, listOf(person(.23f))).status)
    }

    @Test fun singletonReachableFromKnownLookalikeCannotProveTargetSurvived() {
        val matcher = StablePersonMatcher(person(.20f), 0)
        val target = person(.30f)
        assertEquals(target, matcher.step(250_000, listOf(target, person(.50f))).box)
        // Either selected target or its visible rival could explain this one detection.
        assertEquals(MatchStatus.AMBIGUOUS, matcher.step(500_000, listOf(person(.37f))).status)
    }

    @Test fun brieflyAmbiguousCrossingCanRecoverWhenCandidatesSeparate() {
        val matcher = StablePersonMatcher(person(.20f), 0)
        assertEquals(MatchStatus.AMBIGUOUS,
            matcher.step(250_000, listOf(person(.22f), person(.24f))).status)
        val target = person(.21f)
        assertEquals(target, matcher.step(500_000, listOf(target, person(.40f))).box)
    }

    @Test fun missedDetectionBudgetDependsOnSourceTimeNotNumberOfCalls() {
        for (sampleUs in listOf(50_000L, 100_000L, 250_000L)) {
            val matcher = StablePersonMatcher(person(.20f), 0)
            var timeUs = sampleUs
            while (timeUs <= 1_000_000L) {
                assertEquals("cadence=$sampleUs time=$timeUs", MatchStatus.MISSING,
                    matcher.step(timeUs, emptyList()).status)
                timeUs += sampleUs
            }
            val recovered = person(.22f)
            assertEquals(recovered, matcher.step(1_100_000, listOf(recovered)).box)
        }
    }

    @Test fun longGapExpiresBeforeEvenPerfectCandidateCanBeAccepted() {
        for (gap in listOf(1_250_000L, 10_000_000L, Long.MAX_VALUE)) {
            val seed = person(.20f)
            val matcher = StablePersonMatcher(seed, 0)
            assertEquals(MatchStatus.LOST, matcher.step(gap, listOf(seed)).status)
            assertEquals(MatchStatus.LOST, matcher.step(gap, listOf(seed)).status)
        }
    }

    @Test fun outOfOrderAndDuplicateObservationsCannotRewriteHistoryOrConsumeGrace() {
        val seed = person(.20f)
        val matcher = StablePersonMatcher(seed, 0)
        assertEquals(MatchStatus.MISSING, matcher.step(500_000, emptyList()).status)
        repeat(10) {
            assertEquals(MatchStatus.MISSING, matcher.step(250_000, listOf(seed)).status)
            assertEquals(MatchStatus.MISSING, matcher.step(500_000, listOf(seed)).status)
        }
        assertEquals(seed, matcher.step(600_000, listOf(seed)).box)
    }

    @Test fun vfrMovementUsesElapsedSourceTime() {
        val matcher = StablePersonMatcher(person(.10f), 0)
        for (time in listOf(90_000L, 330_000L, 810_000L, 1_050_000L, 1_140_000L, 1_700_000L)) {
            val target = person(.10f + time / 1_000_000f * .12f)
            assertEquals("PTS=$time", target, matcher.step(time, listOf(target)).box)
        }
    }

    @Test fun mergedGroupBoxesDoNotPoisonAppearanceAndLaterRecovery() {
        for (width in listOf(.36f, .54f)) {
            val seed = person(.20f)
            val matcher = StablePersonMatcher(seed, 0)
            val merged = person(seed.cx - width / 2f, w = width)
            assertEquals(MatchStatus.MISSING, matcher.step(250_000, listOf(merged)).status)
            assertEquals(seed, matcher.step(500_000, listOf(seed)).box)
        }
    }

    @Test fun soloTurnJumpAndCameraPanCanRecoverMotionWithoutOldVelocityLock() {
        val matcher = StablePersonMatcher(person(.20f), 0)
        val observations = listOf(person(.30f), person(.20f), person(.12f),
            person(.15f, y = .08f), person(.18f, y = .12f), person(.25f, y = .15f, w = .13f))
        observations.forEachIndexed { index, target ->
            assertEquals("movement=$index", target, matcher.step((index + 1) * 250_000L, listOf(target)).box)
        }
    }

    @Test fun gradualZoomIsAcceptedButAbruptShotSizeChangeIsNotIdentityEvidence() {
        val seed = person(.30f, y = .30f, w = .10f, h = .30f)
        val matcher = StablePersonMatcher(seed, 0)
        val closer = person(.29f, y = .28f, w = .12f, h = .36f)
        assertEquals(closer, matcher.step(250_000, listOf(closer)).box)
        assertNull(matcher.step(500_000, listOf(person(.20f, y = .05f, w = .28f, h = .90f))).box)
    }

    @Test fun wrongCostumeDuringOcclusionDoesNotUpdateIdentity() {
        val seed = person(.20f, color = .10f)
        val matcher = StablePersonMatcher(seed, 0)
        assertEquals(MatchStatus.MISSING, matcher.step(250_000, emptyList()).status)
        assertEquals(MatchStatus.MISSING, matcher.step(500_000, listOf(person(.21f, color = .90f))).status)
        assertEquals(seed, matcher.step(750_000, listOf(seed)).box)
    }
}
