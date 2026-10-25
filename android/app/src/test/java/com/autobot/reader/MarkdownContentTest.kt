package com.autobot.reader

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
    private fun parse(value: String) = Parser.builder().build().parse(value)
    private fun nodes(document: Node): List<Node> {
        val result = mutableListOf<Node>()
        fun visit(node: Node) { result.add(node); var child = node.firstChild; while (child != null) { visit(child); child = child.next } }
        visit(document); return result
    }

    @Test fun sourceImageResolvesOnlyToCallerSuppliedPhoto() {
        val doc = parse("**条件**\n\n![三角形](question-source)")
        MarkdownContent.prepare(doc, "file:///private/photos/own.jpg")
        assertEquals("file:///private/photos/own.jpg", nodes(doc).filterIsInstance<Image>().single().destination)
        assertTrue(nodes(doc).any { it is StrongEmphasis })
    }

    @Test fun optionLineBreaksArePreserved() {
        val doc = parse("A. 2\nB. 3\nC. 4")
        MarkdownContent.prepare(doc, null)
        assertEquals(2, nodes(doc).count { it is HardLineBreak })
        assertTrue(nodes(doc).none { it is SoftLineBreak })
    }

    @Test fun generatedImagesCannotReadFilesOrContactServers() {
        val doc = parse("![x](https://tracking.example/x) ![x](file:///private/settings.xml) ![x](data:image/png;base64,123)")
        MarkdownContent.prepare(doc, "file:///private/photos/own.jpg")
        assertTrue(nodes(doc).none { it is Image })
        assertEquals(3, nodes(doc).filterIsInstance<Text>().count { it.literal.contains("图片不可用") })
    }

    @Test fun historyWithoutSavedPhotoShowsExplicitPlaceholder() {
        val doc = parse("![题图](question-source)")
        MarkdownContent.prepare(doc, null)
        assertTrue(nodes(doc).none { it is Image })
        assertTrue(nodes(doc).filterIsInstance<Text>().any { it.literal.contains("题图未保存") })
    }

    @Test fun dangerousLinksLoseTheirDestinationButKeepVisibleText() {
        val doc = parse("[请看](javascript:alert) [资料](https://example.com)")
        MarkdownContent.prepare(doc, null)
        assertEquals(listOf("https://example.com"), nodes(doc).filterIsInstance<Link>().map { it.destination })
        assertTrue(nodes(doc).filterIsInstance<Text>().any { it.literal == "请看" })
    }

    @Test fun speechReadsContentAndOmitsImagesAndMarkup() {
        val text = MarkdownContent.speech("## 解题\n\n1. **长度**为 `3 cm`\n2. [答案](https://example.com)为 **6**\n\n![图](question-source)")
        assertTrue(text.contains("长度为 3 cm"))
        assertTrue(text.contains("答案为 6"))
        assertFalse(text.contains("question-source"))
        assertFalse(text.contains("https:"))
        assertFalse(text.contains("**"))
    }

    @Test fun mathDelimitersNormalizeWithoutChangingCodeOrCurrency() {
        assertEquals("长度 \$\$BC=6\$\$ cm", MarkdownContent.normalizeMath("长度 \$BC=6\$ cm"))
        assertEquals("\$\$x^2\$\$", MarkdownContent.normalizeMath("\\(x^2\\)"))
        assertEquals("\$\$x+1\$\$", MarkdownContent.normalizeMath("\\[x+1\\]"))
        assertEquals("\$\$x+1\$\$", MarkdownContent.normalizeMath("\$\$x+1\$\$"))
        assertEquals("\\(\\)", MarkdownContent.normalizeMath("\\(\\)"))
        val literal = "`\$x\$`\n```\n\$y\$\n```\n价格 \$5 and \$10"
        assertEquals(literal, MarkdownContent.normalizeMath(literal))
    }

    @Test fun speechDoesNotReadLatexControlWords() {
        val plain = MarkdownContent.speech("面积 \$\$ \\frac{1}{2} \\times 6 \\times 4 \$\$")
        assertTrue(plain.contains("1 除以 2"))
        assertFalse(plain.contains("frac"))
        assertFalse(plain.contains("times"))
        assertFalse(plain.contains("\$"))
    }
}
