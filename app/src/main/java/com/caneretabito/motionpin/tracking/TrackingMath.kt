package com.caneretabito.motionpin.tracking

import com.caneretabito.motionpin.player.VideoViewport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Boxes and appearance samples are always in the displayed, normalized video coordinate system. */
data class PersonBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val confidence: Float, val appearance: List<Float>
) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun valid() = listOf(left, top, right, bottom, confidence).all { it.isFinite() } &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
        width > .012f && height > .026f && confidence in .20f..1f
}

data class TrackingKeyframe(val sourceTimeUs: Long, val box: PersonBox)
enum class MatchStatus { MATCHED, MISSING, AMBIGUOUS, LOST }
data class IdentityMatch(val status: MatchStatus, val box: PersonBox? = null, val margin: Float = 0f)

/**
 * Pure, testable identity association. Motion prediction, body proportions, reference appearance
 * and an explicit second-best margin are all required. A similar-cost candidate is not a match.
 * This is conservative short-term tracking, NOT biometric/person reidentification.
 */
class StablePersonMatcher(seed: PersonBox, startUs: Long) {
    private val initial = seed
    private var last = seed
    private var lastTimeUs = startUs
    private var speedX = 0f
    private var speedY = 0f
    private var missingCount = 0
    private var missingStartedUs: Long? = null
    private var permanentlyLost = false

    init { require(seed.valid() && startUs >= 0) }

    fun step(timeUs: Long, candidates: List<PersonBox>): IdentityMatch {
        if (permanentlyLost) return IdentityMatch(MatchStatus.LOST)
        if (timeUs <= lastTimeUs) return IdentityMatch(MatchStatus.MISSING)
        val seconds = ((timeUs - lastTimeUs) / 1_000_000.0).toFloat().coerceIn(.001f, 2f)
        val predictedX = (last.cx + speedX * seconds).coerceIn(0f, 1f)
        val predictedY = (last.cy + speedY * seconds).coerceIn(0f, 1f)
        val travelLimit = (max(.19f, max(last.width, last.height) * .80f) + .14f * seconds)
            .coerceAtMost(.55f)
        data class Candidate(val box: PersonBox, val cost: Float)
        val scores = candidates.mapNotNull { box ->
            if (!box.valid()) return@mapNotNull null
            val distance = hypot(box.cx - predictedX, box.cy - predictedY)
            val distanceFromLast = hypot(box.cx - last.cx, box.cy - last.cy)
            if (distance > travelLimit || distanceFromLast > travelLimit * 1.5f) return@mapNotNull null
            val relativeArea = box.area / max(.0001f, last.area)
            if (relativeArea !in .23f..4.2f) return@mapNotNull null
            val appearanceToOriginal = appearanceDistance(initial.appearance, box.appearance)
            val appearanceToRecent = appearanceDistance(last.appearance, box.appearance)
            if (appearanceToOriginal > .34f || appearanceToRecent > .36f) return@mapNotNull null
            val overlap = max(0f, min(last.right, box.right) - max(last.left, box.left)) *
                max(0f, min(last.bottom, box.bottom) - max(last.top, box.top))
            val iou = overlap / max(.0001f, last.area + box.area - overlap)
            val bodyRatio = abs(ln(relativeArea))
            val appearanceCost = .55f * appearanceToOriginal + .45f * appearanceToRecent
            val cost = 1.55f * distance / travelLimit + .95f * appearanceCost +
                .22f * bodyRatio - .38f * iou - .07f * box.confidence
            Candidate(box, cost)
        }.sortedBy { it.cost }
        val best = scores.firstOrNull()
        val runnerUp = scores.getOrNull(1)
        val ambiguous = best != null && runnerUp != null && runnerUp.cost - best.cost < .18f
        val acceptable = best != null && best.cost < 1.22f && !ambiguous
        if (!acceptable) {
            missingCount++
            if (missingStartedUs == null) missingStartedUs = timeUs
            // Allow a short gap, but NEVER jump to a near-tied dancer.
            if (missingCount >= 5 || timeUs - (missingStartedUs ?: timeUs) >= 1_250_000L) {
                permanentlyLost = true
                return IdentityMatch(MatchStatus.LOST)
            }
            return IdentityMatch(if (ambiguous) MatchStatus.AMBIGUOUS else MatchStatus.MISSING)
        }
        val choice = checkNotNull(best).box
        // Limit predictive velocity; jumps and shot cuts may otherwise project wildly.
        val observationVx = ((choice.cx - last.cx) / seconds).coerceIn(-.9f, .9f)
        val observationVy = ((choice.cy - last.cy) / seconds).coerceIn(-.9f, .9f)
        speedX = speedX * .45f + observationVx * .55f
        speedY = speedY * .45f + observationVy * .55f
        last = choice
        lastTimeUs = timeUs
        missingCount = 0
        missingStartedUs = null
        return IdentityMatch(MatchStatus.MATCHED, choice,
            if (runnerUp == null) 1f else (runnerUp.cost - best.cost).coerceAtLeast(0f))
    }

    private fun appearanceDistance(a: List<Float>, b: List<Float>): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        return (a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size).toFloat()
    }
}

/** Backwards-compatible stateless facade, including conservative multi-candidate ambiguity. */
object PersonAssociation {
    fun choose(previous: PersonBox, candidates: List<PersonBox>): PersonBox? =
        if (!previous.valid()) null else StablePersonMatcher(previous, 0).step(500_000L, candidates).box
}

enum class DancerFraming(val label: String, val focusY: Float, val minimumZoom: Float) {
    FULL("全身", .50f, 1.65f),
    UPPER("上半身", .30f, 2.50f),
    LOWER("脚・フットワーク", .73f, 2.65f)
}

object TrackingTrajectory {
    /** Never extrapolate more than 250ms outside actually validated detections. */
    fun at(keyframes: List<TrackingKeyframe>, timeUs: Long, maxGapUs: Long = 900_000L): PersonBox? {
        if (keyframes.isEmpty() || timeUs < keyframes.first().sourceTimeUs ||
            timeUs > keyframes.last().sourceTimeUs + 250_000L) return null
        if (timeUs >= keyframes.last().sourceTimeUs) return keyframes.last().box
        var low = 0
        var high = keyframes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].sourceTimeUs < timeUs) low = mid + 1 else high = mid
        }
        val right = keyframes.getOrElse(low) { keyframes.last() }
        val left = if (low == 0) right else keyframes[low - 1]
        val distance = right.sourceTimeUs - left.sourceTimeUs
        if (distance > maxGapUs) return null // An occluded gap must not be smoothly faked over.
        val weight = if (distance == 0L) 0f else
            ((timeUs - left.sourceTimeUs).toDouble() / distance).toFloat().coerceIn(0f, 1f)
        fun blend(a: Float, b: Float) = a + (b - a) * weight
        return left.box.copy(
            left = blend(left.box.left, right.box.left),
            top = blend(left.box.top, right.box.top),
            right = blend(left.box.right, right.box.right),
            bottom = blend(left.box.bottom, right.box.bottom),
            confidence = min(left.box.confidence, right.box.confidence))
    }
}

/**
 * Damp rapid crop jitter but snap after a seek or a backwards jump. Smoothing
 * uses source timestamps, so slow playback, rewind and cache playback are deterministic.
 */
class SmoothFollowCamera {
    private var lastUs: Long? = null
    private var x = .5f
    private var y = .5f
    private var zoom = 1f
    private var userZoom = 1f

    fun reset() { lastUs = null }
    fun resumeFrom(viewport: VideoViewport, sourceTimeUs: Long) {
        x = viewport.centerX.coerceIn(0f, 1f)
        y = viewport.centerY.coerceIn(0f, 1f)
        zoom = viewport.zoom.coerceIn(1f, 8f)
        lastUs = sourceTimeUs
    }
    fun setUserZoom(value: Float) { if (value.isFinite()) userZoom = value.coerceIn(1f, 8f) }

    fun follow(timeUs: Long, box: PersonBox, current: VideoViewport, framing: DancerFraming): VideoViewport {
        val goalX = box.cx.coerceIn(0f, 1f)
        val goalY = (box.top + box.height * framing.focusY).coerceIn(0f, 1f)
        val targetZoom = if (framing == DancerFraming.FULL) {
            // Keep the entire detected body with ~5% top/bottom safety room even when
            // it approaches the lens. Zoom must never be forced beyond a full-body crop.
            val fitBody = (.90f / max(.05f, box.height)).coerceIn(1f, 8f)
            val fitWidth = (.90f / max(.05f, box.width)).coerceIn(1f, 8f)
            min(max(userZoom, framing.minimumZoom), min(fitBody, fitWidth))
        } else max(userZoom, framing.minimumZoom)
        val previousTime = lastUs
        val elapsedUs = if (previousTime == null) 0L else timeUs - previousTime
        val snap = previousTime == null || elapsedUs < 0L || elapsedUs > 900_000L
        if (snap) {
            x = goalX; y = goalY; zoom = targetZoom
        } else {
            val seconds = elapsedUs / 1_000_000.0
            val weight = (1.0 - exp(-seconds / .13)).toFloat().coerceIn(.05f, 1f)
            val deadzone = .0025f
            if (abs(goalX - x) > deadzone) x += (goalX - x) * weight
            if (abs(goalY - y) > deadzone) y += (goalY - y) * weight
            val zoomWeight = (1.0 - exp(-seconds / .45)).toFloat().coerceIn(.03f, 1f)
            zoom += (targetZoom - zoom) * zoomWeight
        }
        lastUs = timeUs
        return current.copy(zoom = zoom.coerceIn(1f, 8f),
            centerX = x.coerceIn(0f, 1f), centerY = y.coerceIn(0f, 1f))
    }
}
