package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class AntiShakeTest {
    private val question="1. Calculate the sum of 2 + 3 and select the correct answer. A. 4 B. 5"
    @Test fun oneDroppedFrameDoesNotEraseConfirmation() {
        val memory=SceneMemory().apply{antiShake=true}
        assertFalse(memory.observe(question,1000));assertFalse(memory.observe("",1800))
        assertFalse(memory.observe(question,2600));assertTrue(memory.observe(question,3400))
        memory.remember(question)
        repeat(100){assertFalse(memory.observe(question,3500L+it*850))}
    }
    @Test fun disabledModeResetsOnEveryDroppedFrame() {
        val memory=SceneMemory()
        memory.observe(question,1000);memory.observe("",1800)
        assertFalse(memory.observe(question,2600));assertFalse(memory.observe(question,3400))
    }
    @Test fun onePartialTextDropoutCanBeSkippedButDoesNotCountAsConfirmation() {
        val memory=SceneMemory().apply{antiShake=true}
        assertFalse(memory.observe(question,1000))
        assertFalse(memory.observe("Calculate the sum of 2",1800))
        assertFalse(memory.observe(question,2600));assertTrue(memory.observe(question,3400))
    }
    @Test fun prolongedBlankOrLongGapCannotReuseAnOldCandidate() {
        val memory=SceneMemory().apply{antiShake=true}
        memory.observe(question,1000);memory.observe("",1800);memory.observe("",2600)
        assertFalse(memory.observe(question,3400));assertFalse(memory.observe(question,4200))
        assertFalse(memory.observe(question,8000))
    }
    @Test fun actualNewQuestionMustBuildItsOwnConfirmation() {
        val memory=SceneMemory().apply{antiShake=true}
        memory.observe(question,1000);memory.observe(question,1800)
        val next=question.replace("2 + 3","2 + 9")
        assertFalse(memory.observe(next,2600));assertFalse(memory.observe(next,3400));assertTrue(memory.observe(next,4200))
        assertFalse(memory.sameView(question,next))
        assertFalse(memory.sameView(question,question.replace("1.","8.")))
        assertFalse(memory.sameView(question,"A completely unrelated screen with different content"))
    }
    @Test fun clearFirstPhotoDoesNotWaitForSelectionWindow() {
        val policy=BestShotPolicy(1000)
        assertTrue(policy.consider(25.0));assertFalse(policy.needsAnother(1100))
    }
    @Test fun selectionRetainsBestAndBoundsCountAndTime() {
        val policy=BestShotPolicy(1000)
        assertTrue(policy.consider(2.0));assertTrue(policy.needsAnother(1100))
        assertTrue(policy.consider(10.0));assertTrue(policy.needsAnother(1200))
        assertFalse(policy.consider(4.0));assertEquals(10.0,policy.bestScore,0.0)
        assertFalse(policy.needsAnother(1300))
        val expired=BestShotPolicy(1000);expired.consider(1.0);assertFalse(expired.needsAnother(1900))
    }
    @Test fun invalidScoresCannotDisplaceWinnerOrCauseUnboundedRetry() {
        val policy=BestShotPolicy(1000)
        assertFalse(policy.consider(Double.NaN));assertTrue(policy.consider(8.0))
        assertFalse(policy.consider(Double.POSITIVE_INFINITY));assertFalse(policy.needsAnother(1100))
        assertEquals(8.0,policy.bestScore,0.0)
    }
}
