package com.autobot.reader

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/** Offline Mandarin transcription. No audio files, remote ASR or speech-service dependency. */
internal class OfflineVoiceSession(
    private val context:Context,private val automatic:Boolean,private val eligible:()->Boolean,
    private val transcript:(String)->Unit,private val recording:(Boolean)->Unit,
    private val completed:(String,Boolean)->Unit,private val info:(String)->Unit,
):AutoCloseable {
    @Volatile private var closed=false
    @Volatile var ready=false;private set
    @Volatile var active=false;private set
    private val lock=Any()
    private var model:Model?=null
    private var recognizer:Recognizer?=null
    private var capture:SoundCapture?=null
    private val endpoint=VoiceEndpoint(automatic)
    private val committed=mutableListOf<String>()
    private var partial=""
    private var blocked=false
    private var announced=0L
    private fun clean(text:String)=text.trim().replace(Regex("(?<=[\\u3400-\\u9fff])\\s+(?=[\\u3400-\\u9fff])"),"").take(4000)
    private fun current()=clean((committed+partial).filter{it.isNotBlank()}.joinToString(" "))
    private fun resetLocked(manual:Boolean=false){recognizer?.reset();committed.clear();partial="";endpoint.reset(manual);active=manual;blocked=false}
    fun start(){Thread({
        try {
            info("正在加载中文离线模型，首次使用需要解包…")
            val folder=unpack()
            if(closed)return@Thread
            val loaded=Model(folder.absolutePath)
            synchronized(lock){if(closed){loaded.close();return@Thread};model=loaded;recognizer=Recognizer(loaded,16000f);ready=true}
            info(if(automatic)"中文离线识别已就绪，请说完整题目，结束后安静约 2 秒" else "中文离线识别已就绪，张嘴保持可开始本段录音")
            if(automatic)openMicrophone()
        }catch(_:Throwable){if(!closed){ready=false;info("离线语音识别启动失败，请检查存储空间后重新开始")}}
    },"offline-asr-init").apply{isDaemon=true;start()}}
    @Synchronized fun begin():Boolean {
        if(closed||!ready||active)return false
        synchronized(lock){resetLocked(true)}
        recording(true);info("正在录入题目，请完整说题，结束后安静约 2 秒")
        openMicrophone();return true
    }
    @Synchronized private fun openMicrophone(){
        if(closed||capture!=null)return
        capture=SoundCapture({chunk->
            var output:Pair<String,Boolean>?=null
            var stateChanged:Boolean?=null
            var stopManual=false
            synchronized(lock){
                val decoder=recognizer
                if(!closed&&decoder!=null){
                    if(!eligible()){
                        if(!blocked){resetLocked();blocked=true;stateChanged=false;stopManual=!automatic}
                    }else{
                        if(blocked){resetLocked();blocked=false}
                        if(decoder.acceptWaveForm(chunk,chunk.size)){
                            val text=JSONObject(decoder.result).optString("text");if(text.isNotBlank())committed.add(text);partial=""
                        }else partial=JSONObject(decoder.partialResult).optString("partial")
                        val text=current()
                        val wasActive=active
                        val rms=kotlin.math.sqrt(chunk.sumOf{val v=it/32768.0;v*v}/chunk.size.coerceAtLeast(1))
                        val decision=endpoint.feed(rms,chunk.size,text.isNotBlank())
                        active=endpoint.active
                        if(wasActive!=active)stateChanged=active
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-announced>=500){announced=now;transcript(text)}
                        if(decision!=null){
                            val final=JSONObject(decoder.finalResult).optString("text")
                            if(final.isNotBlank())partial=final
                            output=current() to (decision=="limit")
                            resetLocked();stateChanged=false
                        }
                    }
                }
            }
            stateChanged?.let{recording(it)}
            if(stopManual)stopMicrophone()
            if(output!=null){if(!automatic)stopMicrophone();completed(output!!.first,output!!.second)}
        },{message->if(!closed){synchronized(this){capture=null};active=false;recording(false);info(message)}},1600).also{it.start()}
    }
    fun finish(){
        val text=synchronized(lock){if(closed||!ready||!active)return;val final=JSONObject(recognizer!!.finalResult).optString("text");if(final.isNotBlank())partial=final;val value=current();resetLocked();value}
        if(!automatic)stopMicrophone();recording(false);completed(text,false)
    }
    fun pause(){stopMicrophone();synchronized(lock){if(ready&&!closed)resetLocked()};recording(false)}
    fun resume(){if(ready&&automatic)openMicrophone()}
    @Synchronized private fun stopMicrophone(){capture?.close();capture=null}
    private fun unpack():File {
        val root=File(context.filesDir,"offline-speech-cn-0.22").apply{mkdirs()}
        val model=File(root,"vosk-model-small-cn-0.22")
        val marker=File(root,"ready-v1")
        synchronized(unpackLock){
            if(marker.exists()&&File(model,"am/final.mdl").exists())return model
            val prefix=root.canonicalPath+File.separator
            var total=0L
            ZipInputStream(context.assets.open("speech/vosk-model-small-cn-0.22.zip")).use{zip->
                while(true){if(closed)throw IllegalStateException("cancelled");val entry=zip.nextEntry?:break
                    val target=File(root,entry.name).canonicalFile;check(target.path.startsWith(prefix))
                    if(entry.isDirectory)target.mkdirs()else{target.parentFile!!.mkdirs();target.outputStream().use{out->val buffer=ByteArray(32768);while(true){val n=zip.read(buffer);if(n<0)break;total+=n;check(total<=80L*1024*1024);out.write(buffer,0,n)}}}
                    zip.closeEntry()
                }
            }
            check(File(model,"am/final.mdl").length()>0);marker.writeText("3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba")
        };return model
    }
    override fun close(){closed=true;ready=false;stopMicrophone();Thread({synchronized(lock){recognizer?.close();recognizer=null;model?.close();model=null}},"offline-asr-close").apply{isDaemon=true;start()}}
    companion object{private val unpackLock=Any()}
}
