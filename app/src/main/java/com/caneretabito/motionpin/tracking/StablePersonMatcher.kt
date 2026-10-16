package com.caneretabito.motionpin.tracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Short-term, single-target association, not person reidentification. Source timestamps bound
 * recovery; missed detector calls must not make the grace period depend on sampling rate.
 * Velocity and a stationary fallback accommodate turns without forcing the old direction.
 */
class StablePersonMatcher(seed: PersonBox, startUs: Long, initialCandidates: List<PersonBox> = emptyList()) {
    private val initial = seed
    private var last = seed
    private var recentAppearance = seed.appearance
    private var lastMatchUs = startUs
    private var lastObservationUs = startUs
    private var speedX = 0f
    private var speedY = 0f
    private var permanentlyLost = false
    private var rivals = initialCandidates.filter { it != seed && usable(it) }.take(24)
    private var unresolvedLookalikes = false

    init { require(seed.valid() && seed.hasValidAppearance() && startUs >= 0) }

    fun step(timeUs: Long, candidates: List<PersonBox>): IdentityMatch {
        if (permanentlyLost) return IdentityMatch(MatchStatus.LOST)
        // A late/duplicate analysis result cannot advance recovery or corrupt motion history.
        if (timeUs <= lastObservationUs) return IdentityMatch(MatchStatus.MISSING)
        lastObservationUs = timeUs
        val gapUs = timeUs - lastMatchUs
        // Check before association: an apparently matching dancer after a seek/long gap is
        // not evidence that it is still the selected person. A new selection is required.
        if (gapUs >= MAX_RECOVERY_GAP_US) {
            permanentlyLost = true
            return IdentityMatch(MatchStatus.LOST)
        }
        val seconds = gapUs / 1_000_000f
        val predictionSeconds = min(seconds, .65f)
        val shiftX = speedX * predictionSeconds
        val shiftY = speedY * predictionSeconds
        val predicted = last.copy(left = last.left + shiftX, right = last.right + shiftX,
            top = last.top + shiftY, bottom = last.bottom + shiftY)
        // A tall narrow dancer must not inherit a very large horizontal gate from body height.
        val limitX = (.08f + .55f * last.width + .22f * seconds).coerceAtMost(.40f)
        val limitY = (.06f + .20f * last.height + .20f * seconds).coerceAtMost(.35f)

        data class Candidate(val box: PersonBox, val cost: Float)
        val scores = candidates.mapNotNull { box ->
            if (!usable(box)) return@mapNotNull null
            val relativeArea = box.area / last.area
            val relativeAspect = (box.width / box.height) / (last.width / last.height)
            // Merged detections often grow sideways while retaining body height. Area alone
            // used to accept a box spanning two/three dancers and contaminate the identity.
            if (relativeArea !in .35f..2.6f || relativeAspect !in .50f..1.80f) return@mapNotNull null
            val originalDistance = appearanceDistance(initial.appearance, box.appearance)
            val recentDistance = appearanceDistance(recentAppearance, box.appearance)
            if (originalDistance > .34f || recentDistance > .36f) return@mapNotNull null
            val predictedDistance = hypot((box.cx - predicted.cx) / limitX, (box.cy - predicted.cy) / limitY)
            val stationaryDistance = hypot((box.cx - last.cx) / limitX, (box.cy - last.cy) / limitY)
            val motionDistance = min(predictedDistance, stationaryDistance + .25f)
            if (motionDistance > 1f || stationaryDistance > 1.5f) return@mapNotNull null
            val appearanceCost = .55f * originalDistance + .45f * recentDistance
            val cost = 1.35f * motionDistance + 1.10f * appearanceCost +
                .18f * abs(ln(relativeArea)) + .14f * abs(ln(relativeAspect)) -
                .30f * intersectionOverUnion(predicted, box) - .07f * box.confidence
            Candidate(box, cost)
        }.sortedBy { it.cost }
        val best = scores.firstOrNull()
        val runnerUp = scores.getOrNull(1)
        val alternatives = scores.drop(1)
        val overlappingLookalikes = best != null && alternatives.any { other ->
            appearanceDistance(best.box.appearance, other.box.appearance) < .08f &&
                intersectionOverUnion(best.box, other.box) > .35f
        }
        // If assigning the winner to the target strands a known nearby rival, a greedy
        // prediction can silently swap dancers during a turn. Check the alternate pairings
        // before accepting it. This bounded guard is O(candidates * rivals), not a claim to
        // solve identity through arbitrary occlusion; uncertainty deliberately wins.
        fun reachable(from: PersonBox, to: PersonBox): Boolean {
            val dx = (.08f + .55f * from.width + .22f * seconds).coerceAtMost(.40f)
            val dy = (.06f + .20f * from.height + .20f * seconds).coerceAtMost(.35f)
            return hypot((to.cx - from.cx) / dx, (to.cy - from.cy) / dy) <= 1f
        }
        val contestedByRival = best != null &&
            (scores.size == 1 || alternatives.any { it.cost < 1.15f && reachable(last, it.box) }) &&
            rivals.any { rival ->
                appearanceDistance(rival.appearance, best.box.appearance) < .08f &&
                    reachable(rival, best.box) && candidates.none { other ->
                        other != best.box && usable(other) &&
                            appearanceDistance(rival.appearance, other.appearance) < .16f &&
                            reachable(rival, other)
                    }
            }
        if (overlappingLookalikes || contestedByRival) unresolvedLookalikes = true
        val ambiguous = best != null && (overlappingLookalikes || contestedByRival ||
            (unresolvedLookalikes && scores.size == 1) ||
            (runnerUp != null && runnerUp.cost - best.cost < .18f))
        if (best == null || best.cost >= 1.15f || ambiguous) {
            return IdentityMatch(if (ambiguous) MatchStatus.AMBIGUOUS else MatchStatus.MISSING)
        }

        val choice = best.box
        val observationVx = ((choice.cx - last.cx) / seconds).coerceIn(-.9f, .9f)
        val observationVy = ((choice.cy - last.cy) / seconds).coerceIn(-.9f, .9f)
        // A supported reversal should not retain most of the old predictive velocity.
        val reversed = speedX * observationVx + speedY * observationVy < -.005f
        val weight = if (reversed) .85f else .55f
        speedX += (observationVx - speedX) * weight
        speedY += (observationVy - speedY) * weight
        recentAppearance = recentAppearance.indices.map { index ->
            recentAppearance[index] * .75f + choice.appearance[index] * .25f
        }
        last = choice
        lastMatchUs = timeUs
        rivals = candidates.filter { it != choice && usable(it) }.take(24)
        unresolvedLookalikes = false
        return IdentityMatch(MatchStatus.MATCHED, choice,
            if (runnerUp == null) 1f else (runnerUp.cost - best.cost).coerceAtLeast(0f))
    }

    private fun usable(box: PersonBox): Boolean = box.valid() && box.hasValidAppearance() &&
        box.appearance.size == initial.appearance.size

    private fun appearanceDistance(a: List<Float>, b: List<Float>): Float =
        (a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size).toFloat()

    private fun intersectionOverUnion(a: PersonBox, b: PersonBox): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersection / max(.0001f, a.area + b.area - intersection)
    }

    companion object {
        const val MAX_RECOVERY_GAP_US = 1_250_000L
    }
}
