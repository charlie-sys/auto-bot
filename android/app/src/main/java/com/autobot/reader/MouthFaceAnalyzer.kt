package com.autobot.reader

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceContour
import java.util.concurrent.atomic.AtomicBoolean

/** Bundled, local detector. Front frames are never saved or passed to solving/upload code. */
internal class MouthFaceAnalyzer(private val sample:(Float?,String)->Unit):AutoCloseable {
    private val detector=FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL).setMinFaceSize(.25f).build())
    private val processing=AtomicBoolean(false)
    @Volatile private var closed=false
    private var lastAt=0L
    fun analyze(proxy:ImageProxy){
        val now=android.os.SystemClock.elapsedRealtime()
        if(closed||now-lastAt<120||!processing.compareAndSet(false,true)){proxy.close();return}
        lastAt=now
        val image=proxy.image
        if(image==null){processing.set(false);proxy.close();return}
        try {
            detector.process(InputImage.fromMediaImage(image,proxy.imageInfo.rotationDegrees)).addOnSuccessListener{faces->
                if(closed)return@addOnSuccessListener
                val face=faces.singleOrNull()
                val value=if(face==null||face.boundingBox.width()<160||face.boundingBox.height()<160||kotlin.math.abs(face.headEulerAngleY)>25||kotlin.math.abs(face.headEulerAngleX)>25)null
                else MouthAperture.measure(
                    face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty().map{MouthPoint(it.x,it.y)},
                    face.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty().map{MouthPoint(it.x,it.y)})
                sample(value,if(value==null)"请靠近、正对前置镜头，露出完整嘴部" else "嘴部检测中")
            }.addOnFailureListener{if(!closed)sample(null,"嘴部检测暂不可用，请重新开始")}
                .addOnCompleteListener{processing.set(false);proxy.close()}
        }catch(_:Exception){processing.set(false);proxy.close();if(!closed)sample(null,"嘴部检测暂不可用，请重新开始")}
    }
    override fun close(){closed=true;detector.close()}
}
