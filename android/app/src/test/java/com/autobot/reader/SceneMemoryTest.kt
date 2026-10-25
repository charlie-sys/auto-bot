package com.autobot.reader
import org.junit.Assert.*
import org.junit.Test

class SceneMemoryTest {
    private val first="1. Calculate the sum of 2 + 3 and select the correct answer.\nA. 4\nB. 5"
    private val second="2. Calculate the difference of 7 - 4 and select the correct answer.\nA. 3\nB. 5"
    @Test fun stableSameQuestionNeverAutoResubmitsEvenAfterBlankOrPunctuationChanges() {
        val memory=SceneMemory();assertFalse(memory.observe(first));assertFalse(memory.observe(first));assertTrue(memory.observe(first));memory.remember(first)
        repeat(100){assertFalse(memory.observe(first.replace(" ","  ").replace(".","．")))}
        memory.observe("");memory.resetCandidate();repeat(10){assertFalse(memory.observe(first))}
    }
    @Test fun confirmedNewQuestionDoesNotWaitForAnOldRequestOrCooldown() {
        val memory=SceneMemory();memory.remember(first)
        assertFalse(memory.observe(second));assertTrue(memory.observe(second));memory.remember(second)
        assertFalse(memory.observe(first));assertFalse(memory.observe(second))
    }
    @Test fun numericalOrOperatorChangesAreNewEvenWithSameWordingAndNumber() {
        val memory=SceneMemory();memory.remember(first)
        val changed=first.replace("2 + 3","2 + 8")
        assertFalse(memory.observe(changed));assertTrue(memory.observe(changed))
        memory.resetCandidate();val operation=first.replace("2 + 3","2 - 3")
        assertFalse(memory.observe(operation));assertTrue(memory.observe(operation))
    }
    @Test fun OCRNoiseAndPartialViewsAreSuppressedButAdditionalQuestionIsNot() {
        val memory=SceneMemory();memory.remember(first)
        assertTrue(memory.known(first.replace("correct","correet")))
        assertTrue(memory.known(first.substringBefore("\nA.")))
        val mixed=first+"\n"+second
        assertFalse(memory.observe(mixed));assertTrue(memory.observe(mixed))
    }
    @Test fun RememberingAnOldResultDoesNotResetConfirmationOfTheNewScene() {
        val memory=SceneMemory();memory.remember(first);assertFalse(memory.observe(second))
        memory.remember(first);assertTrue(memory.observe(second))
    }
    @Test fun memoryCanBeClearedForAccountSwitchAndCannotGrowWithoutBound() {
        val memory=SceneMemory();memory.remember(first);memory.clear();assertFalse(memory.known(first))
        for(i in 0..40)memory.remember("Calculate the sum of $i + 100 and show its answer")
        assertFalse(memory.known("Calculate the sum of 0 + 100 and show its answer"))
    }
    @Test fun firstDensePageCanConfirmDespiteOneNoisyDigitWithoutWeakeningDedup() {
        val page=(1..4).joinToString("\n") { n -> "$n. Please read the complete question carefully and calculate the correct answer for the following numerical conditions: ${n*10} + ${n*11}. A. ${n*12} B. ${n*13} C. ${n*14}" }
        val noisy=page.replace("44", "49")
        val memory=SceneMemory()
        assertFalse(memory.observe(page));assertFalse(memory.observe(noisy));assertTrue(memory.observe(page))
        memory.remember(page)
        assertFalse(memory.known(noisy)) // Real changed values remain eligible as a new question.
        assertFalse(memory.observe(noisy));assertFalse(memory.observe(page))
        assertFalse(memory.observe(noisy));assertTrue(memory.observe(noisy))
    }
    @Test fun shortQuestionAlternatingDigitsCannotConfirm() {
        val memory=SceneMemory()
        repeat(10) { assertFalse(memory.observe(if(it%2==0)first else first.replace("2 + 3","2 + 8"))) }
    }
    @Test fun newQuestionNumberIsNotHiddenByIdenticalBody() {
        val memory=SceneMemory();memory.remember(first)
        val next=first.replace("1. Calculate","8. Calculate")
        assertFalse(memory.known(next));assertFalse(memory.observe(next));assertTrue(memory.observe(next))
    }
    @Test fun reversedQuestionConditionIsNotSuppressedBySharedWording() {
        val memory=SceneMemory()
        val question="下列关于物体运动和力的关系的描述中，选择正确的选项并说明理由。A. 力可以改变物体的运动状态 B. 速度越大惯性越大"
        memory.remember(question)
        val next=question.replace("正确", "不正确")
        assertFalse(memory.known(next));assertFalse(memory.observe(next));assertTrue(memory.observe(next))
    }
    @Test fun addedNumericalConditionDoesNotCountAsDuplicate() {
        val memory=SceneMemory();memory.remember(first)
        val next=first.replace("2 + 3", "2 + 3 + 9")
        assertFalse(memory.known(next));assertFalse(memory.observe(next));assertTrue(memory.observe(next))
    }
    @Test fun sharedInstructionTemplateDoesNotHideDifferentQuestion() {
        val prefix="Read all the following statements carefully, select the correct answer and explain your choice. "
        val memory=SceneMemory();memory.remember(prefix+"The river flows east through the green valley towards the sea.")
        val next=prefix+"The planet orbits its star while rotating around its own axis."
        assertFalse(memory.known(next));assertFalse(memory.observe(next));assertTrue(memory.observe(next))
    }
    @Test fun clearlyDifferentDenseNewPageToleratesOcrDigits() {
        val memory=SceneMemory();memory.remember(first)
        val page=(1..5).joinToString("\n") { n -> "$n. 探究植物光合作用与呼吸作用在不同环境条件下的变化。记录实验中温度、光照强度、气体释放量和叶片颜色，并根据以下观察结果选择正确结论。温度 ${n*10} 光照 ${n*11} 气体 ${n*12}。" }
        val noisy=page.replace("44", "49")
        assertFalse(memory.observe(page));assertTrue(memory.observe(noisy))
    }
    @Test fun noisyDigitsInKnownDensePageCannotUseNewPageFallback() {
        val page=(1..5).joinToString("\n") { n -> "$n. 探究植物光合作用与呼吸作用在不同环境条件下的变化。记录实验中温度、光照强度、气体释放量和叶片颜色，并根据以下观察结果选择正确结论。温度 ${n*10} 光照 ${n*11} 气体 ${n*12}。" }
        val memory=SceneMemory();memory.remember(page)
        repeat(10) { assertFalse(memory.observe(if(it%2==0)page.replace("44","49") else page.replace("44","48"))) }
    }
}
