package com.autobot.reader

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic Windows Chinese TTS fixture, never records the user or contacts a service. */
class VoiceModelAndroidTest {
    @Test fun bundledChineseModelDecodesSyntheticQuestionWithoutMicrophone(){
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val session=OfflineVoiceSession(context,false,{false},{},{},{_,_->},{})
        val folder=OfflineVoiceSession::class.java.getDeclaredMethod("unpack").apply{isAccessible=true}.invoke(session) as java.io.File
        val wave=instrumentation.context.assets.open("voice-question.wav").use{it.readBytes()}
        val buffer=ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",String(wave,0,4));assertEquals("WAVE",String(wave,8,4))
        var offset=12;var pcm:ShortArray?=null
        while(offset+8<=wave.size){
            val kind=String(wave,offset,4);val size=buffer.getInt(offset+4)
            check(size>=0&&offset+8+size<=wave.size)
            if(kind=="fmt "){assertEquals(16000,buffer.getInt(offset+12));assertEquals(1,buffer.getShort(offset+10).toInt());assertEquals(16,buffer.getShort(offset+22).toInt())}
            if(kind=="data"){pcm=ShortArray(size/2){buffer.getShort(offset+8+it*2)};break}
            offset+=8+size+(size%2)
        }
        assertNotNull(pcm)
        val parts=mutableListOf<String>()
        Model(folder.absolutePath).use{model->Recognizer(model,16000f).use{decoder->
            val samples=pcm!!+ShortArray(48000)
            for(start in samples.indices step 1600){val chunk=samples.copyOfRange(start,minOf(start+1600,samples.size));if(decoder.acceptWaveForm(chunk,chunk.size))parts.add(JSONObject(decoder.result).optString("text"))}
            parts.add(JSONObject(decoder.finalResult).optString("text"))
        }}
        session.close()
        val text=parts.joinToString("").replace(" ","")
        assertTrue("Recognized: $text",text.contains("加")&&(text.contains("二")||text.contains("2"))&&(text.contains("三")||text.contains("3")))
    }
}
