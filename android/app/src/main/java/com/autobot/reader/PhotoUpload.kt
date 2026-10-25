package com.autobot.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

/** Crop only uniform white outside margins. All non-white content, including diagrams, is retained. */
internal object PhotoUpload {
    fun optimize(source:File,folder:File,fast:Boolean):File {
        var bitmap=BitmapFactory.decodeFile(source.path) ?: error("无法读取题图")
        val output=File.createTempFile("upload_",".jpg",folder)
        try {
            val w=bitmap.width;val h=bitmap.height
            fun white(pixel:Int)=Color.red(pixel)>250&&Color.green(pixel)>250&&Color.blue(pixel)>250
            val corners=listOf(bitmap.getPixel(0,0),bitmap.getPixel(w-1,0),bitmap.getPixel(0,h-1),bitmap.getPixel(w-1,h-1))
            if(corners.all(::white)) {
                var left=w;var top=h;var right=0;var bottom=0
                val pixels=IntArray(w)
                for(y in 0 until h){
                    bitmap.getPixels(pixels,0,w,0,y,w,1)
                    for(x in 0 until w)if(!white(pixels[x])){left=minOf(left,x);right=maxOf(right,x+1);top=minOf(top,y);bottom=maxOf(bottom,y+1)}
                }
                val margin=maxOf(48,(minOf(w,h)*.05f).toInt())
                left=(left-margin).coerceAtLeast(0);top=(top-margin).coerceAtLeast(0);right=(right+margin).coerceAtMost(w);bottom=(bottom+margin).coerceAtMost(h)
                if(right-left>w*.35f&&bottom-top>h*.2f&&(right-left).toLong()*(bottom-top)<w.toLong()*h*.85) {
                    val cropped=Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top)
                    if(cropped!==bitmap){bitmap.recycle();bitmap=cropped}
                }
            }
            val limit=if(fast)2400f else 3200f
            val scale=minOf(1f,limit/maxOf(bitmap.width,bitmap.height))
            if(scale<1){val resized=Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*scale).toInt()),maxOf(1,(bitmap.height*scale).toInt()),true);if(resized!==bitmap){bitmap.recycle();bitmap=resized}}
            for(quality in listOf(82,75,68)) {
                FileOutputStream(output).use {bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it)}
                if(output.length()<=1200*1024)break
            }
            check(output.length()<=8L*1024*1024){"上传图片过大"}
            return output
        }catch(e:Exception){output.delete();throw e}finally{bitmap.recycle()}
    }
}
