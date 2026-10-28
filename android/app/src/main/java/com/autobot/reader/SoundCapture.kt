package com.autobot.reader

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Owns one microphone session. PCM exists only in memory and is released on stop. */
internal class SoundCapture(private val samples:(ShortArray)->Unit,private val failed:(String)->Unit):AutoCloseable {
    @Volatile private var closed=false
    @Volatile private var recorder:AudioRecord?=null
    @Volatile var isRunning=false
        private set
    private val worker=Thread({
        var audio:AudioRecord?=null
        try {
            val minimum=AudioRecord.getMinBufferSize(SoundFeatures.RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum>0)
            audio=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SoundFeatures.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum*2,SoundFeatures.RATE*2)).build()
            recorder=audio
            if(!closed){check(audio.state==AudioRecord.STATE_INITIALIZED);audio.startRecording();check(audio.recordingState==AudioRecord.RECORDSTATE_RECORDING);isRunning=true}
            val buffer=ShortArray(512)
            while(!closed){val n=audio.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING);check(n>0);if(!closed)samples(buffer.copyOf(n))}
        }catch(_:Exception){if(!closed)failed("麦克风不可用，请检查录音权限、系统麦克风开关或其他录音应用")}
        finally{isRunning=false;recorder=null;runCatching{audio?.stop()};audio?.release()}
    },"sound-capture").apply{isDaemon=true}
    fun start(){worker.start()}
    override fun close(){closed=true;runCatching{recorder?.stop()}}
}
