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

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private lateinit var accounts: AccountUi
    private lateinit var ui: ReaderUi
    private val preview get() = ui.preview
    private val overlay get() = ui.overlay
    private lateinit var engine: ReaderEngine
    private var service: ReaderService? = null
    private var bound = false
    private var readingOverlay = false
    private var preferencesSheet:SettingsSheet?=null
    private fun setReadingOverlay(value:Boolean){readingOverlay=value||preferencesSheet?.dialog?.isShowing==true||(::ui.isInitialized&&ui.hasReadingPage());if(::engine.isInitialized)engine.readingResults=readingOverlay}
    private var pendingPhoto: android.net.Uri? = null
    private var pendingManualCapture: Boolean? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bound || AccountStore(this@MainActivity).token().isBlank()) return
            service = (binder as ReaderService.LocalBinder).service
            engine = service!!.engine
            engine.onAuthExpired = { accounts.signOut("登录已失效，请重新登录") }
            buildUi()
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                engine.attachUi(ui)
                engine.readingResults = readingOverlay
                syncBackgroundMode()
            }
            pendingPhoto?.let { pendingPhoto = null; engine.importPhoto(it) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val manual = pendingManualCapture
        pendingManualCapture = null
        if (granted) {
            syncBackgroundMode()
            if (manual != null) engine.manualCapture(manual) else engine.startScanning()
        } else engine.setStatus("需要摄像头权限才能识题")
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) syncBackgroundMode()
        else {
            prefs.edit().putBoolean("background_enabled", false).apply()
            Toast.makeText(this, "后台相机运行需要摄像头权限，开关已关闭", Toast.LENGTH_LONG).show()
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "通知未授权，可在系统运行中的应用里停止后台任务", Toast.LENGTH_LONG).show()
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (AccountStore(this).token().isBlank()) { pendingPhoto = null; return@registerForActivityResult }
        if (!::engine.isInitialized) { pendingPhoto = uri; return@registerForActivityResult }
        if (uri == null) engine.setStatus(if (engine.running) "已暂停，未选择图片，点击继续恢复相机" else "等待开始。也可上传题目图片。")
        else engine.importPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true){
            override fun handleOnBackPressed(){
                if(::ui.isInitialized&&ui.backToHome())return
                isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true
            }
        })
        window.statusBarColor = AppPalette.background
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        readDeploymentDefaults()
        accounts = AccountUi(this,
            onAuthenticated = {
                if (!bound) {
                    setContentView(TextView(this).apply { text = "正在连接识题服务…"; gravity = Gravity.CENTER })
                    bound = bindService(Intent(this, ReaderService::class.java), connection, Context.BIND_AUTO_CREATE)
                }
            },
            onSignedOut = {
                preferencesSheet?.dialog?.dismiss()
                if (::engine.isInitialized) { engine.resetAccount(); engine.onAuthExpired = null }
                service?.disableBackground()
                if (bound) { unbindService(connection); bound = false }
                service = null; readingOverlay = false; pendingPhoto = null; pendingManualCapture = null
            },
            onReading = { setReadingOverlay(it) })
        accounts.start()
    }

    override fun onStart() {
        super.onStart()
        if (::accounts.isInitialized && bound && AccountStore(this).token().isBlank()) { accounts.signOut(); return }
        if (::engine.isInitialized && ::ui.isInitialized && service != null) {
            engine.attachUi(ui)
            engine.readingResults = readingOverlay
            syncBackgroundMode()
        }
    }

    override fun onStop() {
        if (::ui.isInitialized) ui.cancelUnlock()
        if (::engine.isInitialized) {
            engine.detachUi()
            if (!prefs.getBoolean("background_enabled", false)) engine.suspendForegroundOnly()
        }
        super.onStop()
    }

    override fun onDestroy() {
        preferencesSheet?.dialog?.dismiss()
        if(::ui.isInitialized)ui.close()
        if (::accounts.isInitialized) accounts.close()
        if (::engine.isInitialized) engine.onAuthExpired = null
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onDestroy()
    }

    private fun syncBackgroundMode() {
        val active = service ?: return
        if (!prefs.getBoolean("background_enabled", false)) { active.disableBackground(); return }
        if (!engine.isNetworkCamera() && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            backgroundPermission.launch(Manifest.permission.CAMERA); return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, ReaderService::class.java))
            active.enableBackground()
        }.onFailure {
            prefs.edit().putBoolean("background_enabled", false).apply()
            active.disableBackground()
            Toast.makeText(this, "后台运行启动失败，请保持应用在前台后重试", Toast.LENGTH_LONG).show()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notification_prompted", false) && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("notification_prompted", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestStart() {
        if(engine.isNetworkCamera()){syncBackgroundMode();engine.startScanning();return}
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            syncBackgroundMode(); engine.startScanning()
        } else permission.launch(Manifest.permission.CAMERA)
    }
    fun refreshSourceBackground(){service?.disableBackground();syncBackgroundMode()}

    private fun manualCapture(force: Boolean) {
        if(engine.isNetworkCamera()){syncBackgroundMode();engine.manualCapture(force);return}
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingManualCapture = force
            requestStart()
            return
        }
        syncBackgroundMode()
        engine.manualCapture(force)
    }

    private fun choosePhoto() {
        if (!engine.prepareUpload()) return
        runCatching { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .onFailure { engine.setStatus("图片选择失败：${it.message}") }
    }

    private fun readDeploymentDefaults() {
        prefs.edit().remove("token").apply()
        if (prefs.contains("base_url")) return
        runCatching {
            val props = java.util.Properties()
            assets.open("deploy.properties").use { props.load(it) }
            prefs.edit().putString("base_url", props.getProperty("base_url", "")).apply()
        }
    }

    private fun buildUi() {
        if(::ui.isInitialized)ui.close()
        ui = ReaderUi(this,
            onUpload = { choosePhoto() }, onMute = { engine.toggleMute() },
            onPrimary = { if(engine.busy||engine.capturing)engine.interruptCurrent() else if (!engine.running) requestStart() else engine.togglePause() },
            onCapture = { manualCapture(false) }, onStop = { engine.stopScanning() }, onRetry = { manualCapture(true) },
            onReplay = { engine.speak(it) }, onStopSpeech = { engine.stopSpeech(); Toast.makeText(this, "已停止播报", Toast.LENGTH_SHORT).show() },
            onSettings = { showSettings() }, onHistory = { showHistory() }, onZoom = { engine.changeZoom(it) },
            onFlipCamera = { engine.flipCamera() },
            onTouchLockChanged = { prefs.edit().putBoolean("touch_locked", it).apply() },
            onSpeakQuestion = { engine.speak(SolveBatch(listOf(it), "")) },onExplainQuestion={question,done->engine.explain(question,done)},onAnswerDetail={engine.toggleAnswerDetail()}, onReading = { setReadingOverlay(it) },onAccount={accounts.showAccount()})
        setContentView(ui.build())
        ui.setTouchLocked(prefs.getBoolean("touch_locked", false))
        ui.setMuted(prefs.getBoolean("muted", false))
        ui.setAnswerDetail(prefs.getBoolean("speak_answer_detail",true))
        val gesture = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { engine.changeZoom(detector.scaleFactor); return true }
        })
        val touch = View.OnTouchListener { _, event -> gesture.onTouchEvent(event); true }
        preview.setOnTouchListener(touch)
        overlay.setOnTouchListener(touch)
    }

    private fun showHistory() {
        ui.showHistory(HistoryRepository.account(this))
    }

    private fun showSettings() {
        if(preferencesSheet?.dialog?.isShowing==true)return
        val sheet=SettingsSheet(this,"使用偏好","按你的习惯定制识题、取景和语音体验。修改后点击底部保存。")
        preferencesSheet=sheet
        fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
        val teal = AppPalette.primaryStrong
        var panel = sheet.card("快捷入口")
        fun label(value: String, section: Boolean = false): TextView {
            val view = TextView(this).apply {
                text = value; textSize = if (section) 14f else 12f
                setTextColor(if (section) teal else AppPalette.muted)
                setPadding(0, dp(if (section) 8 else 10), 0, dp(8))
                if (section) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            if(section && !value.contains("›")) panel=sheet.card(value)
            else panel.addView(view)
            return view
        }
        fun toggle(title: String, key: String, default: Boolean): Switch {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(62) }
            row.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(AppPalette.ink); setPadding(0,dp(10),dp(16),dp(10)) }, LinearLayout.LayoutParams(0, -2, 1f))
            val control = Switch(this).apply { isChecked = prefs.getBoolean(key, default); contentDescription = title }
            row.addView(control)
            row.setOnClickListener { if (control.isEnabled) control.isChecked = !control.isChecked }
            panel.addView(row)
            return control
        }
        label("账号与个人模型  ›", true).apply { minHeight = dp(52); setOnClickListener { accounts.showAccount() } }
        label("答题速度", true)
        val fastAnswers=toggle("极速答题 · 简短答案，解析按需生成", "fast_answers",true)
        val streamAnswers=toggle("逐题返回 · 完成一题就显示与播报", "stream_answers",true)
        val newTopicPriority=toggle("新题优先 · 自动中断旧解答和播报", "new_topic_priority",true)
        label("同一题不自动重复提交；解题期间继续本地检测，确认新题后立即切换。中断按钮始终可用，同题重做可点拍照识题。")
        val optimizeUpload=toggle("优化上传图片 · 裁白边与适度压缩", "optimize_upload",true)
        label("不支持流式 JSON 的模型可关闭逐题返回；小字或复杂附图识别不佳时可关闭图片优化。极速模式保留原题图，详细解析另计一次调用。")
        label("自动取景", true)
        label("网络摄像头 / Action 4 接流与取景  ›",true).apply{minHeight=dp(52);setOnClickListener{
            NetworkCameraUi(this@MainActivity,engine){setReadingOverlay(it)}.show()
        }}
        val autoFocus = toggle("自动对焦题目区域", "auto_focus", true)
        val autoZoom = toggle("自动调整取景大小", "auto_zoom", true)
        val antiShake = toggle("防抖取题 · 容忍短暂抖动并选择清晰照片", "anti_shake", true)
        label("短暂漏字或模糊不立即清空确认进度；自动取题时在本机短时选帧，只提交一张图片。大幅移动会重新确认，手动拍照保持直接拍摄。")
        panel.addView(TextView(this).apply {
            text = "根据文字区域对焦、适度缩放；无文字时小幅搜索后回退。保留已检测到的多题文字，手动缩放后暂停自动变焦12秒。"
            textSize = 12f; setTextColor(AppPalette.muted); setPadding(0, 0, 0, dp(8))
        })
        label("后台运行", true)
        val background = toggle("后台与锁屏运行", "background_enabled", false)
        panel.addView(TextView(this).apply {
            text = "开启后通过常驻通知继续识题和播报。请先在主界面启动识题，再锁屏；会增加耗电。通知可停止任务。"
            textSize = 12f; setTextColor(AppPalette.muted); setPadding(0, 0, 0, dp(8))
        })
        val battery = label("打开系统后台省电设置  ›")
        battery.minHeight = dp(48)
        battery.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { Toast.makeText(this, "请在系统设置中允许本应用后台运行", Toast.LENGTH_LONG).show() }
        }
        label("语音播报", true)
        val answerDetail=toggle("播报答案具体内容", "speak_answer_detail",true)
        label("关闭后选择题仅播报选项字母，其他题仅提示查看屏幕，也不播报解析。主界面“详读”按钮可快速切换。")
        val bluetoothOnly = toggle("仅连接蓝牙耳机时播报", "bluetooth_only", false)
        panel.addView(TextView(this).apply {
            text = "未连接蓝牙媒体音频时不发声；断开后停止播报，重连不补播。适用于答案、重播和状态提示。"
            textSize = 12f; setTextColor(AppPalette.muted); setPadding(0, 0, 0, dp(8))
        })
        val statusVoice = toggle("播报摄像头与处理状态", "speak_status", false)
        panel.addView(TextView(this).apply {
            text = "提示相机启动、寻找题目、解题进度与异常；不打断答案播报，一键静音同样生效。"
            textSize = 12f; setTextColor(AppPalette.muted); setPadding(0, 0, 0, dp(8))
        })
        val steps = toggle("同时讲解解题思路", "speak_solution", false)
        val interrupt = toggle("新答案就绪后打断当前播报", "interrupt", true)
        val rateLabel = label("语速  ${prefs.getInt("speech_rate", 100) / 100f}×")
        val speechRate = SeekBar(this).apply { max = 150; progress = prefs.getInt("speech_rate", 100) - 50 }
        panel.addView(speechRate)
        val volumeLabel = label("音量  ${prefs.getInt("volume", 100)}%")
        val volume = SeekBar(this).apply { max = 100; progress = prefs.getInt("volume", 100) }
        panel.addView(volume)
        fun watch(seek: SeekBar, update: (Int) -> Unit) { seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { update(progress) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }) }
        watch(speechRate) { rateLabel.text = String.format(Locale.US, "语速  %.2f×", (it + 50) / 100f) }
        watch(volume) { volumeLabel.text = "音量  $it%" }
        label("识题偏好", true)
        label("换题灵敏度")
        val sensitivity = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("低 · 等待画面更稳定", "中 · 适合日常使用", "高 · 更快识别新题"))
            setSelection(prefs.getInt("sensitivity", 1))
        }
        panel.addView(sensitivity)
        label("历史与照片", true)
        val save = toggle("保存题目与答案", "save_history", true)
        val photos = toggle("同时保留题目照片", "save_photos", false)
        photos.isEnabled = save.isChecked
        save.setOnCheckedChangeListener { _, checked -> photos.isEnabled = checked }
        panel=sheet.card("服务连接")
        val connection = label("连接设置  ›", true).apply { minHeight = dp(52); isClickable = true }
        val connectionPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        connectionPanel.addView(TextView(this).apply { text = "当前服务：${prefs.getString("base_url", "")}\n更改后端地址请先退出账号，再在登录页填写。模型地址与 Key 请进入“账号与个人模型”。"; textSize = 12f; setTextColor(Color.rgb(112, 128, 136)); setPadding(0, 0, 0, dp(10)) })
        connectionPanel.addView(Button(this).apply {text="局域网直连 / 更换后端";setOnClickListener {accounts.showConnectionSwitch()}})
        panel.addView(connectionPanel)
        connection.setOnClickListener { val open = connectionPanel.visibility == View.VISIBLE; connectionPanel.visibility = if (open) View.GONE else View.VISIBLE; connection.text = if (open) "连接设置  ›" else "连接设置  ⌄" }
        val dialog=sheet.dialog
        sheet.footer("保存偏好") {
            prefs.edit().putBoolean("auto_focus", autoFocus.isChecked).putBoolean("auto_zoom", autoZoom.isChecked)
                .putBoolean("anti_shake",antiShake.isChecked)
                .putBoolean("fast_answers",fastAnswers.isChecked).putBoolean("stream_answers",streamAnswers.isChecked).putBoolean("optimize_upload",optimizeUpload.isChecked).putBoolean("speak_answer_detail",answerDetail.isChecked)
                .putBoolean("new_topic_priority",newTopicPriority.isChecked)
                .putBoolean("background_enabled", background.isChecked).putBoolean("bluetooth_only", bluetoothOnly.isChecked).putBoolean("speak_status", statusVoice.isChecked)
                .putBoolean("speak_solution", steps.isChecked).putBoolean("save_history", save.isChecked).putBoolean("save_photos", save.isChecked && photos.isChecked)
                .putBoolean("interrupt", interrupt.isChecked).putInt("sensitivity", sensitivity.selectedItemPosition)
                .putInt("speech_rate", speechRate.progress + 50).putInt("volume", volume.progress).apply()
            engine.settingsChanged()
            ui.setAnswerDetail(answerDetail.isChecked)
            syncBackgroundMode()
            Toast.makeText(this, "偏好已保存", Toast.LENGTH_SHORT).show()
        }
        readingOverlay = true
        engine.readingResults = true
        dialog.setOnDismissListener { if(preferencesSheet===sheet)preferencesSheet=null;setReadingOverlay(false) }
        sheet.show()
    }
}

class FrameOverlay(context: Context) : View(context) {
    private var fitCenter=false
    fun setFitCenter(value:Boolean){fitCenter=value;invalidate()}
    private var mirrored = false
    fun setMirrored(value: Boolean) { mirrored = value; invalidate() }
    private var boxes: List<Rect> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 100, 150, 255); style = Paint.Style.STROKE; strokeWidth = 1.2f * resources.displayMetrics.density }
    fun updateBoxes(found: List<Rect>, width: Int, height: Int) {
        boxes = found
        imageWidth = width.coerceAtLeast(1)
        imageHeight = height.coerceAtLeast(1)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        if (mirrored) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        val scale = if(fitCenter)minOf(width.toFloat()/imageWidth,height.toFloat()/imageHeight)else maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val dx = (width - imageWidth * scale) / 2f
        val dy = (height - imageHeight * scale) / 2f
        boxes.forEach { rect ->
            canvas.drawRoundRect(rect.left * scale + dx, rect.top * scale + dy, rect.right * scale + dx, rect.bottom * scale + dy, 12f, 12f, paint)
        }
        canvas.restore()
    }
}

