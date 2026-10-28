package com.autobot.reader

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Front-only rehearsal: never takes a question photo, saves an image or calls a model. */
internal class MouthControlUi(private val activity:MainActivity,private val engine:ReaderEngine,private val reading:(Boolean)->Unit){
    fun show():android.app.Dialog{
        engine.stopScanning()
        val prefs=activity.getSharedPreferences("settings",android.content.Context.MODE_PRIVATE)
        val sheet=SettingsSheet(activity,"嘴型试用","仅试用前置检测，不拍题、不上传人脸、不调用解题模型。试用会停止当前识题。")
        val card=sheet.card("闭嘴就绪 → 张嘴保持","让脸与嘴部完整入镜，正对前置摄像头；关闭后回到使用偏好。")
        val preview=PreviewView(activity).apply{implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FIT_CENTER}
        card.addView(preview,android.widget.LinearLayout.LayoutParams(-1,sheet.dp(280)))
        val status=sheet.label("正在启动前置镜头…",15f,AppPalette.primaryStrong).apply{setPadding(0,sheet.dp(16),0,0)};card.addView(status)
        val count=sheet.label("试触发 0 次",13f,AppPalette.muted);card.addView(count)
        val gate=MouthGestureGate(prefs.getInt("mouth_hold_ms",600).toLong(),prefs.getInt("mouth_threshold",22)/100f)
        var hits=0;var closed=false
        val worker=Executors.newSingleThreadExecutor()
        var provider:ProcessCameraProvider?=null
        val detector=MouthFaceAnalyzer{value,reason->activity.runOnUiThread{
            if(!closed){val state=gate.observe(android.os.SystemClock.elapsedRealtime(),value);status.text=if(value==null)reason else state.message
                if(state.fire){hits++;count.text="试触发 $hits 次（没有拍题或模型调用）"}}
        }}
        sheet.footer("完成试用"){sheet.dialog.dismiss()}
        sheet.dialog.setOnDismissListener{closed=true;detector.close();provider?.unbindAll();worker.shutdown();reading(false);engine.setStatus("嘴型试用已结束，点击开始识题可启动当前模式")}
        reading(true);sheet.show()
        val future=ProcessCameraProvider.getInstance(activity)
        future.addListener({if(!closed){runCatching{
            provider=future.get();val camera=provider!!;camera.unbindAll()
            check(camera.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)){"设备没有可用前置摄像头"}
            val analysis=ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(640,480),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also{it.setAnalyzer(worker,detector::analyze)}
            camera.bindToLifecycle(activity,CameraSelector.DEFAULT_FRONT_CAMERA,Preview.Builder().build().also{it.surfaceProvider=preview.surfaceProvider},analysis)
            status.text="请先闭嘴，再张嘴保持"
        }.onFailure{status.text="前置检测启动失败，请关闭后重试"}}},ContextCompat.getMainExecutor(activity))
        return sheet.dialog
    }
}
