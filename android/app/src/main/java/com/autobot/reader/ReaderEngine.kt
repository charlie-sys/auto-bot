package com.autobot.reader

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors

import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner

/** Owns recognition and speech independently of an Activity or display surface. */
class ReaderEngine(context: Context, private val owner: LifecycleOwner) : ContextWrapper(context), TextToSpeech.OnInitListener {
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val account by lazy { AccountStore(this) }
    var onAuthExpired: (() -> Unit)? = null
    private val detector = QuestionDetector()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newFixedThreadPool(2)
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var ui: ReaderUi? = null
    private var previewCase: Preview? = null
    private var lensFacing = prefs.getInt("camera_facing", CameraSelector.LENS_FACING_BACK)
        .let { if (it == CameraSelector.LENS_FACING_FRONT) it else CameraSelector.LENS_FACING_BACK }
    private var cameraStarting = false
    @Volatile private var cameraGeneration = 0L
    private var analysisCase: ImageAnalysis? = null
    private val autoFraming = AutoFramingController()
    @Volatile private var assistSettleUntil = 0L
    @Volatile private var adjustmentVersion = 0L
    private var lastFocusAt = 0L
    private var lastFocusX = .5f
    private var lastFocusY = .5f
    private var focusPending = false
    private var focusTicket = 0L
    var focusRequests = 0L
        private set
    var focusSuccesses = 0L
        private set
    var automaticZooms = 0L
        private set
    fun isNetworkCamera()=prefs.getString("camera_source","phone")=="network"
    fun sourceChanged(){detector.clearMemory();networkZoom=1f;refreshCameraUi();ui?.setZoom(1f);ui?.updateSession(running,paused,busy,capturing);setStatus("已选择${if(isNetworkCamera())"网络相机" else "手机镜头"}，点击开始识题")}
    private var networkZoom=1f
    private var networkFeed:NetworkFrameClient?=null
    private var networkBitmap:Bitmap?=null
    private var networkOcrBitmap:Bitmap?=null
    private var networkAt=0L
    private class NetworkSelection(val anchor:String,val id:Long,val generation:Long){
        var policy:BestShotPolicy?=null
        var best:File?=null
        var processing=false
        var different=0
    }
    private var networkSelection:NetworkSelection?=null
    fun currentZoom(): Float = if(isNetworkCamera())networkZoom else camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    private fun selector() = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    private var closed = false
    private var foregroundOnlySuspended = false
    private var resultMessage: Pair<String, String>? = null
    var onStateChanged: (() -> Unit)? = null
    var framesAnalyzed = 0L
        private set
    var requestsSent = 0L
        private set
    var status = "等待开始。首次上传会将题目图片发送至配置的模型服务。"
        private set
    private var capture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var readingResults = false
    private var manualAfterStart = false
    var busy = false
    var capturing = false
    private var lastAnalyzed = 0L
    @Volatile private var requestId = 0L
    private var activePhotoId = 0L
    private var photoTimeout: Runnable? = null
    private var steadyCleanup:(()->Unit)?=null
    private var steadyAnchor=""
    private var steadyChanges=0
    private var pending: Captured? = null
    private var latest: Captured? = null
    @Volatile private var currentRequest:SolveRequest?=null
    @Volatile private var observedText=""
    @Volatile private var observedAt=0L
    @Volatile private var needsSceneAnchor=false
    var lastResult: SolveBatch? = null
    private var tts: TextToSpeech? = null
    private var speakingParts = listOf<String>()
    private var speakingIndex = 0
    private var speechGeneration = 0L
    private var deferredSpeech: SolveBatch? = null
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingStatusSpeech: Runnable? = null
    private var pendingStatusKey = ""
    private var lastSpokenStatus = ""
    private var lastStatusSpeechAt = 0L
    private var statusSpeechActive = false
    private var ttsReady = false
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioMonitoring = false
    private var noisyMonitoring = false
    private val audioDevices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (prefs.getBoolean("bluetooth_only", false) &&
                removedDevices.any { SpeechPolicy.isBluetoothMediaOutput(it.type) }) stopSpeech()
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && prefs.getBoolean("bluetooth_only", false)) stopSpeech()
        }
    }

    private fun outputTypes(): List<Int> = runCatching {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }.getOrDefault(emptyList())

    private fun canSpeak(): Boolean = !closed && !foregroundOnlySuspended && SpeechPolicy.canSpeak(
        prefs.getBoolean("muted", false), prefs.getBoolean("bluetooth_only", false), outputTypes())

    /** All TTS output, including error prompts, passes this last check. */
    private fun speakText(text: String, utteranceId: String): Int {
        if (!ttsReady || !canSpeak()) return TextToSpeech.ERROR
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, prefs.getInt("volume", 100) / 100f) }
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.SUCCESS) onStateChanged?.invoke()
        return result
    }


    data class Captured(val file: File, val text: String, val hash: Long, val id: Long, val uploaded: Boolean = false)

    init {
        tts = TextToSpeech(this, this)
        runCatching {
            audioManager.registerAudioDeviceCallback(audioDevices, statusHandler)
            audioMonitoring = true
            ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyMonitoring = true
        }
        detector.sensitivity = prefs.getInt("sensitivity", 1)
        detector.antiShake = prefs.getBoolean("anti_shake",true)
        detector.resetSession()
    }

    private fun runOnUiThread(action: () -> Unit) { statusHandler.post(action) }

    fun attachUi(view: ReaderUi) {
        foregroundOnlySuspended = false
        ui = view
        view.setMuted(prefs.getBoolean("muted", false))
        view.setStatus(status)
        refreshCameraUi()
        view.setNetworkCamera(isNetworkCamera())
        if(isNetworkCamera()){view.setZoom(networkZoom);view.showNetworkFrame(networkBitmap)}
        view.updateSession(running, paused, busy, capturing)
        lastResult?.let { view.showBatch(it) }
        resultMessage?.let { view.showMessage(it.first, it.second) }
        attachPreview()
    }

    private fun attachPreview() {
        if(isNetworkCamera())return
        val view = ui ?: return
        val provider = cameraProvider ?: return
        if (!running || camera == null || previewCase != null) return
        val useCase = Preview.Builder().build().also { it.surfaceProvider = view.preview.surfaceProvider }
        runCatching { provider.bindToLifecycle(owner, selector(), useCase) }
            .onSuccess { previewCase = useCase }
            .onFailure { setStatus("预览恢复失败：${it.message}") }
    }

    fun detachUi() {
        ui?.showNetworkFrame(null)
        previewCase?.let { cameraProvider?.unbind(it) }
        previewCase = null
        ui = null
        readingResults = false
    }

    fun suspendForegroundOnly() {
        foregroundOnlySuspended = true
        stopScanning()
    }

    fun stopAll() {
        ++requestId
        currentRequest?.cancel();currentRequest=null
        busy = false
        stopScanning()
    }

    fun prepareUpload(): Boolean {
        if (account.token().isBlank()) { onAuthExpired?.invoke(); return false }
        if (busy || capturing) return false
        if (running) paused = true
        stopSpeech()
        pending?.file?.delete(); pending = null
        ++requestId
        setStatus("已暂停，请选择包含完整题目的图片")
        return true
    }

    fun settingsChanged() {
        stopSpeech()
        autoFraming.reset(android.os.SystemClock.elapsedRealtime())
        if (!prefs.getBoolean("auto_focus", true)) {
            ++focusTicket; focusPending = false
            runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
        }
        detector.sensitivity = prefs.getInt("sensitivity", 1)
        lastSpokenStatus = ""
        if (!prefs.getBoolean("speak_status", false)) {
            cancelStatusSpeech()
            if (statusSpeechActive) { tts?.stop(); statusSpeechActive = false }
        }
        if (!canSpeak()) stopSpeech()
    }

    fun resetAccount() {
        stopAll()
        busy = false
        lastResult = null
        resultMessage = null
        detector.clearMemory();observedText="";observedAt=0L
        detector.clearQueued("")
        ui?.showMessage("请登录后识题", "每个账号使用自己的模型配置与历史记录")
    }

    fun hasWork(): Boolean = running || busy || capturing || tts?.isSpeaking == true || speakingIndex < speakingParts.size

    fun close() {
        closed = true
        stopScanning()
        cancelStatusSpeech()
        detachUi()
        if (audioMonitoring) audioManager.unregisterAudioDeviceCallback(audioDevices)
        if (noisyMonitoring) unregisterReceiver(noisyReceiver)
        recognizer.close()
        tts?.shutdown()
        cameraExecutor.shutdown()
        networkExecutor.shutdown()
    }

    fun importPhoto(uri: android.net.Uri) {
        if (running) paused = true
        capturing = true
        val id = ++requestId
        setStatus("正在处理图片，完成后自动解题")
        cameraExecutor.execute {
            val raw = File.createTempFile("import_", ".image", cacheDir)
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(raw).use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= 50L * 1024 * 1024) { "原图超过 50MB，请先缩小图片" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法打开所选图片，请重新选择")
                preparePhoto(raw)
            }
            raw.delete()
            runOnUiThread {
                capturing = false
                if (closed || id != requestId) { result.getOrNull()?.delete(); return@runOnUiThread }
                result.onSuccess { send(Captured(it, "", 0L, id, uploaded = true)) }
                    .onFailure { setStatus("图片处理失败：${it.message ?: "请换一张图片"}") }
            }
        }
    }

    fun toggleMute() {
        val silent = !prefs.getBoolean("muted", false)
        prefs.edit().putBoolean("muted", silent).apply()
        if (silent) stopSpeech()
        ui?.setMuted(silent)
        Toast.makeText(this, if (silent) "已静音，识题仍会继续" else "已恢复声音，可点击播报重听答案", Toast.LENGTH_SHORT).show()
    }

    fun changeZoom(factor: Float) {
        if(isNetworkCamera()){
            if(capturing){setStatus("请先中断选帧再调整取景");return}
            interruptCurrent(false)
            networkZoom=(networkZoom*factor).coerceIn(1f,4f)
            detector.resetSession();ui?.setZoom(networkZoom);setStatus("数字裁剪 ${String.format(Locale.US,"%.1f",networkZoom)}×，不增加原始细节")
            return
        }
        val active = camera ?: return
        val state = active.cameraInfo.zoomState.value ?: return
        val ratio = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        active.cameraControl.setZoomRatio(ratio)
        autoFraming.manualOverride(android.os.SystemClock.elapsedRealtime())
        ++adjustmentVersion; ++focusTicket; focusPending = false; lastFocusAt = 0L
        assistSettleUntil = android.os.SystemClock.elapsedRealtime() + 1200L
        runCatching { active.cameraControl.cancelFocusAndMetering() }
        ui?.setZoom(ratio)
        detector.resetSession()
        lastAnalyzed = System.currentTimeMillis() + 1200
    }

    private fun requestAutoFocus(x: Float = .5f, y: Float = .5f, rotation: Int = 0, blurred: Boolean = false): Boolean {
        if (!running || paused || busy&&!prefs.getBoolean("new_topic_priority",true) || capturing || !prefs.getBoolean("auto_focus", true)) return false
        val active = camera ?: return false
        val analysis = analysisCase ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (focusPending && now - lastFocusAt < 4500L) return false
        val moved = kotlin.math.abs(x - lastFocusX) + kotlin.math.abs(y - lastFocusY) > .16f
        val interval = if (blurred) 3500L else if (moved) 8000L else 12_000L
        if (lastFocusAt != 0L && now - lastFocusAt < interval) return false
        lastFocusAt = now; lastFocusX = x; lastFocusY = y
        return runCatching {
            val surface = AutoFramingController.surfacePoint(x.coerceIn(.03f, .97f), y.coerceIn(.03f, .97f), rotation)
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f, analysis).createPoint(surface.first, surface.second, .2f)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
            if (!active.cameraInfo.isFocusMeteringSupported(action)) return@runCatching false
            val generation = cameraGeneration
            val ticket = ++focusTicket
            val future = active.cameraControl.startFocusAndMetering(action)
            focusPending = true; focusRequests++; ++adjustmentVersion
            assistSettleUntil = now + 900L
            detector.resetSession()
            future.addListener({
                if (generation == cameraGeneration && ticket == focusTicket && !closed) {
                    focusPending = false
                    if (runCatching { future.get().isFocusSuccessful }.getOrDefault(false)) focusSuccesses++
                    assistSettleUntil = maxOf(assistSettleUntil, android.os.SystemClock.elapsedRealtime() + 250L)
                }
            }, ContextCompat.getMainExecutor(this))
            true
        }.getOrDefault(false)
    }

    private fun adjustForText(boxes: List<Rect>, width: Int, height: Int, rotation: Int, readable: Boolean = false): Boolean {
        if (busy || capturing || paused) return false
        if(prefs.getBoolean("anti_shake",true)&&detector.holdingCandidate())return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < assistSettleUntil) return true
        val active = camera ?: return false
        if (!readable && prefs.getBoolean("auto_zoom", true)) {
            val zoom = active.cameraInfo.zoomState.value
            if (zoom != null) {
                val regions = boxes.map { TextRegion((it.left.toFloat() / width).coerceIn(0f, 1f), (it.top.toFloat() / height).coerceIn(0f, 1f), (it.right.toFloat() / width).coerceIn(0f, 1f), (it.bottom.toFloat() / height).coerceIn(0f, 1f)) }
                val change = autoFraming.observe(regions, zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio, now)
                if (change != null) {
                    val generation = cameraGeneration
                    val future = active.cameraControl.setZoomRatio(change.ratio)
                    automaticZooms++; ++adjustmentVersion; ++focusTicket; focusPending = false
                    lastFocusAt = 0L
                    assistSettleUntil = now + 1300L
                    detector.resetSession()
                    setStatus(if (change.searching) "正在自动调整取景，寻找题目" else "正在自动调整取景，保留完整题目")
                    future.addListener({
                        if (generation == cameraGeneration) runCatching { future.get() }
                    }, ContextCompat.getMainExecutor(this))
                    return true
                }
            }
        }
        // Readable OCR is stronger evidence than a periodic focus timer. Let
        // consecutive frames confirm instead of repeatedly resetting them.
        if (readable) return false
        val target = boxes.maxByOrNull { it.width().toLong() * it.height() }
        val x = target?.exactCenterX()?.div(width) ?: .5f
        val y = target?.exactCenterY()?.div(height) ?: .5f
        val focusing = requestAutoFocus(x, y, rotation)
        if (focusing) setStatus("正在自动对焦题目，请保持稳定")
        return focusing
    }

    private fun refreshCameraUi() {
        if(isNetworkCamera()){ui?.setNetworkCamera(true);return}
        ui?.setNetworkCamera(false)
        val canSwitch = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        ui?.setCameraFacing(lensFacing == CameraSelector.LENS_FACING_FRONT, canSwitch, cameraStarting)
    }

    fun flipCamera() {
        if(isNetworkCamera()){setStatus("网络相机不支持前后镜头翻转，请在设置切换来源");return}
        if (closed || busy || capturing || cameraStarting) return
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val wasRunning = running
        val wasPaused = paused
        val operation = ++cameraGeneration
        cameraStarting = true
        setStatus("正在切换摄像头")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (closed || operation != cameraGeneration) return@addListener
            try {
                val provider = future.get()
                if (!provider.hasCamera(CameraSelector.Builder().requireLensFacing(target).build())) {
                    cameraStarting = false
                    setStatus("当前设备没有可用的另一侧摄像头")
                    return@addListener
                }
                stopScanning()
                lensFacing = target
                prefs.edit().putInt("camera_facing", lensFacing).apply()
                detector.resetSession()
                lastAnalyzed = 0L
                ui?.setZoom(1f)
                if (wasRunning) { startScanning(); paused = wasPaused }
                else setStatus(if (target == CameraSelector.LENS_FACING_FRONT) "已选择前置摄像头，点击开始识题" else "已选择后置摄像头，点击开始识题")
            } catch (e: Exception) {
                cameraStarting = false
                setStatus("摄像头切换失败：${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun setStatus(message: String) {
        status = message
        ui?.setStatus(message)
        refreshCameraUi()
        ui?.updateSession(running, paused, busy, capturing)
        onStateChanged?.invoke()
        announceStatus(message)
    }

    fun cancelStatusSpeech() {
        pendingStatusSpeech?.let { statusHandler.removeCallbacks(it) }
        pendingStatusSpeech = null
        pendingStatusKey = ""
    }

    fun announceStatus(message: String) {
        if (!prefs.getBoolean("speak_status", false) || !canSpeak()) {
            cancelStatusSpeech()
            return
        }
        val spoken = when {
            message.startsWith("等待开始") || message.startsWith("已解答") || message.startsWith("已获得") -> ""
            message.contains("失败") -> message.substringBefore('：') + "，请查看屏幕提示"
            message.contains("需要摄像头权限") -> "请允许摄像头权限"
            message.contains("正在启动摄像头") -> "正在启动摄像头"
            message.contains("自动对焦") -> "正在自动对焦，请保持镜头稳定"
            message.contains("自动调整取景") -> "正在自动调整取景"
            message.contains("已暂停") -> "自动识题已暂停"
            message == "已停止" -> "摄像头识题已停止"
            message.contains("正在处理图片") -> "正在处理上传图片"
            message.contains("识别中") -> "正在识别并解答题目"
            message.contains("发现新题") -> "发现新题，等待当前解题完成"
            message.contains("正在拍照") -> "正在拍摄题目"
            message.contains("过暗") -> "画面过暗，请增加光线"
            message.contains("反光") -> "画面有反光，请调整拍摄角度"
            message.contains("未找到") || message.contains("不清") -> "未找到清晰完整的题目，请调整距离或角度"
            message.contains("查找") || message.contains("等待新题") -> "摄像头正在寻找题目"
            else -> ""
        }
        if (spoken.isEmpty()) { cancelStatusSpeech(); return }
        if (spoken == pendingStatusKey) return
        cancelStatusSpeech()
        if (spoken == lastSpokenStatus || readingResults) return
        pendingStatusKey = spoken
        val task = Runnable {
            pendingStatusSpeech = null
            pendingStatusKey = ""
            if (closed || !ttsReady || readingResults ||
                !prefs.getBoolean("speak_status", false) || !canSpeak() ||
                tts?.isSpeaking == true || speakingIndex < speakingParts.size) return@Runnable
            val now = android.os.SystemClock.elapsedRealtime()
            val urgent = message.contains("失败") || message.contains("已暂停") || message == "已停止"
            if (!urgent && now - lastStatusSpeechAt < 4000L) return@Runnable
            tts?.setSpeechRate(prefs.getInt("speech_rate", 100) / 100f)
            statusSpeechActive = true
            val result = speakText(spoken, "status_$now")
            if (result == TextToSpeech.SUCCESS) { lastSpokenStatus = spoken; lastStatusSpeechAt = now }
        }
        pendingStatusSpeech = task
        statusHandler.postDelayed(task, 600L)
    }

    private fun clearNetworkSelection(){networkSelection?.best?.delete();networkSelection=null}

    private fun startNetworkScanning(){
        if(running){paused=false;setStatus("等待网络相机新题目");return}
        val base=prefs.getString("base_url","").orEmpty()
        if(runCatching{AccountApi.server(base)}.isFailure){setStatus("请先配置识题后端地址");return}
        running=true;paused=false;cameraStarting=false
        val generation=++cameraGeneration
        observedText="";observedAt=0;lastAnalyzed=0;networkAt=0
        detector.resetSession();refreshCameraUi()
        ui?.updateSession(running,paused,busy,capturing)
        setStatus("正在连接网络摄像头，请先在 DJI Mimo 开始 RTMP 直播")
        networkFeed=NetworkFrameClient(base,account.token(),{source,done->
            if(!running||closed||generation!=cameraGeneration){source.recycle();done()}
            else receiveNetworkFrame(source,generation,done)
        },{code,message->
            if(generation==cameraGeneration&&running){
                interruptCurrent(false);detector.resetSession()
                val previous=networkBitmap;networkBitmap=null;networkAt=0
                ui?.showNetworkFrame(null);ui?.overlay?.updateBoxes(emptyList(),1,1)
                if(previous!==networkOcrBitmap)previous?.recycle()
                setStatus(message)
                if(code==401){stopAll();onAuthExpired?.invoke()}
            }
        }).also{it.start()}
        if(manualAfterStart){manualAfterStart=false;setStatus("等待新鲜网络画面，到图后可点拍照识题")}
    }

    private fun receiveNetworkFrame(source:Bitmap,generation:Long,done:()->Unit){
        val bitmap=try{NetworkImageTransform.apply(source,prefs.getInt("network_rotation",0),networkZoom,
            prefs.getInt("network_left",0),prefs.getInt("network_top",0),prefs.getInt("network_width",100),prefs.getInt("network_height",100))}
        catch(e:Exception){source.recycle();done();setStatus("网络相机取景参数无效，请重新设置");return}
        source.recycle()
        val previous=networkBitmap;networkBitmap=bitmap;networkAt=android.os.SystemClock.elapsedRealtime()
        if(previous!==networkOcrBitmap)previous?.recycle()
        ui?.showNetworkFrame(bitmap)
        val now=System.currentTimeMillis()
        val interval=when(prefs.getInt("sensitivity",1)){0->1100L;2->550L;else->850L}
        if(paused||readingResults||capturing&&networkSelection==null||networkSelection==null&&now-lastAnalyzed<interval){done();return}
        if((busy||speakingIndex<speakingParts.size)&&!prefs.getBoolean("new_topic_priority",true)){done();return}
        lastAnalyzed=now;framesAnalyzed++;networkOcrBitmap=bitmap
        recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener{found->
            if(!running||generation!=cameraGeneration||paused)return@addOnSuccessListener
            val text=found.textBlocks.joinToString("\n"){it.text}
            observedText=text;observedAt=android.os.SystemClock.elapsedRealtime()
            val boxes=found.textBlocks.mapNotNull{it.boundingBox}
            ui?.overlay?.updateBoxes(boxes,bitmap.width,bitmap.height)
            val selecting=networkSelection
            if(selecting!=null){
                if(!detector.sameView(selecting.anchor,text)){
                    selecting.different++
                    if(selecting.different>=2){interruptCurrent(false);detector.resetSession();setStatus("网络画面已换题，已丢弃旧候选")}
                }else{selecting.different=0;scoreNetworkCandidate(selecting,bitmap)}
                return@addOnSuccessListener
            }
            detector.antiShake=prefs.getBoolean("anti_shake",true)
            val region=if(boxes.isEmpty())null else floatArrayOf(boxes.minOf{it.left}.toFloat()/bitmap.width,boxes.minOf{it.top}.toFloat()/bitmap.height,boxes.maxOf{it.right}.toFloat()/bitmap.width,boxes.maxOf{it.bottom}.toFloat()/bitmap.height)
            if(detector.observe(text,0,region)){
                interruptCurrent(false)
                lastResult=null;resultMessage="正在解答网络相机新题" to "只提交选中的一张画面"
                ui?.showMessage(resultMessage!!.first,resultMessage!!.second)
                if(prefs.getBoolean("anti_shake",true)){
                    val selection=NetworkSelection(text,++requestId,generation)
                    networkSelection=selection;capturing=true;activePhotoId=selection.id
                    photoTimeout=Runnable{interruptCurrent(false);detector.resetSession();setStatus("网络选帧超时，请靠稳相机后重试")}.also{statusHandler.postDelayed(it,6000)}
                    setStatus("已发现题目，正在选择清晰网络画面")
                    scoreNetworkCandidate(selection,bitmap)
                }else captureNetworkDirect(text)
            }else if(!busy){
                setStatus(if(detector.normalize(text).length<5)"网络画面文字不清晰，请放大原页面或调整相机" else if(detector.known(text))"网络画面与已处理题目相似；重做可点拍照识题" else "已连接网络相机，正在确认题目")
            }
        }.addOnFailureListener{if(generation==cameraGeneration)setStatus("网络画面文字检查失败，等待下一帧")}
            .addOnCompleteListener{
                if(networkOcrBitmap===bitmap)networkOcrBitmap=null
                if(networkBitmap!==bitmap&&!bitmap.isRecycled)bitmap.recycle()
                done()
            }
    }

    private fun scoreNetworkCandidate(selection:NetworkSelection,bitmap:Bitmap){
        if(selection.processing||networkSelection!==selection)return
        selection.processing=true
        val copy=bitmap.copy(Bitmap.Config.ARGB_8888,false)
        cameraExecutor.execute{
            var file:File?=null
            val result=runCatching{
                val score=PhotoSharpness.score(copy)
                val photo=File.createTempFile("network_",".jpg",cacheDir);file=photo
                FileOutputStream(photo).use{check(copy.compress(Bitmap.CompressFormat.JPEG,90,it))}
                photo to score
            }
            copy.recycle()
            runOnUiThread{
                if(networkSelection!==selection||selection.id!=requestId||selection.generation!=cameraGeneration){file?.delete();return@runOnUiThread}
                selection.processing=false
                result.onSuccess{(photo,score)->
                    val first=selection.policy==null
                    val policy=selection.policy?:BestShotPolicy(android.os.SystemClock.elapsedRealtime()).also{selection.policy=it}
                    if(policy.consider(score)){selection.best?.delete();selection.best=photo}else photo.delete()
                    if(first){
                        photoTimeout?.let{statusHandler.removeCallbacks(it)}
                        photoTimeout=Runnable{deliverNetworkSelection(selection)}.also{statusHandler.postDelayed(it,900)}
                    }
                    if(!policy.needsAnother(android.os.SystemClock.elapsedRealtime()))deliverNetworkSelection(selection)
                }.onFailure{file?.delete();interruptCurrent(false);detector.resetSession();setStatus("网络选帧失败，请重新取题")}
            }
        }
    }

    private fun deliverNetworkSelection(selection:NetworkSelection){
        if(networkSelection!==selection||selection.id!=requestId)return
        val file=selection.best
        if(file==null||selection.different>0||android.os.SystemClock.elapsedRealtime()-networkAt>2500){
            interruptCurrent(false);detector.resetSession();setStatus("网络画面变化或已过期，重新等待新鲜画面");return
        }
        selection.best=null;networkSelection=null
        if(!finishPhoto(selection.id,selection.generation)){file.delete();return}
        detector.markQueued(selection.anchor,0)
        val item=Captured(file,selection.anchor,0,selection.id);latest=item;send(item)
    }

    private fun captureNetworkDirect(text:String){
        val bitmap=networkBitmap
        if(bitmap==null||android.os.SystemClock.elapsedRealtime()-networkAt>2500){setStatus("没有新鲜网络画面，请先在 DJI Mimo 开始推流");return}
        val copy=bitmap.copy(Bitmap.Config.ARGB_8888,false)
        val scene=text.ifBlank{observedText.takeIf{android.os.SystemClock.elapsedRealtime()-observedAt<2500}.orEmpty()}
        val id=++requestId;val generation=cameraGeneration
        capturing=true;activePhotoId=id;setStatus("正在提取网络画面")
        cameraExecutor.execute{
            var file:File?=null
            val result=runCatching{
                File.createTempFile("network_",".jpg",cacheDir).also{file=it;FileOutputStream(it).use{stream->check(copy.compress(Bitmap.CompressFormat.JPEG,90,stream))}}
            };copy.recycle()
            runOnUiThread{
                if(id!=requestId||generation!=cameraGeneration){file?.delete();return@runOnUiThread}
                finishPhoto(id,generation)
                result.onSuccess{photo->detector.markQueued(scene,0);val item=Captured(photo,scene,0,id);latest=item;send(item)}
                    .onFailure{file?.delete();setStatus("网络截图失败，请重新尝试")}
            }
        }
    }

    fun startScanning() {
        if (account.token().isBlank()) { onAuthExpired?.invoke(); return }
        if(isNetworkCamera()){startNetworkScanning();return}
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            manualAfterStart = false; setStatus("需要摄像头权限才能识题"); return
        }
        if (running) { paused = false; setStatus("等待新题目"); return }
        running = true
        paused = false
        cameraStarting = true
        val generation = ++cameraGeneration
        observedText="";observedAt=0L
        ++adjustmentVersion; ++focusTicket; focusPending = false; lastFocusAt = 0L
        assistSettleUntil = 0L
        autoFraming.reset(android.os.SystemClock.elapsedRealtime())
        detector.resetSession()
        setStatus("正在启动摄像头")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (!running || closed || generation != cameraGeneration) return@addListener
                check(provider.hasCamera(selector())) { "当前镜头不可用，请尝试切换摄像头" }
                cameraProvider = provider
                provider.unbindAll()
                previewCase = null
                
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(android.util.Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
                val analysis = ImageAnalysis.Builder().setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysisCase = analysis
                analysis.setAnalyzer(cameraExecutor) { analyze(it) }
                camera = provider.bindToLifecycle(owner, selector(), capture, analysis)
                attachPreview()
                statusHandler.postDelayed({ if (generation == cameraGeneration) requestAutoFocus() }, 300L)
                camera?.cameraInfo?.zoomState?.observe(owner) { state -> ui?.setZoom(state.zoomRatio) }
                setStatus(if (paused) "已暂停识题与播报" else "正在自动查找完整题目；可双指或点＋－缩放")
                if (manualAfterStart) { manualAfterStart = false; statusHandler.postDelayed({ if (running && generation == cameraGeneration) captureQuestion("", 0L) }, 500) }
            } catch (e: Exception) { running = false; manualAfterStart = false; setStatus("摄像头启动失败：${e.message}") }
            finally {
                if (generation == cameraGeneration) {
                    cameraStarting = false
                    refreshCameraUi()
                    ui?.updateSession(running, paused, busy, capturing)
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun togglePause() {
        if (!running) return
        paused = !paused
        if (paused) stopSpeech()
        setStatus(if (paused) "已暂停识题与播报" else "等待新题目")
    }

    fun stopScanning() {
        ++requestId
        networkFeed?.close();networkFeed=null
        clearNetworkSelection()
        val previousNetwork=networkBitmap;networkBitmap=null
        if(previousNetwork!==networkOcrBitmap)previousNetwork?.recycle()
        networkAt=0;ui?.showNetworkFrame(null)
        steadyCleanup?.invoke();steadyCleanup=null
        currentRequest?.cancel();currentRequest=null;busy=false
        if (activePhotoId != 0L) {
            activePhotoId = 0L
            capturing = false
            photoTimeout?.let { statusHandler.removeCallbacks(it) }
            photoTimeout = null
        }
        ++adjustmentVersion; ++focusTicket; focusPending = false
        assistSettleUntil = 0L
        ++cameraGeneration
        cameraStarting = false
        running = false
        paused = false
        pending?.file?.delete()
        pending = null
        cameraProvider?.unbindAll()
        previewCase = null
        camera?.cameraInfo?.zoomState?.removeObservers(owner)
        camera = null
        capture = null
        analysisCase = null
        manualAfterStart = false
        ui?.overlay?.updateBoxes(emptyList(), 1, 1)
        stopSpeech()
        setStatus("已停止")
    }

    fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val generation = cameraGeneration
        val adjustment = adjustmentVersion
        val analysisInterval=if(capturing&&steadyCleanup!=null)250L else when(prefs.getInt("sensitivity",1)){0->1100L;2->550L;else->850L}
        if (!running || paused || cameraStarting || readingResults || now - lastAnalyzed < analysisInterval) { proxy.close(); return }
        if (android.os.SystemClock.elapsedRealtime() < assistSettleUntil) { proxy.close(); return }
        lastAnalyzed = now
        framesAnalyzed++
        val quality = try { detector.inspect(proxy) } catch (_: Exception) { proxy.close(); return }
        if (!quality.acceptable) {
            detector.antiShake=prefs.getBoolean("anti_shake",true)
            detector.missedFrame()
            runOnUiThread {
                if (running && generation == cameraGeneration && !paused && !capturing && (!busy||prefs.getBoolean("new_topic_priority",true))) {
                    val focusing = quality.reason.contains("不清晰") && !detector.holdingCandidate() && requestAutoFocus(blurred = true)
                    setStatus(if (focusing) "正在自动对焦，请保持稳定" else if (quality.reason.contains("不清晰") && prefs.getBoolean("auto_focus", true)) "画面不清晰，请靠近题目或增加光线" else quality.reason)
                }
            }
            proxy.close(); return
        }
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(input).addOnSuccessListener { found ->
            if (!running || generation != cameraGeneration || adjustment != adjustmentVersion) return@addOnSuccessListener
            val rotatedWidth = if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.width else proxy.height
            val rotatedHeight = if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.height else proxy.width
            val filtered = found.textBlocks.joinToString("\n") { it.text }
            observedText=filtered;observedAt=android.os.SystemClock.elapsedRealtime()
            val boxes = found.textBlocks.filter { it.text.length >= 3 }.mapNotNull { it.boundingBox }
            runOnUiThread { if (running && generation == cameraGeneration) ui?.overlay?.updateBoxes(boxes, rotatedWidth, rotatedHeight) }
            if(needsSceneAnchor&&detector.normalize(filtered).length>=5){detector.markQueued(filtered,quality.hash);needsSceneAnchor=false}
            if(capturing&&steadyCleanup!=null){
                if(detector.normalize(filtered).length>=20){
                    steadyChanges=if(detector.sameView(steadyAnchor,filtered))0 else steadyChanges+1
                    if(steadyChanges>=2){interruptCurrent(false);detector.resetSession();setStatus("取景已变化，已丢弃旧候选，重新确认新题")}
                }
                return@addOnSuccessListener
            }
            if (capturing || (busy||speakingIndex<speakingParts.size)&&!prefs.getBoolean("new_topic_priority",true)) return@addOnSuccessListener
            val readable = detector.normalize(filtered).length >= 40 && boxes.size >= 2
            if (!busy&&adjustForText(boxes, rotatedWidth, rotatedHeight, proxy.imageInfo.rotationDegrees, readable)) return@addOnSuccessListener
            detector.antiShake=prefs.getBoolean("anti_shake",true)
            val region=if(boxes.isEmpty())null else floatArrayOf(boxes.minOf{it.left}.toFloat()/rotatedWidth,boxes.minOf{it.top}.toFloat()/rotatedHeight,boxes.maxOf{it.right}.toFloat()/rotatedWidth,boxes.maxOf{it.bottom}.toFloat()/rotatedHeight)
            val changed = detector.observe(filtered, quality.hash,region)
            if (changed) runOnUiThread {
                if (running && generation == cameraGeneration && adjustment == adjustmentVersion && !paused && !readingResults && !capturing) {
                    interruptCurrent(false)
                    lastResult=null;resultMessage="正在解答新题" to "旧请求和旧播报已停止，完成一题即显示"
                    ui?.showMessage(resultMessage!!.first,resultMessage!!.second)
                    captureQuestion(filtered, quality.hash,steady=true)
                }
            }
            else runOnUiThread {
                if (running && generation == cameraGeneration && !paused && !busy && !capturing) {
                    setStatus(if (detector.normalize(filtered).length < 5) "未找到清晰文字，请对焦或缩放" else if(detector.known(filtered))"画面与已处理题目相似；若已换题，请点拍照识题" else "检测到可能的新题，正在确认文字；也可点拍照识题")
                }
            }
        }.addOnFailureListener { runOnUiThread { if (running && generation == cameraGeneration && !busy) setStatus("本地文字识别失败，可手动识别") } }
            .addOnCompleteListener { proxy.close() }
    }

    fun manualCapture(force: Boolean) {
        interruptCurrent(false)
        if (!running) { manualAfterStart = true; startScanning(); return }
        paused = false
        if (force) detector.resetSession()
        captureQuestion("", 0L)
    }

    fun captureQuestion(text: String, hash: Long, steady:Boolean=false) {
        if (capturing) return
        if(isNetworkCamera()){captureNetworkDirect(text);return}
        if(steady && prefs.getBoolean("anti_shake",true)){captureSteadyQuestion(text,hash);return}
        val useCase = capture ?: return
        val sceneText=text.ifBlank {observedText.takeIf {android.os.SystemClock.elapsedRealtime()-observedAt<2500}.orEmpty()}
        needsSceneAnchor=sceneText.isBlank()
        if(sceneText.isNotBlank())detector.markQueued(sceneText,hash)
        val raw = runCatching { File.createTempFile("raw_", ".jpg", cacheDir) }.getOrElse {
            setStatus("图片处理失败：无法创建照片文件"); return
        }
        capturing = true
        val id = ++requestId
        val generation = cameraGeneration
        activePhotoId = id
        setStatus("正在拍照")
        photoTimeout = Runnable {
            if (finishPhoto(id, generation)) {
                runCatching { camera?.cameraControl?.cancelFocusAndMetering() }
                setStatus("拍照失败：等待相机超时，请保持稳定后重试")
            }
        }.also { statusHandler.postDelayed(it, 12_000L) }
        try { useCase.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(), cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val cropped = preparePhoto(raw)
                    raw.delete()
                    runOnUiThread {
                        if (!finishPhoto(id, generation)) { cropped.delete(); return@runOnUiThread }
                        if (!running || id != requestId) { cropped.delete(); return@runOnUiThread }
                        val item = Captured(cropped, sceneText, hash, id)
                        latest = item
                        send(item)
                    }
                } catch (e: Exception) { raw.delete(); runOnUiThread { if (finishPhoto(id, generation)) setStatus("图片处理失败：${e.message}") } }
            }
            override fun onError(exception: ImageCaptureException) { raw.delete(); runOnUiThread { if (finishPhoto(id, generation)) setStatus("拍照失败：${exception.message}") } }
        }) } catch (e: Exception) {
            raw.delete()
            if (finishPhoto(id, generation)) setStatus("拍照失败：${e.message}")
        }
    }

    private fun finishPhoto(id: Long, generation: Long): Boolean {
        if (id != activePhotoId || generation != cameraGeneration) return false
        clearNetworkSelection()
        steadyCleanup?.invoke();steadyCleanup=null
        activePhotoId = 0L
        capturing = false
        photoTimeout?.let { statusHandler.removeCallbacks(it) }
        photoTimeout = null
        return true
    }

    /** Full-resolution candidates are kept on disk, never as a bitmap queue. */
    private fun captureSteadyQuestion(anchor:String,hash:Long) {
        val useCase=capture?:return
        val id=++requestId;val generation=cameraGeneration
        var policy:BestShotPolicy?=null
        steadyAnchor=anchor;steadyChanges=0
        var best:File?=null
        var ended=false
        capturing=true;activePhotoId=id
        steadyCleanup={ended=true;best?.delete();best=null}
        fun valid()=!ended && running && !paused && !closed && id==requestId && generation==cameraGeneration
        fun fail(message:String) {
            if(finishPhoto(id,generation)){
                detector.resetSession()
                lastAnalyzed=System.currentTimeMillis()+900
                setStatus(message)
            }
        }
        photoTimeout=Runnable{fail("选帧超时，请靠稳手机或点拍照识题")}.also{statusHandler.postDelayed(it,12_000L)}
        setStatus("已发现题目，正在选择清晰画面")
        fun deliver() {
            val selected=best
            if(!valid()||selected==null){fail("画面不清晰，请靠稳手机或增加光线");return}
            // Detach the winner before shared cleanup; every other file has
            // already been discarded. Late camera/OCR callbacks cannot send.
            best=null;steadyCleanup=null;ended=true
            if(!finishPhoto(id,generation)){selected.delete();return}
            detector.markQueued(anchor,hash)
            val item=Captured(selected,anchor,hash,id);latest=item
            setStatus("已选定清晰画面，正在提交")
            send(item)
        }
        fun takeNext() {
            if(!valid()){fail("取题已停止");return}
            val raw=runCatching{File.createTempFile("steady_raw_",".jpg",cacheDir)}.getOrElse{fail("无法创建取题图片");return}
            try{useCase.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(),cameraExecutor,object:ImageCapture.OnImageSavedCallback{
                override fun onImageSaved(output:ImageCapture.OutputFileResults) {
                    var prepared:File?=null
                    var bitmap:Bitmap?=null
                    try{
                        if(id!=requestId||generation!=cameraGeneration){raw.delete();return}
                        val photo=preparePhoto(raw);prepared=photo;raw.delete()
                        val score=PhotoSharpness.score(photo)
                        val selection=policy?:BestShotPolicy(android.os.SystemClock.elapsedRealtime()).also{policy=it}
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        BitmapFactory.decodeFile(photo.absolutePath,bounds)
                        val options=BitmapFactory.Options().apply{inSampleSize=if(maxOf(bounds.outWidth,bounds.outHeight)>2400)2 else 1}
                        val image=BitmapFactory.decodeFile(photo.absolutePath,options)?:error("无法读取候选图片")
                        bitmap=image
                        recognizer.process(InputImage.fromBitmap(image,0))
                            .addOnSuccessListener{found->
                                if(!valid()){photo.delete();return@addOnSuccessListener}
                                if(!detector.sameView(anchor,found.text)){
                                    photo.delete();fail("取景已变化或文字不完整，重新寻找题目");return@addOnSuccessListener
                                }
                                if(selection.consider(score)) {best?.delete();best=photo}else photo.delete()
                                if(selection.needsAnother(android.os.SystemClock.elapsedRealtime())){
                                    setStatus("正在避开模糊帧，选择清晰画面")
                                    takeNext()
                                }else deliver()
                            }.addOnFailureListener{
                                photo.delete();if(valid())fail("候选画面文字检查失败，请重新取题")
                            }.addOnCompleteListener{image.recycle()}
                    }catch(e:Exception){
                        raw.delete();prepared?.delete();bitmap?.recycle()
                        runOnUiThread{if(valid())fail("选帧失败，请点拍照识题重试")}
                    }
                }
                override fun onError(exception:ImageCaptureException){raw.delete();runOnUiThread{if(valid())fail("拍照失败，请靠稳手机后重试")}}
            })}catch(e:Exception){raw.delete();fail("相机暂不可用，请重新开始识题")}
        }
        takeNext()
    }

    fun preparePhoto(raw: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(raw.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片，请选择有效的照片或截图" }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 4096) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(raw.absolutePath, options) ?: error("无法读取照片")
        val orientation = ExifInterface(raw.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val rotated = if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true).also { if (it !== bitmap) bitmap.recycle() }
        val scale = minOf(1f, 2400f / rotated.width, 3200f / rotated.height)
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt().coerceAtLeast(1), (rotated.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== rotated) rotated.recycle() } else rotated
        val output = File.createTempFile("question_", ".jpg", cacheDir)
        for (quality in listOf(84, 72, 60)) {
            FileOutputStream(output).use { resized.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (output.length() <= 8L * 1024 * 1024) break
        }
        resized.recycle()
        if (output.length() > 8L * 1024 * 1024) { output.delete(); error("图片过大，请缩小取景范围") }
        return output
    }

    fun send(item: Captured) {
        val operation=SolveRequest()
        currentRequest?.cancel();currentRequest=operation
        requestsSent++
        busy = true
        setStatus("识别中，请稍候")
        val url = prefs.getString("base_url", "").orEmpty()
        val token = account.token()
        val userId = account.id()
        val previewFolder = account.previewFolder()
        val fast=prefs.getBoolean("fast_answers",true)
        val streaming=prefs.getBoolean("stream_answers",true)
        val optimize=prefs.getBoolean("optimize_upload",true)
        var streamedCount=0
        var partialResults=emptyList<SolveResult>()
        if (token.isBlank()) { busy = false; onAuthExpired?.invoke(); return }
        networkExecutor.execute {
            if (operation.cancelled || item.id != requestId || previewFolder != account.previewFolder()) { item.file.delete(); return@execute }
            val response = runCatching {
                val preview = runCatching {
                        val folder = previewFolder.apply { mkdirs() }
                        val file = File(folder, "${java.util.UUID.randomUUID()}.jpg")
                        item.file.copyTo(file)
                        val previous = lastResult?.questions?.map { it.photoPath }?.toSet().orEmpty()
                        folder.listFiles()?.filter { it != file && it.absolutePath !in previous }?.forEach { it.delete() }
                        file.absolutePath
                }.getOrDefault("")
                val upload=if(optimize)PhotoUpload.optimize(item.file,cacheDir,fast) else item.file
                try {
                    val cancelled={operation.cancelled || closed || userId!=account.id() || previewFolder!=account.previewFolder() || item.id!=requestId}
                    val result=try {
                        SolveApi.solveBatch(url,token,upload,fast,streaming,onQuestion={question,index->
                            val ready=question.copy(photoPath=preview)
                            runOnUiThread {
                                if(!cancelled()&&(item.uploaded||running)) {
                                    val previous=if(streamedCount==0)emptyList() else lastResult?.questions.orEmpty()
                                    streamedCount=index
                                    val partial=SolveBatch(previous+ready,"")
                                    partialResults=partial.questions
                                    lastResult=partial;resultMessage=null;ui?.showBatch(partial)
                                    setStatus("已收到第 $index 题答案，继续解答中")
                                    if((item.uploaded||!paused)&&!readingResults&&canSpeak())speak(SolveBatch(listOf(ready),""),append=index>1,startNumber=index)
                                }
                            }
                        },cancelled=cancelled,request=operation)
                    }catch(partial:PartialSolveException){partial.partial}
                    if(result.questions.isEmpty()&&preview.isNotBlank())File(preview).delete()
                    result.copy(questions=result.questions.map {it.copy(photoPath=preview)})
                }finally{if(upload!==item.file)upload.delete()}
            }
            runOnUiThread {
                // A password change can rotate the token without changing ownership.
                // A stop/logout cancels the request ID, and another server/account has a different folder.
                if (userId != account.id() || previewFolder != account.previewFolder() || operation.cancelled || item.id != requestId) {
                    if(userId==account.id()&&previewFolder==account.previewFolder()&&partialResults.isNotEmpty())runCatching {saveHistory(partialResults,item.file)}
                    item.file.delete();return@runOnUiThread
                }
                if(currentRequest===operation)currentRequest=null
                busy = false
                if (closed || (!item.uploaded && !running)) { item.file.delete(); setStatus("已停止"); return@runOnUiThread }
                val newer = pending
                if (newer != null) {
                    pending = null
                    item.file.delete()
                    send(newer)
                    return@runOnUiThread
                }
                response.onSuccess { result ->
                    if (result.questions.isNotEmpty()) {
                        detector.markSubmitted(if (item.text.isNotBlank()) item.text else result.questions.joinToString("\n") { it.full }, item.hash, result.questions.joinToString("\n") { it.full })
                        lastResult = result
                        resultMessage = null
                        ui?.showBatch(result)
                        val timing=if(result.elapsedMs>0)" · 首答 ${String.format(Locale.US,"%.1f",result.firstAnswerMs/1000f)} 秒 / 全部 ${String.format(Locale.US,"%.1f",result.elapsedMs/1000f)} 秒" else ""
                        setStatus(if(result.interrupted)"连接中断，已保留 ${result.questions.size} 道完整题目，可手动重新识别" else "已解答 ${result.questions.size} 道完整题目$timing")
                        runCatching {saveHistory(result.questions, item.file)}.onFailure {
                            Toast.makeText(this,"答案已生成，但历史保存失败，请检查存储空间",Toast.LENGTH_LONG).show()
                        }
                        if (streamedCount==0 && (item.uploaded || !paused) && !readingResults && !prefs.getBoolean("muted", false)) speak(result)
                    } else {
                        detector.clearQueued(item.text)
                        lastResult = null
                        resultMessage = "未找到完整题目" to result.uncertainty
                        ui?.showMessage("未找到完整题目", result.uncertainty)
                        setStatus("未找到完整题目，请调整距离或缩放")
                        if ((item.uploaded || !paused) && !readingResults && !prefs.getBoolean("muted", false)) {
                            stopSpeech()
                            speakText("未找到完整题目，请调整距离或缩放", "unreadable")
                        }
                    }
                }.onFailure { e ->
                    detector.clearQueued(item.text)
                    if (e is ApiException && e.status == 401) {
                        stopAll(); account.clear(); lastResult = null
                        setStatus("登录已失效，请重新登录")
                        onAuthExpired?.invoke()
                    } else {
                        resultMessage="本次识别未完成" to "${e.message?:"网络异常"}；不会自动重复请求，可点拍照识题重试"
                        ui?.showMessage(resultMessage!!.first,resultMessage!!.second)
                        setStatus("识别失败：${e.message ?: "网络异常"}；可点重新识别")
                    }
                }
                item.file.delete()
            }
        }
    }

    fun saveHistory(results: List<SolveResult>, photo: File) {
        if (!prefs.getBoolean("save_history", true)) return
        HistoryRepository.withStorageLock {
        val fresh = JSONArray()
        var savedPhoto = ""
        if (prefs.getBoolean("save_photos", false)) {
            val folder = account.photoFolder().apply { mkdirs() }
            val saved = File(folder, "${java.util.UUID.randomUUID()}.jpg")
            photo.copyTo(saved, overwrite = true)
            savedPhoto = saved.absolutePath
        }
        for (result in results) {
            val entry = JSONObject().put("time", System.currentTimeMillis()).put("brief", result.brief).put("full", result.full).put("answer", result.answer).put("solution", result.solution).put("uncertainty", result.uncertainty).put("fast",result.fast)
            if (savedPhoto.isNotBlank()) entry.put("photo", savedPhoto)
            fresh.put(entry)
        }
        HistoryRepository.account(this).append(fresh)
        }
    }

    fun toggleAnswerDetail() {
        val enabled=!prefs.getBoolean("speak_answer_detail",true)
        prefs.edit().putBoolean("speak_answer_detail",enabled).apply()
        stopSpeech();ui?.setAnswerDetail(enabled)
        Toast.makeText(this,if(enabled)"将播报答案具体内容" else "仅播报选择题字母；其他题提示查看屏幕",Toast.LENGTH_SHORT).show()
    }

    /** Invalidates late callbacks before canceling IO; the camera stays available for the next scene. */
    fun interruptCurrent(pause:Boolean=true) {
        ++requestId
        clearNetworkSelection()
        steadyCleanup?.invoke();steadyCleanup=null
        currentRequest?.cancel();currentRequest=null
        busy=false;capturing=false;activePhotoId=0L;needsSceneAnchor=false
        photoTimeout?.let {statusHandler.removeCallbacks(it)};photoTimeout=null
        pending?.file?.delete();pending=null
        stopSpeech()
        if(pause){
            paused=running
            if(lastResult==null){resultMessage="已中断当前解答" to "切换题目后点继续；同题重做请点拍照识题";ui?.showMessage(resultMessage!!.first,resultMessage!!.second)}
            setStatus("已中断当前解答与播报，切换题目后点继续；同题可手动重新识别")
        }
    }

    fun explain(question:SolveResult,complete:(Result<SolveResult>)->Unit) {
        if(busy||capturing){complete(Result.failure(java.io.IOException("请等待当前识题完成后再生成解析")));return}
        val source=QuestionImages.resolve(this,question.photoPath)
        if(source==null){complete(Result.failure(java.io.IOException("原题图已不存在，请重新上传；历史题目需开启保存照片后才可补充解析")));return}
        val url=prefs.getString("base_url","").orEmpty();val token=account.token();val userId=account.id();val folder=account.previewFolder();val ticket=++requestId
        val operation=SolveRequest();currentRequest=operation
        busy=true;setStatus("正在按需生成详细解析（计一次调用）")
        networkExecutor.execute {
            val result=runCatching {
                val copy=File.createTempFile("detail_",".jpg",cacheDir)
                try{if(operation.cancelled)throw java.io.IOException("请求已取消");source.copyTo(copy,overwrite=true);SolveApi.explain(url,token,copy,question.full,operation).copy(photoPath=question.photoPath)}finally{copy.delete()}
            }
            runOnUiThread {
                if(closed||operation.cancelled||userId!=account.id()||folder!=account.previewFolder()||ticket!=requestId){complete(Result.failure(java.io.IOException("解析已中断")));return@runOnUiThread}
                if(currentRequest===operation)currentRequest=null
                busy=false
                result.onSuccess {detail->
                    lastResult=lastResult?.let {batch->batch.copy(questions=batch.questions.map {if(it.full==question.full&&it.answer==question.answer)detail else it})}
                    val saved=runCatching {HistoryRepository.account(this).updateDetails(question,detail)}
                    setStatus(if(saved.isSuccess)"详细解析已生成，已同步当前账号的对应历史" else "详细解析已生成，但历史保存失败，请检查存储空间")
                }.onFailure {setStatus("详细解析失败：${it.message}")}
                complete(result)
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.SIMPLIFIED_CHINESE
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
    }

    fun speak(result: SolveBatch, append:Boolean=false, startNumber:Int=0) {
        if (!canSpeak()) {
            stopSpeech()
            Toast.makeText(this, if (prefs.getBoolean("muted", false)) "当前已静音，请先恢复声音" else "仅蓝牙耳机播报已开启，请连接耳机并启用媒体音频", Toast.LENGTH_SHORT).show()
            return
        }
        if(append&&deferredSpeech!=null){deferredSpeech=deferredSpeech!!.copy(questions=deferredSpeech!!.questions+result.questions);return}
        if (!append && !prefs.getBoolean("interrupt", true) && speakingIndex < speakingParts.size && tts?.isSpeaking == true) { deferredSpeech = result; return }
        val parts = try {
            result.questions.flatMapIndexed { index, question ->
                listOf("${if(startNumber>0) "第${startNumber+index}题" else if (result.questions.size > 1) "第${index + 1}题" else "题目"}：${MarkdownContent.speech(question.brief)}", AnswerSpeech.text(question.answer,question.full,prefs.getBoolean("speak_answer_detail",true))) +
                    if (prefs.getBoolean("speak_solution", false)&&prefs.getBoolean("speak_answer_detail",true)) listOf("解题思路：${MarkdownContent.speech(question.solution)}") else emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.w("ReaderEngine", "Speech text conversion failed: ${e.javaClass.simpleName}")
            Toast.makeText(this, "语音文本处理失败，请查看屏幕上的答案", Toast.LENGTH_LONG).show()
            return
        }
        if(append&&speakingParts.isNotEmpty()) {
            val waiting=speakingIndex>=speakingParts.size
            speakingParts=speakingParts+parts
            if(waiting)speakNext()
            return
        }
        stopSpeech()
        speakingParts=parts
        speakingIndex = 0
        val generation = ++speechGeneration
        tts?.setSpeechRate(prefs.getInt("speech_rate", 100) / 100f)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { runOnUiThread { if (utteranceId == "${generation}_${speakingIndex}" && generation == speechGeneration) { speakingIndex++; speakNext() } } }
            override fun onError(utteranceId: String?) {}
        })
        speakNext()
    }

    fun speakNext() {
        if (!canSpeak()) { stopSpeech(); return }
        if (speakingIndex >= speakingParts.size) {
            onStateChanged?.invoke()
            deferredSpeech?.let { val next = it; deferredSpeech = null; speak(next) }
            return
        }
        if (speakText(speakingParts[speakingIndex], "${speechGeneration}_${speakingIndex}") != TextToSpeech.SUCCESS) stopSpeech()
        onStateChanged?.invoke()
    }

    fun stopSpeech() { cancelStatusSpeech(); statusSpeechActive = false; speechGeneration++; speakingParts = emptyList(); deferredSpeech = null; tts?.stop(); onStateChanged?.invoke() }
}
