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

/** Isolated account/fake server: exercises voice answers, details, retry, cancellation and front camera. */
class VoiceEngineAndroidTest {
    @Test fun textFlowAndMouthReadinessDoNotUploadImagesOrChangeUserData(){
        val test=InstrumentationRegistry.getInstrumentation();val target=test.targetContext
        target.startActivity(Intent(target,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        test.waitForIdleSync();SystemClock.sleep(500)
        val socket=ServerSocket(0);val requests=AtomicInteger();val status=AtomicInteger(200)
        val payloads=java.util.concurrent.CopyOnWriteArrayList<String>()
        val server=Thread{
            while(!socket.isClosed)runCatching{socket.accept().use{client->
                client.soTimeout=10000;val input=client.getInputStream().buffered()
                fun line():String{val out=java.io.ByteArrayOutputStream();while(true){val b=input.read();check(b>=0);if(b==10)break;out.write(b);check(out.size()<16384)};return out.toString("US-ASCII").trimEnd('\r')}
                val path=line();var length=0
                while(true){val header=line();if(header.isEmpty())break;if(header.startsWith("Content-Length:",true))length=header.substringAfter(':').trim().toInt()}
                val bytes=ByteArray(length);var offset=0;while(offset<length){val n=input.read(bytes,offset,length-offset);check(n>0);offset+=n}
                val solve=path.contains("/v1/solve-text")
                if(solve){payloads.add(bytes.toString(Charsets.UTF_8));requests.incrementAndGet()}
                val code=if(solve)status.get()else 200
                if(code==202)SystemClock.sleep(1500)
                val body=if(code==502)"""{"detail":"fixture failure"}""" else """{"questions":[{"question_brief":"Voice fixture","question_full":"2 + 3 = ?","answer_brief":"5","solution":"2+3=5","uncertainty":""}],"uncertainty":""}"""
                val response=body.toByteArray();client.getOutputStream().apply{write("HTTP/1.1 ${if(code==502)502 else 200} Test\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray());write(response);flush()}
            }}
        }.apply{isDaemon=true;start()}
        val prefix="voice_test_${UUID.randomUUID()}_";val names=mutableSetOf<String>();val root=File(target.cacheDir,prefix).apply{mkdirs()}
        val context=object:ContextWrapper(target){
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences(prefix+name,mode).also{names.add(prefix+name)}
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
        }
        val base="http://127.0.0.1:${socket.localPort}";val prefs=context.getSharedPreferences("settings",0)
        prefs.edit().putString("base_url",base).putString("input_mode","voice").putString("voice_trigger","mouth").putBoolean("background_enabled",true).putBoolean("muted",true).putBoolean("save_history",false).commit()
        AccountStore(context).save(JSONObject().put("access_token","isolated-test-session").put("server_id","e".repeat(32)).put("user",JSONObject().put("id",777).put("username","fixture").put("role","user")),base)
        var engine:ReaderEngine?=null
        fun field(name:String)=ReaderEngine::class.java.getDeclaredField(name).apply{isAccessible=true}.get(engine)
        fun waitFor(message:String,condition:()->Boolean){val deadline=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<deadline){var ready=false;test.runOnMainSync{ready=condition()};if(ready)return;SystemClock.sleep(100)};fail("$message; ${engine?.status}")}
        try{
            test.runOnMainSync{
                val activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().first()
                (MainActivity::class.java.getDeclaredField("engine").apply{isAccessible=true}.get(activity) as? ReaderEngine)?.stopScanning()
                engine=ReaderEngine(context,activity).also{it.manualVoiceSubmit("计算二加三等于多少")}
            }
            waitFor("voice answer"){requests.get()==1&&!engine!!.busy}
            val question=(field("lastResult") as SolveBatch).questions.single()
            assertEquals("5",question.answer);assertTrue(question.photoPath.isBlank());assertTrue(question.brief.startsWith("语音 ·"))
            var detail:Result<SolveResult>?=null
            test.runOnMainSync{engine!!.explain(question){detail=it}}
            waitFor("voice details"){detail!=null};assertTrue(detail!!.isSuccess)
            assertEquals("full",JSONObject(payloads[1]).getString("mode"))
            status.set(502);test.runOnMainSync{engine!!.manualVoiceSubmit("计算二加三等于多少")}
            waitFor("failed answer keeps editable draft"){requests.get()==3&&!engine!!.busy}
            assertTrue((field("voiceDraft") as String).isNotBlank())
            status.set(200);test.runOnMainSync{engine!!.manualVoiceSubmit("计算二加三等于多少")}
            waitFor("explicit retry"){requests.get()==4&&!engine!!.busy}
            status.set(202);test.runOnMainSync{engine!!.manualVoiceSubmit("计算三加四等于多少")}
            waitFor("request pending"){requests.get()==5&&engine!!.busy}
            test.runOnMainSync{engine!!.interruptCurrent()};SystemClock.sleep(1800)
            test.runOnMainSync{assertFalse(engine!!.busy);assertEquals("5",(field("lastResult") as SolveBatch).questions.single().answer)}
            assertTrue(payloads.all{!it.contains("image")&&!it.contains("audio")})
            test.runOnMainSync{engine!!.readingResults=true;engine!!.startScanning()}
            waitFor("mouth front camera and local ASR ready without recording"){
                engine!!.running&&field("mouthActive")==true&&field("cameraStarting")==false&&(field("voiceSession") as? OfflineVoiceSession)?.ready==true
            }
            assertFalse((field("voiceSession") as OfflineVoiceSession).active)
        }finally{
            test.runOnMainSync{engine?.close()};socket.close();server.join(2000)
            names.forEach{target.deleteSharedPreferences(it)};root.walkBottomUp().forEach{it.delete()}
        }
    }
}
