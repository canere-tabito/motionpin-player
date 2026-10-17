package com.caneretabito.motionpin.thumbnail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The complete source identity is also the memory-cache key; relinking must not reuse old pixels. */
data class ThumbnailRequest(
    val sourceId: String,
    val uri: String,
    val revision: Long,
    val modifiedSeconds: Long = 0,
    val sizeBytes: Long = 0,
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val maxWidth: Int = 320,
    val maxHeight: Int = 180,
)

data class ThumbnailSize(val width: Int, val height: Int)

object ThumbnailPolicy {
    const val MIN_DETAIL_SCORE = .045f
    private const val MAX_SIDE = 512

    /** A few sync-frame seeks, away from a fade-in; never walk/decode the whole video. */
    fun frameTimesUs(durationUs: Long): List<Long> {
        if (durationUs <= 0L) return listOf(1_000_000L, 3_000_000L, 0L)
        val last = durationUs - 1L
        return listOf(minOf(2_000_000L, durationUs / 10L),
            minOf(10_000_000L, durationUs / 3L), minOf(30_000_000L, durationUs / 2L), 0L)
            .map { it.coerceIn(0L, last) }.distinct()
    }

    /** Aspect-preserving downscale only. Bounds protect against malformed source/provider sizes. */
    fun scaledSize(width: Int, height: Int, maxWidth: Int = 320, maxHeight: Int = 180): ThumbnailSize {
        val boundWidth = maxWidth.coerceIn(1, MAX_SIDE)
        val boundHeight = maxHeight.coerceIn(1, MAX_SIDE)
        if (width <= 0 || height <= 0) return ThumbnailSize(boundWidth, boundHeight)
        val scale = minOf(1.0, boundWidth.toDouble() / width, boundHeight.toDouble() / height)
        return ThumbnailSize((width * scale).roundToInt().coerceIn(1, boundWidth),
            (height * scale).roundToInt().coerceIn(1, boundHeight))
    }

    /** Prefer visible detail over a flat black/white lead-in. This is not an image-quality claim. */
    fun detailScore(argb: IntArray): Float {
        if (argb.isEmpty()) return 0f
        var sum = 0.0
        var squareSum = 0.0
        var minimum = 255.0
        var maximum = 0.0
        argb.forEach { pixel ->
            val alpha = (pixel ushr 24 and 255) / 255.0
            val luma = ((pixel ushr 16 and 255) * .2126 + (pixel ushr 8 and 255) * .7152 +
                (pixel and 255) * .0722) * alpha
            sum += luma
            squareSum += luma * luma
            minimum = minOf(minimum, luma)
            maximum = maxOf(maximum, luma)
        }
        val mean = sum / argb.size
        val variance = (squareSum / argb.size - mean * mean).coerceAtLeast(0.0)
        return ((sqrt(variance) + .15 * (maximum - minimum)) / 255.0).toFloat().coerceIn(0f, 1f)
    }
}

/** Provider and decoder ownership are explicit so failure/cancellation policy is JVM-testable. */
interface ThumbnailBackend<T : Any> {
    suspend fun provider(): T?
    suspend fun frame(timeUs: Long): T?
    fun score(image: T): Float
    fun discard(image: T)
}

/** Returns ownership of one image; every rejected or cancelled candidate is discarded. */
suspend fun <T : Any> chooseThumbnail(request: ThumbnailRequest, backend: ThumbnailBackend<T>): T? {
    var best: T? = null
    var bestScore = -1f
    suspend fun read(block: suspend () -> T?): T? {
        currentCoroutineContext().ensureActive()
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); null }
    }
    suspend fun consider(candidate: T?): Boolean {
        if (candidate == null) return false
        var adopted = false
        try {
            currentCoroutineContext().ensureActive()
            val score = backend.score(candidate).takeIf { it.isFinite() } ?: 0f
            if (score > bestScore) {
                best?.takeIf { it !== candidate }?.let(backend::discard)
                best = candidate
                bestScore = score
                adopted = true
            }
            return bestScore >= ThumbnailPolicy.MIN_DETAIL_SCORE
        } finally {
            if (!adopted && candidate !== best) backend.discard(candidate)
        }
    }
    try {
        if (!consider(read { backend.provider() })) {
            for (timeUs in ThumbnailPolicy.frameTimesUs(request.durationUs)) {
                if (consider(read { backend.frame(timeUs) })) break
            }
        }
        currentCoroutineContext().ensureActive()
        return best.also { best = null }
    } finally {
        best?.let(backend::discard)
    }
}
