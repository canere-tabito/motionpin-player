package com.caneretabito.motionpin.player

import kotlin.math.max
import kotlin.math.min

/** Player timestamps are local to a clipped source or to a speed-baked cache. */
data class PlaybackTimeline(
    val sourceStartUs: Long = 0,
    val sourceEndUs: Long,
    val bakedSpeed: Double = 1.0,
    val cached: Boolean = false,
) {
    init {
        require(sourceStartUs >= 0 && sourceEndUs > sourceStartUs)
        require(bakedSpeed > 0 && bakedSpeed.isFinite())
    }
    fun toSourceUs(playerUs: Long): Long =
        sourceStartUs + (playerUs.coerceAtLeast(0) * bakedSpeed).toLong()
            .coerceIn(0, sourceEndUs - sourceStartUs - 1)
    fun toPlayerUs(sourceUs: Long): Long =
        ((sourceUs.coerceIn(sourceStartUs, sourceEndUs - 1) - sourceStartUs) / bakedSpeed).toLong()
    fun effectivePlayerSpeed(requested: Float): Float = if (cached) 1f else requested
}

/** Center coordinates refer to the video after its rotation metadata has been applied. */
data class VideoViewport(val zoom: Float = 1f, val centerX: Float = .5f, val centerY: Float = .5f) {
    fun bounded() = copy(zoom = zoom.coerceIn(1f, 8f), centerX = centerX.coerceIn(0f, 1f), centerY = centerY.coerceIn(0f, 1f))
    fun pan(dx: Float, dy: Float, videoWidth: Float, videoHeight: Float): VideoViewport = copy(
        centerX = centerX - dx / max(1f, videoWidth * zoom),
        centerY = centerY - dy / max(1f, videoHeight * zoom),
    ).bounded()
}

data class ViewportTransform(val baseWidth: Float, val baseHeight: Float, val translateX: Float, val translateY: Float)

fun viewportTransform(containerWidth: Float, containerHeight: Float, aspect: Float, fill: Boolean, view: VideoViewport): ViewportTransform {
    val safeAspect = aspect.takeIf { it > 0 && it.isFinite() } ?: (16f / 9f)
    val fitWidth = if (containerWidth / max(1f, containerHeight) > safeAspect) containerHeight * safeAspect else containerWidth
    val fitHeight = fitWidth / safeAspect
    val fillScale = if (fill) max(containerWidth / max(1f, fitWidth), containerHeight / max(1f, fitHeight)) else 1f
    val width = fitWidth * fillScale
    val height = fitHeight * fillScale
    val xExtent = max(0f, (width * view.zoom - containerWidth) / 2f)
    val yExtent = max(0f, (height * view.zoom - containerHeight) / 2f)
    return ViewportTransform(width, height,
        ((.5f - view.centerX) * width * view.zoom).coerceIn(-xExtent, xExtent),
        ((.5f - view.centerY) * height * view.zoom).coerceIn(-yExtent, yExtent))
}

fun formatTimestamp(us: Long, millis: Boolean = false): String {
    val ms = us.coerceAtLeast(0) / 1_000
    val seconds = ms / 1_000
    val h = seconds / 3_600
    val base = if (h > 0) "%d:%02d:%02d".format(h, (seconds / 60) % 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    return if (millis) "$base.%03d".format(ms % 1_000) else base
}

/** Decimal seconds use integer arithmetic, so 0.1 s edits do not accumulate float error. */
fun parseSecondsUs(text: String): Long? {
    if (!text.matches(Regex("[0-9]+(?:\\.[0-9]{1,6})?"))) return null
    val parts = text.split('.')
    val whole = parts[0].toLongOrNull() ?: return null
    if (whole > Long.MAX_VALUE / 1_000_000L) return null
    val fraction = parts.getOrNull(1)?.padEnd(6, '0')?.toLongOrNull() ?: 0L
    return runCatching { Math.addExact(Math.multiplyExact(whole, 1_000_000L), fraction) }.getOrNull()
}

fun secondsText(us: Long): String = "%d.%03d".format(java.util.Locale.ROOT, us.coerceAtLeast(0) / 1_000_000, (us.coerceAtLeast(0) % 1_000_000) / 1_000)
