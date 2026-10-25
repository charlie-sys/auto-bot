package com.autobot.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object QuestionImages {
    /** Do not let generated Markdown or old history read arbitrary app files. */
    fun resolve(context: Context, path: String): File? = runCatching {
        if (path.isBlank()) return null
        val file = File(path).canonicalFile
        val account = AccountStore(context)
        if (account.id() == 0) return null
        val roots = listOf(account.photoFolder(), account.previewFolder())
        file.takeIf { it.isFile && roots.any { root -> file.parentFile == root.canonicalFile } }
    }.getOrNull()

    fun decode(file: File, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
