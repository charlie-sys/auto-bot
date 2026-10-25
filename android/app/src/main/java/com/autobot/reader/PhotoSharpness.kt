package com.autobot.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs

internal object PhotoSharpness {
    fun score(file:File):Double {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        val options=BitmapFactory.Options()
        while(maxOf(bounds.outWidth,bounds.outHeight)/options.inSampleSize.coerceAtLeast(1)>1024)
            options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
        val bitmap=BitmapFactory.decodeFile(file.absolutePath,options)?:return 0.0
        return try{score(bitmap)}finally{bitmap.recycle()}
    }
    fun score(bitmap:Bitmap):Double {
        val w=bitmap.width;val h=bitmap.height
        if(w<8||h<8)return 0.0
        val pixels=IntArray(w*h);bitmap.getPixels(pixels,0,w,0,0,w,h)
        fun gray(index:Int):Int {val c=pixels[index];return (((c shr 16) and 255)*77+((c shr 8) and 255)*150+(c and 255)*29) shr 8}
        val tiles=DoubleArray(36);val counts=IntArray(36)
        for(y in 2 until h-2 step 2)for(x in 2 until w-2 step 2){
            val i=y*w+x;val v=gray(i)
            // Second derivative falls as edges blur; score the strongest tiles
            // so white document margins do not dominate the measurement.
            val edge=abs(4*v-gray(i-1)-gray(i+1)-gray(i-w)-gray(i+w)).toDouble()
            val t=(y*6/h).coerceAtMost(5)*6+(x*6/w).coerceAtMost(5)
            tiles[t]+=edge;counts[t]++
        }
        return tiles.indices.map{tiles[it]/counts[it].coerceAtLeast(1)}.sortedDescending().take(8).average()
    }
}
