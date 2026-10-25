package com.autobot.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class TextRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class FramingChange(val ratio: Float, val searching: Boolean)

/** Conservative, center-crop-aware controller. Coordinates refer to the entire analysis image. */
internal class AutoFramingController {
    private var previous: TextRegion? = null
    private var confirmations = 0
    private var emptyFrames = 0
    private var searchStep = 0
    private var nextChangeAt = 0L

    fun reset(now: Long) {
        previous = null; confirmations = 0; emptyFrames = 0; searchStep = 0
        nextChangeAt = now + 1800L
    }

    fun manualOverride(now: Long) { reset(now); nextChangeAt = now + 12_000L }

    fun observe(regions: List<TextRegion>, ratio: Float, minZoom: Float, maxZoom: Float, now: Long): FramingChange? {
        if (!ratio.isFinite() || ratio <= 0 || minZoom <= 0 || maxZoom < minZoom) return null
        val valid = regions.filter { it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 && it.right > it.left && it.bottom > it.top }
        if (valid.isEmpty()) {
            previous = null; confirmations = 0; emptyFrames++
            if (now < nextChangeAt || emptyFrames < 4 || ratio > 1.6f) return null
            // A bounded center search; return to the wide view if no text appears.
            val target = listOf(1.25f, 1.5f, 1f)[searchStep].coerceIn(minZoom, maxZoom)
            searchStep = (searchStep + 1) % 3
            nextChangeAt = now + if (searchStep == 0) 15_000L else 3500L
            emptyFrames = 0
            if (abs(target - ratio) < 0.05f) return null
            return FramingChange(target, true)
        }
        emptyFrames = 0; searchStep = 0
        val all = TextRegion(valid.minOf { it.left }, valid.minOf { it.top }, valid.maxOf { it.right }, valid.maxOf { it.bottom })
        val old = previous
        confirmations = if (old != null && maxOf(abs(old.left - all.left), abs(old.right - all.right), abs(old.top - all.top), abs(old.bottom - all.bottom)) < 0.055f) confirmations + 1 else 1
        previous = all
        if (now < nextChangeAt || confirmations < 2) return null
        val floor = min(maxZoom, max(1f, minZoom))
        val atEdge = all.left < 0.035f || all.right > 0.965f || all.top < 0.025f || all.bottom > 0.975f
        val target = if (atEdge && ratio > floor + 0.05f) {
            max(floor, ratio * 0.86f)
        } else {
            if (ratio >= minOf(maxZoom, 3f)) return null
            val span = max(all.right - all.left, all.bottom - all.top)
            if (span >= 0.58f || atEdge) return null
            // Center zoom cannot pan: protect every detected region, including off-center options.
            val distance = maxOf(abs(all.left - .5f), abs(all.right - .5f), abs(all.top - .5f), abs(all.bottom - .5f))
            val factor = minOf(1.22f, .72f / span, .44f / max(distance, .01f))
            if (factor < 1.06f) return null
            minOf(ratio * factor, maxZoom, 3f)
        }
        if (abs(target - ratio) < 0.05f) return null
        nextChangeAt = now + 3000L
        previous = null; confirmations = 0
        return FramingChange(target, false)
    }

    companion object {
        /** Inverse of ImageProxy.imageInfo.rotationDegrees; front preview mirroring is irrelevant. */
        fun surfacePoint(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
    }
}
