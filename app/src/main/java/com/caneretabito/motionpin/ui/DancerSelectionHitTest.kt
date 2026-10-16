package com.caneretabito.motionpin.ui

import com.caneretabito.motionpin.tracking.PersonBox
import kotlin.math.max

/** All overlapping people remain selectable; a near miss may hit a small person's 48dp target. */
internal fun dancerHitCandidates(
    people: List<PersonBox>, xPx: Float, yPx: Float,
    widthPx: Float, heightPx: Float, minimumTouchPx: Float,
): List<Int> {
    if (listOf(xPx, yPx, widthPx, heightPx, minimumTouchPx).any { !it.isFinite() } ||
        widthPx <= 0f || heightPx <= 0f || minimumTouchPx < 0f ||
        xPx !in 0f..widthPx || yPx !in 0f..heightPx) return emptyList()
    val valid = people.withIndex().filter { it.value.valid() }
    val exact = valid.filter { it.value.contains(xPx / widthPx, yPx / heightPx) }
    if (exact.isNotEmpty()) return exact.sortedWith(compareBy({ it.value.area }, { it.index })).map { it.index }
    return valid.filter { (_, box) ->
        val halfWidth = max(box.width * widthPx, minimumTouchPx) / 2f
        val halfHeight = max(box.height * heightPx, minimumTouchPx) / 2f
        xPx in (box.cx * widthPx - halfWidth)..(box.cx * widthPx + halfWidth) &&
            yPx in (box.cy * heightPx - halfHeight)..(box.cy * heightPx + halfHeight)
    }.sortedWith(compareBy({ (_, box) ->
        val dx = box.cx * widthPx - xPx
        val dy = box.cy * heightPx - yPx
        dx * dx + dy * dy
    }, { it.index })).map { it.index }
}

/** Repeated taps on an overlap cycle through it without starting tracking prematurely. */
internal fun nextDancerSelection(hits: List<Int>, current: Int?): Int? = when {
    hits.isEmpty() -> current
    current !in hits -> hits.first()
    else -> hits[(hits.indexOf(current) + 1) % hits.size]
}
