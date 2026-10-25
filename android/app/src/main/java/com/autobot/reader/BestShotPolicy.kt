package com.autobot.reader

/** Bounds selection work; an already clear photo takes the fast path. */
internal class BestShotPolicy(private val startedAt:Long) {
    var attempts=0; private set
    var bestScore=Double.NEGATIVE_INFINITY; private set
    fun consider(score:Double):Boolean {
        attempts++
        if(!score.isFinite() || score<=bestScore)return false
        bestScore=score;return true
    }
    fun needsAnother(now:Long)=attempts<3 && now-startedAt<900 && bestScore<18.0
}
