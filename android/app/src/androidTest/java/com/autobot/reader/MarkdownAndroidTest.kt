package com.autobot.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Android's ICU regex engine; desktop JVM regex tests cannot cover it. */
@RunWith(AndroidJUnit4::class)
class MarkdownAndroidTest {
    @Test fun plainAnswerCanStartAutomaticSpeech() {
        assertEquals("答案是 12", MarkdownContent.speech("答案是 **12**"))
    }

    @Test fun latexSpeechPatternsCompileAndConvertOnAndroid() {
        val result = MarkdownContent.speech("\$\$ \\text{Area} = \\frac{1}{2} \\times 6 \\times 4 \$\$")
        assertTrue(result.contains("Area = 1 除以 2"))
        assertFalse(result.contains("frac"))
        assertFalse(result.contains("text{"))
        assertEquals("9 的平方根", MarkdownContent.speech("\\sqrt{9}"))
        assertEquals("x", MarkdownContent.speech("\\mathbf{x}"))
        assertEquals("x", MarkdownContent.speech("\\mathrm{x}"))
    }

    @Test fun imageUrlsAreOmittedAndMathDelimitersWorkOnAndroid() {
        assertEquals("题目", MarkdownContent.speech("**题目**\n\n![图](question-source)"))
        assertEquals("\$\$x+1\$\$", MarkdownContent.normalizeMath("\\(x+1\\)"))
        assertEquals("\$\$x+1\$\$", MarkdownContent.normalizeMath("\\[x+1\\]"))
        assertEquals("\$\$x+1\$\$", MarkdownContent.normalizeMath("\$x+1\$"))
    }
}
