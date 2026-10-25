package com.autobot.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

@RunWith(AndroidJUnit4::class)
class SpeedAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun photo():File {
        val bitmap=Bitmap.createBitmap(1000,1400,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint().apply {color=Color.BLACK;textSize=50f}
        canvas.drawText("1. 2+3=?",240f,450f,paint)
        paint.color=Color.BLUE;canvas.drawCircle(650f,800f,70f,paint)
        val file=File.createTempFile("speed_test_",".jpg",context.cacheDir)
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle();return file
    }
    private fun login()=AccountApi.request("http://127.0.0.1:8099","/v1/auth/login","POST",JSONObject().put("username","qaowner").put("password","TestAdminPass123!"))

    @Test fun nativeStreamPassesGatewayBeforeAllQuestionsFinish() {
        val token=login().getString("access_token");val file=photo();val arrived=mutableListOf<Long>();val start=SystemClock.elapsedRealtime()
        try {
            val batch=SolveApi.solveBatch("http://127.0.0.1:8099",token,file,fast=true,streaming=true,onQuestion={_,_->arrived.add(SystemClock.elapsedRealtime()-start)})
            assertEquals(2,batch.questions.size);assertEquals(2,arrived.size)
            assertTrue("gateway must not buffer the first answer",arrived[1]-arrived[0]>=700)
            assertTrue(batch.questions.all {it.fast})
            val detail=SolveApi.explain("http://127.0.0.1:8099",token,file,batch.questions[0].full)
            assertFalse(detail.fast);assertTrue(detail.solution.isNotBlank())
        }finally{file.delete();AccountApi.request("http://127.0.0.1:8099","/v1/auth/logout","POST",token=token)}
    }
    @Test fun interruptedNativeStreamPreservesOnlyCompletedQuestions() {
        val token=login().getString("access_token");val file=photo()
        fun model(value:String) {AccountApi.request("http://127.0.0.1:8099","/v1/me/model","PUT",JSONObject().put("base_url","https://fixture.example/v1").put("model",value),token)}
        try {
            model("qa-break")
            try {SolveApi.solveBatch("http://127.0.0.1:8099",token,file,fast=true,streaming=true);fail("expected partial result")}
            catch(partial:PartialSolveException){assertEquals(1,partial.partial.questions.size);assertTrue(partial.partial.interrupted)}
        }finally{model("qa-stream");file.delete();AccountApi.request("http://127.0.0.1:8099","/v1/auth/logout","POST",token=token)}
    }
    @Test fun compressionPreservesSourceAndNonTextDiagram() {
        val source=photo();val original=source.readBytes()
        val result=PhotoUpload.optimize(source,context.cacheDir,true)
        try {
            assertArrayEquals(original,source.readBytes())
            val bitmap=BitmapFactory.decodeFile(result.path)
            assertTrue(bitmap.width<1000);assertTrue(bitmap.height<1400)
            var blue=false
            for(y in 0 until bitmap.height step 5)for(x in 0 until bitmap.width step 5){val pixel=bitmap.getPixel(x,y);if(Color.blue(pixel)>170&&Color.red(pixel)<100)blue=true}
            assertTrue("diagram must not be cropped out",blue);bitmap.recycle()
        }finally{source.delete();result.delete()}
    }
    @Test fun answerSpeechToggleWorksWithAndroidRegex() {
        assertEquals("答案是：D",AnswerSpeech.text("**D. 选项内容**","",false))
        assertEquals("答案已生成，请查看屏幕",AnswerSpeech.text("42","",false))
        assertEquals("答案是：D，选项内容",AnswerSpeech.text("D","D. 选项内容",true))
    }
    @Test fun cancellingAStalledStreamOnMainThreadReleasesNewQuestionImmediately() {
        val token=login().getString("access_token");val file=photo();val request=SolveRequest();val received=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        fun model(value:String){AccountApi.request("http://127.0.0.1:8099","/v1/me/model","PUT",JSONObject().put("base_url","https://fixture.example/v1").put("model",value),token)}
        try {
            model("qa-hang")
            val future=executor.submit<SolveBatch>{SolveApi.solveBatch("http://127.0.0.1:8099",token,file,fast=true,streaming=true,onQuestion={_,_->received.countDown()},request=request)}
            assertTrue(received.await(5,TimeUnit.SECONDS))
            val start=SystemClock.elapsedRealtime()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {request.cancel()}
            assertTrue("cancel must not block the UI",SystemClock.elapsedRealtime()-start<500)
            try{future.get(2,TimeUnit.SECONDS);fail("cancelled request must not produce a final answer")}catch(expected:ExecutionException){assertTrue(expected.cause is java.io.IOException);assertFalse(expected.cause is PartialSolveException)}
            model("qa-stream")
            val new=executor.submit<SolveBatch>{SolveApi.solveBatch("http://127.0.0.1:8099",token,file,fast=true,streaming=true)}.get(5,TimeUnit.SECONDS)
            assertEquals(2,new.questions.size)
        }finally{request.cancel();model("qa-stream");executor.shutdownNow();file.delete();AccountApi.request("http://127.0.0.1:8099","/v1/auth/logout","POST",token=token)}
    }
    @Test fun cancellingNonstreamRequestDoesNotWaitForItsReadTimeout() {
        val token=login().getString("access_token");val file=photo();val request=SolveRequest();val executor=Executors.newSingleThreadExecutor()
        fun model(value:String){AccountApi.request("http://127.0.0.1:8099","/v1/me/model","PUT",JSONObject().put("base_url","https://fixture.example/v1").put("model",value),token)}
        try {
            model("qa-hang")
            val future=executor.submit<SolveBatch>{SolveApi.solveBatch("http://127.0.0.1:8099",token,file,fast=true,streaming=false,request=request)}
            for(i in 0..30){val usage=AccountApi.request("http://127.0.0.1:8099","/v1/me/usage",token=token);if(usage.getJSONObject("summary").optInt("processing")>0)break;Thread.sleep(50)}
            val start=SystemClock.elapsedRealtime();request.cancel()
            try{future.get(2,TimeUnit.SECONDS);fail("expected cancellation")}catch(expected:ExecutionException){assertTrue(expected.cause is java.io.IOException)}
            assertTrue(SystemClock.elapsedRealtime()-start<1500)
            model("qa-stream");assertEquals(1,SolveApi.solveBatch("http://127.0.0.1:8099",token,file).questions.size)
        }finally{request.cancel();model("qa-stream");executor.shutdownNow();file.delete();AccountApi.request("http://127.0.0.1:8099","/v1/auth/logout","POST",token=token)}
    }
    @Test fun repeatedOcrDoesNotQueueAnotherAnswerAndNewSceneNeedsOnlyLocalConfirmation() {
        val memory=SceneMemory();val old="1. 计算 2 + 3 的结果并选择正确答案。A. 4 B. 5";val fresh="2. 计算 7 - 4 的结果并选择正确答案。A. 3 B. 6"
        memory.remember(old);repeat(100){assertFalse(memory.observe(old.replace(" ","  ")))}
        assertFalse(memory.observe(fresh));assertTrue(memory.observe(fresh));memory.remember(fresh)
        repeat(100){assertFalse(memory.observe(fresh))}
    }
}
