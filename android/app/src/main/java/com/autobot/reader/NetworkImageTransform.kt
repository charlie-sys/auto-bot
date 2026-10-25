package com.autobot.reader

import android.graphics.Bitmap
import android.graphics.Matrix

internal object NetworkImageTransform {
    /** Always returns an owned bitmap, so the decoder frame can be released. */
    fun apply(source:Bitmap,rotation:Int,zoom:Float,left:Int,top:Int,width:Int,height:Int):Bitmap {
        val angle=((rotation%360)+360)%360
        require(angle%90==0)
        require(left in 0..95&&top in 0..95&&width in 5..100&&height in 5..100&&left+width<=100&&top+height<=100)
        val rotated=if(angle==0)source else Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply{postRotate(angle.toFloat())},true)
        try{
            val ratio=zoom.coerceIn(1f,4f)
            val fullW=(rotated.width*width/100).coerceAtLeast(1)
            val fullH=(rotated.height*height/100).coerceAtLeast(1)
            val cropW=(fullW/ratio).toInt().coerceAtLeast(1);val cropH=(fullH/ratio).toInt().coerceAtLeast(1)
            val x=(rotated.width*left/100+(fullW-cropW)/2).coerceIn(0,rotated.width-cropW)
            val y=(rotated.height*top/100+(fullH-cropH)/2).coerceIn(0,rotated.height-cropH)
            val crop=Bitmap.createBitmap(rotated,x,y,cropW,cropH)
            return if(crop===rotated)crop.copy(Bitmap.Config.ARGB_8888,false) else crop
        }finally{if(rotated!==source)rotated.recycle()}
    }
}
