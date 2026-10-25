package com.autobot.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.LruCache
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.util.concurrent.Executors

class QuestionMarkdown(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val renderers = mutableMapOf<Pair<Float, Int>, Markwon>()
    private fun renderer(size: Float, color: Int): Markwon = renderers.getOrPut(size to color) { Markwon.builder(context)
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(size) { builder ->
            builder.inlinesEnabled(true).executorService(formulaExecutor)
            builder.theme().textColor(color)
            builder.errorHandler { latex, _ -> FormulaFallbackDrawable(latex, size, color) }
        })
        .usePlugin(ImagesPlugin.create { plugin ->
            plugin.addSchemeHandler(object : SchemeHandler() {
                override fun supportedSchemes(): Collection<String> = listOf("file")
                override fun handle(raw: String, uri: Uri): ImageItem {
                    val file = QuestionImages.resolve(context, uri.path.orEmpty()) ?: error("题图已不存在")
                    val bitmap = cache.get(file.path) ?: (QuestionImages.decode(file, 1200) ?: error("题图无法读取")).also { cache.put(file.path, it) }
                    return ImageItem.withResult(BitmapDrawable(context.resources, bitmap))
                }
            })
        })
        .build() }

    fun set(view: TextView, markdown: String, photoPath: String = "") {
        val source = QuestionImages.resolve(context, photoPath)?.let { Uri.fromFile(it).toString() }
        val markwon = renderer(view.textSize, view.currentTextColor)
        val document = markwon.parse(MarkdownContent.normalizeMath(markdown))
        MarkdownContent.prepare(document, source)
        markwon.setParsedMarkdown(view, markwon.render(document))
        view.setTextIsSelectable(true)
    }

    companion object { private val formulaExecutor = Executors.newFixedThreadPool(2) }
}
