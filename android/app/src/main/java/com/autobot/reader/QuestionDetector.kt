package com.autobot.reader

import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max

data class FrameQuality(val acceptable: Boolean, val reason: String, val hash: Long)

/** Local quality gate; repeated OCR text confirms stability before capture. */
class QuestionDetector {
    private val scenes=SceneMemory()
    private var previousHash: Long? = null
    private var stableFrames = 0
    @Volatile var sensitivity = 1
    @Volatile var antiShake = true
    private var lastRegion: FloatArray? = null

    @Synchronized
    fun resetSession() {
        scenes.resetCandidate()
        previousHash = null
        stableFrames = 0
        lastRegion=null
    }

    @Synchronized
    fun inspect(proxy: ImageProxy): FrameQuality {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val width = proxy.width
        val height = proxy.height
        val stride = plane.rowStride
        val pixel = plane.pixelStride
        fun y(x: Int, yy: Int): Int = buffer.get(yy * stride + x * pixel).toInt() and 255
        val radius = max(2, minOf(width, height) / 160)
        fun smooth(x: Int, yy: Int): Int {
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) {
                sum += y((x + dx * radius).coerceIn(0, width - 1), (yy + dy * radius).coerceIn(0, height - 1))
            }
            return sum / 9
        }
        val left = width / 20
        val right = width * 19 / 20
        val top = height / 20
        val bottom = height * 19 / 20
        var total = 0L
        var grad = 0L
        var count = 0
        var glare = 0
        val step = max(2, minOf(width, height) / 110)
        for (yy in top until bottom - step step step) {
            for (xx in left until right - step step step) {
                val v = y(xx, yy)
                total += v
                grad += abs(v - y(xx + step, yy)) + abs(v - y(xx, yy + step))
                if (v > 248) glare++
                count++
            }
        }
        if (count == 0) return FrameQuality(false, "等待画面", 0)
        val mean = total / count
        val sharpness = grad / (count * 2)
        var hash = 0L
        for (row in 0..7) for (col in 0..7) {
            val x1 = left + (right - left) * col / 9
            val x2 = left + (right - left) * (col + 1) / 9
            val yy = top + (bottom - top) * row / 8
            hash = (hash shl 1) or if (smooth(x1, yy) > smooth(x2, yy)) 1L else 0L
        }
        val reason = when {
            mean < 42 -> "画面过暗，请补光"
            glare > count * 0.65 -> "反光较强，请调整角度"
            sharpness < 1 -> "画面不清晰，请对焦"
            else -> ""
        }
        if (reason.isNotEmpty()) {
            stableFrames = 0
            return FrameQuality(false, reason, hash)
        }
        // Screen moire and camera auto exposure can change a perceptual hash while
        // the printed question is stationary. Confirm stability with repeated OCR
        // content instead of blocking OCR on the hash distance.
        previousHash = hash
        return FrameQuality(true, "正在确认题目", hash)
    }

    fun normalize(value: String): String = value.lowercase().replace(Regex("[\\s\\p{Punct}，。；：！？、（）【】]+"), "")

    /** Returns true after repeated OCR confirmation and duplicate suppression. */
    @Synchronized
    fun observe(text: String, hash: Long, region:FloatArray?=null): Boolean {
        scenes.sensitivity=sensitivity
        scenes.antiShake=antiShake
        if(antiShake && region!=null) {
            val previous=lastRegion
            if(previous!=null && region.indices.maxOf {abs(region[it]-previous[it])}>.20f)scenes.resetCandidate()
            lastRegion=region.copyOf()
        }
        return scenes.observe(text)
    }

    @Synchronized fun missedFrame(){scenes.antiShake=antiShake;scenes.observe("")}
    @Synchronized fun holdingCandidate()=antiShake&&scenes.holdingCandidate()
    @Synchronized fun sameView(first:String,second:String)=scenes.sameView(first,second)

    @Synchronized fun clearMemory(){scenes.clear();resetSession()}
    @Synchronized fun known(text:String)=scenes.known(text)

    @Synchronized
    fun markSubmitted(text: String, hash: Long, modelText: String = "") {
        scenes.remember(text)
        if(modelText.isNotBlank())scenes.remember(modelText)
    }

    @Synchronized
    fun markQueued(text: String, hash: Long) {
        scenes.remember(text)
    }

    @Synchronized
    fun clearQueued(text: String) {
        // Keep attempted scenes latched even on errors/cancel; explicit manual capture can retry.
    }
}
