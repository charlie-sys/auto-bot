package com.autobot.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AntiShakeAndroidTest {
    private fun page():Bitmap {
        val bitmap=Bitmap.createBitmap(1000,1200,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=44f}
        listOf("1. Calculate the sum of 2 + 3.","Select the correct answer.","A. 4","B. 5").forEachIndexed{i,line->canvas.drawText(line,80f,200f+i*150,paint)}
        return bitmap
    }
    @Test fun sharpTextScoresAboveBlurredTextAndBlankPage() {
        val sharp=page()
        val small=Bitmap.createScaledBitmap(sharp,35,42,true)
        val blur=Bitmap.createScaledBitmap(small,1000,1200,true)
        val blank=Bitmap.createBitmap(1000,1200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)}
        try{
            val score=PhotoSharpness.score(sharp)
            assertTrue("sharp=$score blurred=${PhotoSharpness.score(blur)}",score>PhotoSharpness.score(blur)*2)
            assertEquals(0.0,PhotoSharpness.score(blank),0.0)
        }finally{sharp.recycle();small.recycle();blur.recycle();blank.recycle()}
    }
    @Test fun candidateJpegCanBeScoredAndLocallyValidatedBeforeUpload() {
        val bitmap=page()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("antishake_test_",".jpg",context.cacheDir)
        val reader=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try{
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}
            assertTrue(PhotoSharpness.score(file)>0)
            val found=Tasks.await(reader.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS).text
            val scene=SceneMemory()
            assertTrue(found,scene.sameView("1. Calculate the sum of 2 + 3.\nSelect the correct answer.\nA. 4\nB. 5",found))
            assertFalse(scene.sameView("8. Name the capital of France. A. Paris B. Rome",found))
        }finally{reader.close();bitmap.recycle();file.delete()}
    }
}
