package com.caneretabito.motionpin.tracking

import com.caneretabito.motionpin.player.VideoViewport
import com.caneretabito.motionpin.player.ViewportGeometry
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
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

    /** An absent/corrupt signature is unknown identity, never a zero-distance match. */
    fun hasValidAppearance() = appearance.isNotEmpty() &&
        appearance.all { it.isFinite() && it in 0f..1f }
}

data class TrackingKeyframe(val sourceTimeUs: Long, val box: PersonBox)
enum class MatchStatus { MATCHED, MISSING, AMBIGUOUS, LOST }
data class IdentityMatch(val status: MatchStatus, val box: PersonBox? = null, val margin: Float = 0f)

/** Backwards-compatible stateless facade, including conservative multi-candidate ambiguity. */
object PersonAssociation {
    fun choose(previous: PersonBox, candidates: List<PersonBox>): PersonBox? =
        if (!previous.valid() || !previous.hasValidAppearance()) null
        else StablePersonMatcher(previous, 0).step(500_000L, candidates).box
}

enum class DancerFraming(val label: String, val focusY: Float, val minimumZoom: Float,
                         val regionTop: Float, val regionBottom: Float) {
    FULL("全身", .50f, 1.65f, 0f, 1f),
    UPPER("上半身", .30f, 2.50f, 0f, .60f),
    LOWER("脚・フットワーク", .73f, 2.65f, .46f, 1f)
}

object TrackingTrajectory {
    /** Hold at most 250ms after the final observation; no invented post-observation velocity. */
    fun at(keyframes: List<TrackingKeyframe>, timeUs: Long, maxGapUs: Long = 900_000L): PersonBox? {
        if (keyframes.isEmpty() || maxGapUs < 0L || timeUs < keyframes.first().sourceTimeUs) return null
        if (timeUs >= keyframes.last().sourceTimeUs) return keyframes.last().box.takeIf {
            timeUs - keyframes.last().sourceTimeUs <= 250_000L && it.valid()
        }
        var low = 0
        var high = keyframes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].sourceTimeUs < timeUs) low = mid + 1 else high = mid
        }
        val right = keyframes.getOrElse(low) { keyframes.last() }
        // A real confirmed recovery is usable even when the preceding gap is too long to bridge.
        if (right.sourceTimeUs == timeUs) return right.box.takeIf { it.valid() }
        val left = if (low == 0) right else keyframes[low - 1]
        val distance = right.sourceTimeUs - left.sourceTimeUs
        if (distance <= 0L || distance > maxGapUs || !left.box.valid() || !right.box.valid()) return null
        val weight = ((timeUs - left.sourceTimeUs).toDouble() / distance).toFloat().coerceIn(0f, 1f)
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
    private var lastGoalX: Float? = null
    private var lastGoalY: Float? = null
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFraming: DancerFraming? = null
    private var lastGeometry: ViewportGeometry? = null
    private var lastUserZoom = 1f
    private var resuming = false

    fun reset() {
        lastUs = null
        lastGoalX = null; lastGoalY = null
        velocityX = 0f; velocityY = 0f
        resuming = false
    }
    fun resumeFrom(viewport: VideoViewport, sourceTimeUs: Long) {
        val safe = viewport.bounded()
        x = safe.centerX; y = safe.centerY; zoom = safe.zoom
        lastGoalX = null; lastGoalY = null
        velocityX = 0f; velocityY = 0f
        lastUs = sourceTimeUs
        resuming = true
    }
    fun setUserZoom(value: Float) { if (value.isFinite()) userZoom = value.coerceIn(1f, 8f) }

    fun follow(timeUs: Long, box: PersonBox, current: VideoViewport, framing: DancerFraming,
               geometry: ViewportGeometry = ViewportGeometry()): VideoViewport {
        if (timeUs < 0 || !box.valid()) return current.bounded()
        val goalX = box.cx.coerceIn(0f, 1f)
        val goalY = (box.top + box.height * framing.focusY).coerceIn(0f, 1f)
        val regionTop = box.top + box.height * framing.regionTop
        val regionBottom = box.top + box.height * framing.regionBottom
        val regionHeight = regionBottom - regionTop
        val fitBody = .90f * geometry.heightAtUnitZoom / regionHeight
        val fitWidth = .90f * geometry.widthAtUnitZoom / box.width
        val safeRegionZoom = min(fitBody, fitWidth).coerceIn(1f, 8f)
        // Leave a comfort band between desired framing and the hard containment boundary.
        // Clamping to the desired 10% padding every tick would bypass zoom damping whenever
        // the detector's body height changes slightly, even with the entire body still visible.
        val containmentZoom = min(.98f * geometry.heightAtUnitZoom / regionHeight,
            .98f * geometry.widthAtUnitZoom / box.width).coerceIn(1f, 8f)
        // Adapt to a distant dancer as well as a close-up, without claiming extra source detail.
        // FULL tracking uses FIT in the UI; in FILL a region may not fit even at 1x.
        val subjectZoom = .72f * geometry.heightAtUnitZoom / regionHeight
        val targetZoom = min(max(max(userZoom, framing.minimumZoom), subjectZoom), safeRegionZoom)
        val previousTime = lastUs
        val elapsedUs = if (previousTime == null) 0L else timeUs - previousTime
        val changed = lastFraming != framing || lastGeometry != geometry || lastUserZoom != userZoom
        // A paused frame must not drift each time the UI asks for the same source timestamp.
        if (previousTime != null && elapsedUs == 0L && (!changed || resuming)) return current.bounded()
        val snap = previousTime == null || elapsedUs < 0L || elapsedUs > 900_000L ||
            (elapsedUs == 0L && changed)
        if (snap) {
            x = goalX; y = goalY; zoom = targetZoom
            velocityX = 0f; velocityY = 0f
        } else {
            val seconds = elapsedUs / 1_000_000.0
            val velocityWeight = (1.0 - exp(-seconds / .12)).toFloat()
            fun speed(goal: Float, old: Float?): Float {
                val delta = goal - (old ?: goal)
                // A source-time speed threshold avoids changing behavior merely because
                // slow playback or a busy UI polls the same motion at a different interval.
                return if (abs(delta) < .24 * seconds) 0f else (delta / seconds).toFloat().coerceIn(-1f, 1f)
            }
            val observedX = speed(goalX, lastGoalX)
            val observedY = speed(goalY, lastGoalY)
            val continuingX = observedX * velocityX > .0001f
            val continuingY = observedY * velocityY > .0001f
            velocityX += (observedX - velocityX) * velocityWeight
            velocityY += (observedY - velocityY) * velocityWeight
            val motion = hypot(velocityX, velocityY)
            val responseSeconds = (.19f - .20f * motion).coerceIn(.075f, .19f)
            val weight = (1.0 - exp(-seconds / responseSeconds)).toFloat()
            val leadX = if (continuingX) (velocityX * .035f).coerceIn(-box.width * .15f, box.width * .15f) else 0f
            val leadY = if (continuingY) (velocityY * .035f).coerceIn(-box.height * .10f, box.height * .10f) else 0f
            val deadzone = .0035f
            if (abs(goalX + leadX - x) > deadzone) x += (goalX + leadX - x) * weight
            if (abs(goalY + leadY - y) > deadzone) y += (goalY + leadY - y) * weight
            val zoomWeight = (1.0 - exp(-seconds / .55)).toFloat()
            if (abs(targetZoom - zoom) > .025f) zoom += (targetZoom - zoom) * zoomWeight
        }
        if (!resuming) {
            // Smoothing cannot leave an approaching/jumping body outside the current crop.
            // User-requested manual resume is allowed to ease from the held composition.
            zoom = min(zoom, containmentZoom)
            fun contain(center: Float, first: Float, last: Float, visible: Float): Float =
                if (last - first <= visible) center.coerceIn(last - visible / 2f, first + visible / 2f)
                else center
            x = contain(x, box.left, box.right, geometry.visibleWidth(zoom))
            y = contain(y, regionTop, regionBottom, geometry.visibleHeight(zoom))
        }
        // Once close enough, restore strict full-body safety after a manually held crop.
        if (resuming && abs(x - goalX) < .02f && abs(y - goalY) < .02f && abs(zoom - targetZoom) < .04f)
            resuming = false
        lastUs = timeUs
        lastGoalX = goalX; lastGoalY = goalY
        lastFraming = framing; lastGeometry = geometry; lastUserZoom = userZoom
        return current.copy(zoom = zoom.coerceIn(1f, 8f),
            centerX = x.coerceIn(0f, 1f), centerY = y.coerceIn(0f, 1f))
    }
}
