package com.autobot.reader

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Camera switching with an isolated on-device fake API: no real model calls/user data changes. */
class MouthCameraAndroidTest {
    @Test fun rearCaptureSuccessFailureAndStopReturnSafely(){
        val test=InstrumentationRegistry.getInstrumentation();val target=test.targetContext
        target.startActivity(Intent(target,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        test.waitForIdleSync();SystemClock.sleep(800)
        val socket=ServerSocket(0);val requests=AtomicInteger();val status=AtomicInteger(200)
        val server=Thread{
            while(!socket.isClosed)runCatching{socket.accept().use{client->
                client.soTimeout=15000
                val input=client.getInputStream().buffered()
                fun line():String{val bytes=java.io.ByteArrayOutputStream();while(true){val next=input.read();if(next<0)error("closed");if(next==10)break;bytes.write(next);check(bytes.size()<16384)};return bytes.toString("US-ASCII").trimEnd('\r')}
                val path=line();var length=0
                while(true){val header=line();if(header.isEmpty())break;if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()}
                var left=length;val buffer=ByteArray(8192);while(left>0){val read=input.read(buffer,0,minOf(left,buffer.size));check(read>0);left-=read}
                val isSolve=path.contains("/v1/solve-batch");if(isSolve)requests.incrementAndGet()
                val code=if(isSolve)status.get()else 200
                val body=if(code==200)"""{"questions":[{"question_brief":"Camera fixture","question_full":"2 + 3 = ?","answer_brief":"5","solution":"fixture only","uncertainty":""}],"uncertainty":""}""" else """{"detail":"fixture failure"}"""
                val bytes=body.toByteArray();client.getOutputStream().apply{write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
            }}
        }.apply{isDaemon=true;start()}
        val prefix="mouth_test_${UUID.randomUUID()}_";val names=mutableSetOf<String>()
        val root=File(target.cacheDir,prefix).apply{mkdirs()}
        val context=object:ContextWrapper(target){
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(prefix+name,mode).also{names.add(prefix+name)}
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
        }
        val prefs=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
        val base="http://127.0.0.1:${socket.localPort}"
        prefs.edit().putString("base_url",base).putBoolean("mouth_capture",true).putInt("mouth_hold_ms",1500).putInt("mouth_threshold",30)
            .putBoolean("background_enabled",true).putBoolean("muted",true).putBoolean("save_history",false).putBoolean("stream_answers",false).putBoolean("optimize_upload",false).commit()
        AccountStore(context).save(JSONObject().put("access_token","isolated-test-session").put("server_id","e".repeat(32)).put("user",JSONObject().put("id",777).put("username","fixture").put("role","user")),base)
        var engine:ReaderEngine?=null
        fun field(name:String)=ReaderEngine::class.java.getDeclaredField(name).apply{isAccessible=true}.get(engine)
        fun waitFor(message:String,condition:()->Boolean){val deadline=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<deadline){var ready=false;test.runOnMainSync{ready=condition()};if(ready)return;SystemClock.sleep(120)};fail("$message; state=${engine?.status}")}
        try {
            test.runOnMainSync{
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                runCatching{(MainActivity::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(activity) as ReaderEngine).stopScanning()}
                engine=ReaderEngine(context,activity).also{it.startScanning()}
            }
            waitFor("front camera startup"){engine!!.running&&field("mouthActive")==true&&field("cameraStarting")==false}
            fun trigger(){test.runOnMainSync{ReaderEngine::class.java.getDeclaredMethod("switchMouthToRear",String::class.java,java.lang.Long.TYPE).apply{isAccessible=true}.invoke(engine,"",0L)}}
            trigger();waitFor("one successful rear capture and front recovery"){requests.get()==1&&field("mouthBackPhase")==false&&!engine!!.busy&&!engine!!.capturing}
            test.runOnMainSync{assertEquals("5",(field("lastResult") as SolveBatch).questions.single().answer);assertTrue(engine!!.running)}
            status.set(502);trigger();waitFor("failed request must also return to front"){requests.get()==2&&field("mouthBackPhase")==false&&!engine!!.busy&&!engine!!.capturing}
            val sent=engine!!.requestsSent
            trigger();test.runOnMainSync{engine!!.stopScanning()};SystemClock.sleep(2800)
            assertEquals("stop must cancel pending camera-switch shot",sent,engine!!.requestsSent)
            assertEquals(2,requests.get())
        }finally{
            test.runOnMainSync{engine?.close()};socket.close();server.join(1000)
            names.forEach{target.deleteSharedPreferences(it)}
            root.walkBottomUp().forEach{it.delete()}
        }
    }
}
