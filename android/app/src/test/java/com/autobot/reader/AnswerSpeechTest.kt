package com.autobot.reader

import org.junit.Assert.*
import org.junit.Test

class AnswerSpeechTest {
    @Test fun detailOffOnlyReadsChoiceLabels() {
        assertEquals("答案是：D",AnswerSpeech.text("**D. 守恒定律**","",false))
        assertEquals("答案是：A、C",AnswerSpeech.text("A、C. 两个正确选项","",false))
    }
    @Test fun detailOffDoesNotReadNumericalOrFreeTextAnswer() {
        assertEquals("答案已生成，请查看屏幕",AnswerSpeech.text("42","",false))
        assertEquals("答案已生成，请查看屏幕",AnswerSpeech.text("能量守恒","",false))
    }
    @Test fun detailOnExpandsAChoiceOnlyWhenVisibleInQuestion() {
        assertEquals("答案是：D，能量守恒",AnswerSpeech.text("D","A. 速度\nD. 能量守恒",true))
        assertEquals("答案是：D",AnswerSpeech.text("D","题目没有转写选项",true))
        assertEquals("答案是：42",AnswerSpeech.text("42","",true))
    }
}
