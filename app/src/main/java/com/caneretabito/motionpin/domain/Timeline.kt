package com.caneretabito.motionpin.domain

import java.math.BigInteger

private val minimumLong = BigInteger.valueOf(Long.MIN_VALUE)
private val maximumLong = BigInteger.valueOf(Long.MAX_VALUE)

/** BigInteger.longValueExact is API31; preserve exact overflow behavior on API29/30. */
private fun BigInteger.exactLong(): Long {
    if (this < minimumLong || this > maximumLong) throw ArithmeticException("Timestamp exceeds the signed 64-bit range")
    return toLong()
}

/** Exact rate. 59.94 must be represented as 60000/1001, not rounded to 60. */
data class Rational(val numerator: Long, val denominator: Long = 1) {
    init { require(numerator > 0 && denominator > 0) { "Rates must be positive" } }
    fun toDouble(): Double = numerator.toDouble() / denominator
    fun normalized(): Rational {
        val gcd = numerator.toBigInteger().gcd(denominator.toBigInteger()).exactLong()
        return Rational(numerator / gcd, denominator / gcd)
    }
    fun scaleFloor(value: Long): Long = multiplyDivide(value, numerator, denominator)
    fun inverse(): Rational = Rational(denominator, numerator)
    companion object {
        val ONE = Rational(1)
        val HALF = Rational(1, 2)
        val QUARTER = Rational(1, 4)
        val FPS_60 = Rational(60)
        val FPS_5994 = Rational(60_000, 1_001)
        fun fromSpeed(speed: Float): Rational = when (speed) {
            .25f -> QUARTER; .5f -> HALF; .75f -> Rational(3, 4)
            1f -> ONE; 1.5f -> Rational(3, 2); 2f -> Rational(2)
            else -> throw IllegalArgumentException("Unsupported playback speed")
        }
    }
}

private fun multiplyDivide(a: Long, b: Long, divisor: Long): Long {
    require(a >= 0 && b >= 0 && divisor > 0)
    return (a.toBigInteger() * b.toBigInteger() / divisor.toBigInteger()).exactLong()
}

/** All stored timestamps use original-video microseconds; caches contain baked slow motion. */
data class TimeMapping(val startUs: Long, val endUs: Long, val speed: Rational) {
    init { require(startUs >= 0 && endUs > startUs) }
    val outputDurationUs: Long get() = speed.inverse().scaleFloor(endUs - startUs)
    fun sourceTimeUs(outputTimeUs: Long): Long {
        require(outputTimeUs >= 0 && outputTimeUs <= outputDurationUs)
        return Math.addExact(startUs, speed.scaleFloor(outputTimeUs)).coerceAtMost(endUs)
    }
    fun outputTimeUs(sourceTimeUs: Long): Long {
        require(sourceTimeUs in startUs..endUs)
        return speed.inverse().scaleFloor(sourceTimeUs - startUs)
    }
    fun outputPtsUs(frameIndex: Long, fps: Rational): Long {
        require(frameIndex >= 0)
        return (frameIndex.toBigInteger() * BigInteger.valueOf(1_000_000) * fps.denominator.toBigInteger() /
            fps.numerator.toBigInteger()).exactLong()
    }
    /** Derived from frame index on every call: rounded microseconds are never accumulated. */
    fun sourceTimeForFrameUs(frameIndex: Long, fps: Rational): Long {
        require(frameIndex >= 0)
        val delta = frameIndex.toBigInteger() * BigInteger.valueOf(1_000_000) * fps.denominator.toBigInteger() * speed.numerator.toBigInteger() /
            (fps.numerator.toBigInteger() * speed.denominator.toBigInteger())
        return Math.addExact(startUs, delta.exactLong()).coerceAtMost(endUs)
    }
    fun outputFrameCount(fps: Rational): Long {
        val top = (endUs - startUs).toBigInteger() * speed.denominator.toBigInteger() * fps.numerator.toBigInteger()
        val bottom = speed.numerator.toBigInteger() * BigInteger.valueOf(1_000_000) * fps.denominator.toBigInteger()
        return ((top + bottom - BigInteger.ONE) / bottom).exactLong()
    }
    fun playerSpeed(cacheIsBaked: Boolean): Float = if (cacheIsBaked) 1f else speed.toDouble().toFloat()
}

sealed class FrameSelection {
    data class Original(val index: Int, val reason: String) : FrameSelection()
    data class Interpolate(val before: Int, val after: Int, val numerator: Long, val denominator: Long) : FrameSelection() {
        val alpha: Float get() = numerator.toDouble().div(denominator).toFloat()
    }
}

/** Presentation order only, including VFR. Decode order/B-frame indexes are not time indexes. */
object FrameSelector {
    fun select(ptsUs: List<Long>, targetUs: Long, cutBefore: Set<Int> = emptySet()): FrameSelection {
        require(ptsUs.isNotEmpty() && targetUs >= 0)
        require(ptsUs.all { it >= 0 } && ptsUs.zipWithNext().all { (a, b) -> a <= b }) { "PTS must be in presentation order" }
        var low = 0
        var high = ptsUs.size
        // Upper bound makes duplicate PTS deterministic and prevents a zero interpolation divisor.
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ptsUs[mid] <= targetUs) low = mid + 1 else high = mid
        }
        if (low == 0) return FrameSelection.Original(0, "leading boundary")
        val before = low - 1
        if (ptsUs[before] == targetUs) return FrameSelection.Original(before, "exact source PTS")
        if (low == ptsUs.size) return FrameSelection.Original(before, "trailing boundary")
        if (low in cutBefore) return FrameSelection.Original(before, "scene cut: hold original")
        return FrameSelection.Interpolate(before, low, targetUs - ptsUs[before], ptsUs[low] - ptsUs[before])
    }
}

/** Center is in the rotated video's normalized space, never in screen/letterbox coordinates. */
data class ZoomState(val scale: Float = 1f, val centerX: Float = .5f, val centerY: Float = .5f) {
    init { require(scale.isFinite() && scale in 1f..8f && centerX.isFinite() && centerY.isFinite() && centerX in 0f..1f && centerY in 0f..1f) }
    fun pan(dxNormalized: Float, dyNormalized: Float) = copy(
        centerX = (centerX - dxNormalized / scale).coerceIn(0f, 1f),
        centerY = (centerY - dyNormalized / scale).coerceIn(0f, 1f))
}

object PinBounds {
    fun validate(startUs: Long, endUs: Long?, durationUs: Long) {
        require(durationUs > 0 && startUs >= 0 && startUs < durationUs) { "Point must be inside the source duration" }
        require(endUs == null || (endUs > startUs && endUs <= durationUs)) { "Interval must satisfy 0 ≤ start < end ≤ duration" }
    }
}
