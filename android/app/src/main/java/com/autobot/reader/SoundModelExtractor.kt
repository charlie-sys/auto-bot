package com.autobot.reader

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** YAMNet embeddings and category evidence, entirely on-device, without preserving audio. */
internal class SoundModelExtractor(private val context:Context):AutoCloseable {
    private var interpreter:Interpreter?=null
    @Synchronized fun extract(samples:ShortArray,kind:String="generic"):SoundTemplate {
        val spectral=SoundFeatures.extract(samples)
        val model=interpreter?:run {
            val bytes=context.assets.open("sound/yamnet_features.tflite").use{it.readBytes()}
            val buffer=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply{put(bytes);rewind()}
            Interpreter(buffer,Interpreter.Options().setNumThreads(2)).also{interpreter=it}
        }
        val starts=(listOf(0)+(0 until samples.size step 7800).toList()+listOf(maxOf(0,samples.size-15600))).distinct()
        fun rms(start:Int):Double {val end=minOf(samples.size,start+15600);return sqrt((start until end).sumOf{val v=samples[it]/32768.0;v*v}/(end-start).coerceAtLeast(1))}
        val energies=starts.associateWith{rms(it)};val peak=energies.values.max()
        val embedding=FloatArray(1024);val categories=FloatArray(521);var total=0.0
        for(start in starts){
            val energy=energies.getValue(start)
            if(energy<maxOf(.006,peak*.25))continue
            val wave=ByteBuffer.allocateDirect(15600*4).order(ByteOrder.nativeOrder())
            for(i in 0 until 15600)wave.putFloat(if(start+i<samples.size)samples[start+i]/32768f else 0f)
            wave.rewind()
            val scores=Array(1){FloatArray(521)}
            val embeddingTensor=model.getOutputTensor(1)
            val vectors=ByteBuffer.allocateDirect(embeddingTensor.numBytes()).order(ByteOrder.nativeOrder())
            model.runForMultipleInputsOutputs(arrayOf(wave),hashMapOf<Int,Any>(0 to scores,1 to vectors))
            vectors.rewind()
            for(i in embedding.indices){
                val value=if(embeddingTensor.dataType()==DataType.INT8){val q=embeddingTensor.quantizationParams();(vectors.get().toInt()-q.zeroPoint)*q.scale}else vectors.float
                embedding[i]+=(value*energy).toFloat()
            }
            for(i in categories.indices)categories[i]=maxOf(categories[i],scores[0][i])
            total+=energy
        }
        check(total>0){"声音太小，请靠近手机重录"}
        val norm=sqrt(embedding.sumOf{it.toDouble()*it}).coerceAtLeast(1e-9)
        for(i in embedding.indices)embedding[i]=(embedding[i]/norm).toFloat()
        return spectral.copy(embedding=embedding,categories=categories,kind=kind)
    }
    @Synchronized override fun close(){interpreter?.close();interpreter=null}
}
