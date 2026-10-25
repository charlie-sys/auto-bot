package com.autobot.reader

import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Model text is untrusted: images can only refer to this question's local photograph. */
object MarkdownContent {
    const val SOURCE = "question-source"

    /** Markwon's inline math uses double dollars; keep literal code untouched. */
    fun normalizeMath(markdown: String): String {
        val protected = Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|`[^`\\n]*`")
        fun normalize(value: String): String {
            val delimiters = Regex("\\\\\\(([\\s\\S]*?)\\\\\\)|\\\\\\[([\\s\\S]*?)\\\\\\]|(?<![\\\\$])\\$(?![\\s$])([^$\\n]+?)(?<!\\s)\\$(?![0-9$])")
            return delimiters.replace(value) { match ->
                val formula = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                if (formula == null) match.value else "\$\$$formula\$\$"
            }
        }
        val result = StringBuilder()
        var end = 0
        for (match in protected.findAll(markdown)) {
            result.append(normalize(markdown.substring(end, match.range.first)))
            result.append(match.value); end = match.range.last + 1
        }
        return result.append(normalize(markdown.substring(end))).toString()
    }

    fun prepare(document: Node, sourceUrl: String?) {
        fun visit(parent: Node) {
            var node = parent.firstChild
            while (node != null) {
                val next = node.next
                when (node) {
                    is SoftLineBreak -> {
                        // Preserve option and formula line breaks from OCR and old history.
                        node.insertBefore(HardLineBreak()); node.unlink()
                    }
                    is Image -> {
                        if (node.destination == SOURCE && sourceUrl != null) node.destination = sourceUrl
                        else {
                            node.insertBefore(Text(if (node.destination == SOURCE) "【题图未保存，请开启“同时保留题目照片”后重新识题】" else "【图片不可用，请查看原始题图】"))
                            node.unlink()
                        }
                    }
                    is Link -> {
                        visit(node)
                        if (!node.destination.startsWith("https://", true) && !node.destination.startsWith("http://", true)) {
                            while (node.firstChild != null) node.insertBefore(node.firstChild)
                            node.unlink()
                        }
                    }
                    else -> visit(node)
                }
                node = next
            }
        }
        visit(document)
    }

    fun speech(markdown: String): String {
        val result = StringBuilder()
        fun visit(node: Node) {
            when (node) {
                is Image, is HtmlInline, is HtmlBlock -> return
                is Text -> result.append(node.literal)
                is Code -> result.append(node.literal)
                is FencedCodeBlock -> result.append(node.literal)
                is IndentedCodeBlock -> result.append(node.literal)
                is SoftLineBreak, is HardLineBreak -> result.append('\n')
                else -> {
                    var child = node.firstChild
                    while (child != null) { visit(child); child = child.next }
                    if (node is Block) result.append('\n')
                }
            }
        }
        visit(Parser.builder().build().parse(markdown))
        var plain = result.toString()
        plain = plain.replace(Regex("\\\\(?:text|mathrm|mathbf)\\{([^{}]*)\\}"), "$1")
            .replace(Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}"), "$1 除以 $2")
            .replace(Regex("\\\\sqrt\\{([^{}]*)\\}"), "$1 的平方根")
        for ((command, word) in mapOf("times" to "乘", "cdot" to "乘", "div" to "除以", "leq" to "小于等于", "geq" to "大于等于", "neq" to "不等于", "pi" to "派")) {
            plain = plain.replace(Regex("\\\\$command(?![A-Za-z])"), word)
        }
        return plain.replace(Regex("\\\\[A-Za-z]+"), " ").replace(Regex("[$\\\\{}]"), "").trim().replace(Regex("\n{3,}"), "\n\n")
    }
}
