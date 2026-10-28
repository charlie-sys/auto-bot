package com.autobot.reader

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.*
import androidx.camera.view.PreviewView

/** Native screen layout and result presentation, independent of the camera session. */
class ReaderUi(
    private val activity: android.app.Activity,
    private val onUpload: () -> Unit,
    private val onMute: () -> Unit,
    private val onPrimary: () -> Unit,
    private val onCapture: () -> Unit,
    private val onStop: () -> Unit,
    private val onRetry: () -> Unit,
    private val onReplay: (SolveBatch) -> Unit,
    private val onStopSpeech: () -> Unit,
    private val onSettings: () -> Unit,
    private val onHistory: () -> Unit,
    private val onZoom: (Float) -> Unit,
    private val onFlipCamera: () -> Unit,
    private val onTouchLockChanged: (Boolean) -> Unit,
    private val onSpeakQuestion: (SolveResult) -> Unit,
    private val onExplainQuestion: (SolveResult,(Result<SolveResult>)->Unit) -> Unit,
    private val onAnswerDetail: () -> Unit,
    private val onReading: (Boolean) -> Unit,
    private val onAccount: () -> Unit,
) {
    val preview = PreviewView(activity).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    val overlay = FrameOverlay(activity)
    private val networkPreview=ImageView(activity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.GONE;setBackgroundColor(Color.rgb(20,33,41))}
    private val ink = AppPalette.ink
    private val muted = AppPalette.muted
    private val teal = AppPalette.primaryStrong
    private val softTeal = AppPalette.soft
    private val surfaceBackground = AppPalette.background
    private val border = AppPalette.line
    private lateinit var primary: TextView
    private lateinit var captureButton: TextView
    private lateinit var uploadButton: TextView
    private val muteButtons = mutableListOf<TextView>()
    private val answerDetailButtons = mutableListOf<TextView>()
    private var isMuted = false
    private var answerDetailEnabled=true
    private lateinit var sessionBadge: TextView
    private lateinit var cameraHint: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var mouthHint:TextView
    private var mouthMode=false
    private var mouthFront=true
    private lateinit var progress: ProgressBar
    private lateinit var statusDot: View
    private lateinit var zoomText: TextView
    private lateinit var flipButton: TextView
    private lateinit var cameraMode: TextView
    private var cameraSwitchAvailable = true
    private var cameraStarting = false
    private lateinit var interactiveContent: LinearLayout
    private lateinit var touchShield: FrameLayout
    private lateinit var unlockButton: TextView
    private val unlockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var unlockTask: Runnable? = null
    private lateinit var countText: TextView
    private lateinit var expandButton: TextView
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private var batch: SolveBatch? = null
    private var readingDepth = 0
    private var currentTab=0
    private lateinit var homePage:LinearLayout
    private lateinit var libraryPage:LinearLayout
    private lateinit var profilePage:LinearLayout
    private val navLabels=mutableListOf<TextView>()
    private val navIcons=mutableListOf<AppGlyph>()
    private val navItems=mutableListOf<LinearLayout>()
    private fun enterReading() { readingDepth++; onReading(true) }
    private fun leaveReading() { readingDepth = maxOf(0, readingDepth - 1); onReading(readingDepth > 0||currentTab!=0) }
    private val markdown by lazy { QuestionMarkdown(activity) }

    private fun markdownText(value: String, size: Float = 14f, color: Int = ink, photoPath: String = "") =
        text("", size, color).apply { markdown.set(this, value, photoPath) }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int, radius: Int = 16, outline: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (outline != null) setStroke(dp(1), outline)
    }
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun action(value: String, filled: Boolean = false, dark: Boolean = false, listener: () -> Unit) = text(
        value, 13f, if (filled || dark) Color.WHITE else teal, true
    ).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minWidth=0;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dp(10), dp(10), dp(10), dp(10))
        val normal = shape(if (filled) teal else if (dark) Color.argb(175, 20, 33, 41) else Color.WHITE, 14, if (!filled && !dark) border else null)
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 0, 0, 0)), normal, null)
        contentDescription = value
        setOnClickListener { listener() }
    }
    private fun LinearLayout.addGap(size: Int = 10) { addView(View(activity), LinearLayout.LayoutParams(dp(size), dp(size))) }
    private fun margins(width: Int, height: Int, weight: Float = 0f, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(width, height, weight).apply {
        setMargins(dp(16), dp(top), dp(16), dp(bottom))
    }

    fun build(): View {
        val outer=column().apply{setBackgroundColor(surfaceBackground)}
        interactiveContent = outer
        val pageHost=FrameLayout(activity)
        val root=column();homePage=root
        libraryPage=column().apply{visibility=View.GONE}
        profilePage=column().apply{visibility=View.GONE}
        pageHost.addView(root,FrameLayout.LayoutParams(-1,-1));pageHost.addView(libraryPage,FrameLayout.LayoutParams(-1,-1));pageHost.addView(profilePage,FrameLayout.LayoutParams(-1,-1))
        outer.addView(pageHost,LinearLayout.LayoutParams(-1,0,1f))
        val header = row().apply { setPadding(dp(20), dp(10), dp(20), dp(12)) }
        val mark = AppGlyph(activity,"scan",Color.WHITE).apply { background = shape(AppPalette.primary, 12);setPadding(dp(9),dp(9),dp(9),dp(9)) }
        header.addView(mark, LinearLayout.LayoutParams(dp(38), dp(38)))
        val heading = column().apply { setPadding(dp(12), 0, 0, 0) }
        heading.addView(text("识题助手", 20f, ink, true))
        heading.addView(text("拍下问题，让答案更清晰", 11f, muted), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("使用偏好") { onSettings() }.apply{background=shape(Color.WHITE,12,border)})
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val camera = FrameLayout(activity).apply {
            background = shape(AppPalette.camera, 24)
            clipToOutline = true
        }
        camera.addView(preview, FrameLayout.LayoutParams(-1, -1))
        camera.addView(networkPreview,FrameLayout.LayoutParams(-1,-1))
        camera.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        cameraHint = column().apply { gravity = Gravity.CENTER; setBackgroundColor(Color.argb(195, 20, 33, 57)); setPadding(dp(20), dp(20), dp(20), dp(52)) }
        cameraHint.addView(ScanGlyph(activity), LinearLayout.LayoutParams(dp(52), dp(52)))
        cameraHint.addView(text("让题目完整入镜", 18f, Color.WHITE, true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        cameraHint.addView(text("支持同时识别多道题目", 12f, Color.rgb(170, 191, 197)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        camera.addView(cameraHint, FrameLayout.LayoutParams(-1, -1))
        sessionBadge = text("●  待开始", 11f, Color.WHITE, true).apply {
            background = shape(Color.argb(175, 20, 33, 41), 30); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        camera.addView(sessionBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(14), 0, 0) })
        val multi = text("多题模式", 11f, Color.WHITE).apply { background = shape(Color.argb(145, 20, 33, 41), 30); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        cameraMode = multi
        camera.addView(multi, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(14), dp(14), 0) })
        val zoom = row().apply { background = shape(Color.argb(190, 20, 33, 41), 30); setPadding(dp(3), dp(2), dp(3), dp(2)) }
        zoom.addView(action("−", dark = true) { onZoom(1f / 1.25f) }, LinearLayout.LayoutParams(dp(44), dp(42)))
        zoomText = text("1.0×", 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER; contentDescription = "当前相机缩放倍数" }
        zoom.addView(zoomText, LinearLayout.LayoutParams(dp(56), dp(42)))
        zoom.addView(action("＋", dark = true) { onZoom(1.25f) }, LinearLayout.LayoutParams(dp(44), dp(42)))
        camera.addView(zoom, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        val lock = action("锁定", dark = true) { setTouchLocked(true) }
        lock.contentDescription = "开启防误触锁定"
        camera.addView(lock, FrameLayout.LayoutParams(dp(60), dp(44), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(12); bottomMargin = dp(13) })
        flipButton = action("翻转", dark = true) { onFlipCamera() }
        flipButton.contentDescription = "切换前后摄像头"
        camera.addView(flipButton, FrameLayout.LayoutParams(dp(60), dp(44), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(12); bottomMargin = dp(13) })
        root.addView(camera, margins(-1, 0, 1.1f, bottom = 10))

        val status = row().apply { background=shape(Color.WHITE,14);setPadding(dp(9), dp(9), dp(12), dp(9)) }
        val indicator = FrameLayout(activity)
        progress = ProgressBar(activity).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(teal); visibility = View.GONE }
        indicator.addView(progress, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        statusDot = View(activity).apply { background = shape(teal, 8) }
        indicator.addView(statusDot, FrameLayout.LayoutParams(dp(9), dp(9), Gravity.CENTER))
        status.addView(indicator, LinearLayout.LayoutParams(dp(28), dp(32)))
        val statusContent = column()
        statusTitle = text("准备好开始了", 13f, ink, true)
        statusDetail = text("将题目对准镜头，稳定后自动拍照", 11f, muted).apply { maxLines = 2;ellipsize=android.text.TextUtils.TruncateAt.END }
        statusContent.addView(statusTitle)
        statusContent.addView(statusDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        mouthHint=text("",11f,teal).apply{visibility=View.GONE;setPadding(0,dp(5),0,0)}
        statusContent.addView(mouthHint)
        status.addView(statusContent, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(status, margins(-1, -2, bottom = 8))

        val controls = row()
        primary = action("开始识题", filled = true) { onPrimary() }.apply{textSize=15f}
        captureButton = action("拍照识题") { onCapture() }
        controls.addView(primary, LinearLayout.LayoutParams(0, dp(50), 1.3f))
        controls.addGap(8)
        controls.addView(captureButton, LinearLayout.LayoutParams(0, dp(50), 1f))
        controls.addGap(8)
        val more = action("⋯") { }
        more.contentDescription = "更多操作"
        more.setOnClickListener {
            PopupMenu(activity, more).apply {
                menu.add("重新识别当前画面").setOnMenuItemClickListener { onRetry(); true }
                menu.add("播报全部答案").apply { isEnabled = batch != null; setOnMenuItemClickListener { batch?.let(onReplay); true } }
                menu.add("停止播报").setOnMenuItemClickListener { onStopSpeech(); true }
                menu.add("开启防误触锁定").setOnMenuItemClickListener { setTouchLocked(true); true }
                menu.add("结束识题").setOnMenuItemClickListener { onStop(); true }
                show()
            }
        }
        controls.addView(more, LinearLayout.LayoutParams(dp(48), dp(50)))
        root.addView(controls, margins(-1, -2, bottom = 8))
        val quickActions = row()
        uploadButton = action("相册导入") { onUpload() }.apply{contentDescription="上传题目图片"}
        quickActions.addView(uploadButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        quickActions.addGap(8)
        quickActions.addView(muteAction(), LinearLayout.LayoutParams(0, dp(44), 1f))
        quickActions.addGap(8)
        quickActions.addView(answerDetailAction(), LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(quickActions, margins(-1, -2, bottom = 10))

        val resultHeader = row()
        resultHeader.addView(text("本次解答", 16f, ink, true))
        countText = text("0 题", 11f, teal, true).apply { background = shape(softTeal, 30); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        resultHeader.addView(countText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        resultHeader.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        expandButton = text("展开阅读  ↗", 12f, teal, true).apply {
            gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(8), 0, 0, 0)
            isEnabled = false; alpha = 0.4f; setOnClickListener { showAllResults() }
        }
        resultHeader.addView(expandButton)
        root.addView(resultHeader, margins(-1, dp(40)))
        results = column()
        resultsScroll = ScrollView(activity).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(16), 0, dp(16), dp(12)); addView(results) }
        root.addView(resultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val navigation=row().apply{setBackgroundColor(Color.WHITE);elevation=dp(2).toFloat();setPadding(dp(20),dp(6),dp(20),dp(4))}
        listOf("识题" to "scan","历史" to "history","我的" to "user").forEachIndexed{index,(title,icon)->
            val item=column().apply{gravity=Gravity.CENTER;minimumHeight=dp(58);isClickable=true;contentDescription=title;setOnClickListener{selectTab(index)}}
            item.isSelected=index==0;item.background=shape(if(index==0)softTeal else Color.TRANSPARENT,14)
            val glyph=AppGlyph(activity,icon,if(index==0)teal else muted)
            item.addView(glyph,LinearLayout.LayoutParams(dp(23),dp(23)))
            val label=text(title,10f,if(index==0)teal else muted,index==0).apply{gravity=Gravity.CENTER}
            item.addView(label,LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(4)})
            navIcons.add(glyph);navLabels.add(label);navItems.add(item)
            navigation.addView(item,LinearLayout.LayoutParams(0,-1,1f))
        }
        outer.addView(navigation,LinearLayout.LayoutParams(-1,dp(64)))
        showMessage("答案会出现在这里", "开始识题后，完整题目将逐题整理成卡片")
        val screen = FrameLayout(activity)
        screen.addView(outer, FrameLayout.LayoutParams(-1, -1))
        touchShield = FrameLayout(activity).apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            fitsSystemWindows=true
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }
        unlockButton = action("已锁定 · 长按 2 秒解锁", filled = true) { }
        unlockButton.contentDescription = "长按此处两秒解除防误触"
        unlockButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelUnlock()
                    val deadline = android.os.SystemClock.elapsedRealtime() + 2000L
                    val task = object : Runnable {
                        override fun run() {
                            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                            if (remaining <= 0) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                setTouchLocked(false)
                            } else {
                                unlockButton.text = String.format(java.util.Locale.CHINA, "继续按住 %.1f 秒", remaining / 1000f)
                                unlockHandler.postDelayed(this, 50L)
                            }
                        }
                    }
                    unlockTask = task
                    unlockHandler.post(task)
                }
                MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.x > view.width || event.y < 0 || event.y > view.height) cancelUnlock()
                MotionEvent.ACTION_UP -> { cancelUnlock(); view.performClick() }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelUnlock()
            }
            true
        }
        // Use only the navigation strip; camera, status and answers remain unobscured.
        touchShield.addView(unlockButton, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM).apply { setMargins(dp(20),0,dp(20),dp(8)) })
        screen.addView(touchShield, FrameLayout.LayoutParams(-1, -1))
        return screen
    }

    fun cancelUnlock() {
        unlockTask?.let { unlockHandler.removeCallbacks(it) }
        unlockTask = null
        if (::unlockButton.isInitialized) unlockButton.text = "已锁定 · 长按 2 秒解锁"
    }

    private fun selectTab(index:Int){
        currentTab=index
        homePage.visibility=if(index==0)View.VISIBLE else View.GONE
        libraryPage.visibility=if(index==1)View.VISIBLE else View.GONE
        profilePage.visibility=if(index==2)View.VISIBLE else View.GONE
        navItems.forEachIndexed{i,item->
            item.isSelected=i==index
            item.background=shape(if(i==index)softTeal else Color.TRANSPARENT,14)
            navLabels[i].setTextColor(if(i==index)teal else muted)
            navIcons[i].tint=if(i==index)teal else muted
        }
        onReading(readingDepth>0||index!=0)
        if(index==1)onHistory()
        if(index==2)buildProfile()
    }

    fun backToHome():Boolean{if(currentTab==0)return false;selectTab(0);return true}
    fun hasReadingPage()=currentTab!=0||readingDepth>0

    private fun pageTitle(parent:LinearLayout,title:String,subtitle:String){
        parent.addView(column().apply{
            setPadding(dp(20),dp(20),dp(20),dp(16))
            addView(text(title,25f,ink,true))
            addView(text(subtitle,12f,muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        })
    }

    private fun buildProfile(){
        profilePage.removeAllViews()
        muteButtons.removeAll{!it.isAttachedToWindow};answerDetailButtons.removeAll{!it.isAttachedToWindow}
        pageTitle(profilePage,"我的","管理账号，让识题更顺手")
        val content=column().apply{setPadding(dp(16),0,dp(16),dp(24))}
        val user=AccountStore(activity).user()
        val accountCard=row().apply{background=shape(Color.WHITE,18);setPadding(dp(18),dp(20),dp(18),dp(20))}
        val avatar=AppGlyph(activity,"user",teal).apply{background=shape(softTeal,16);setPadding(dp(12),dp(12),dp(12),dp(12))}
        accountCard.addView(avatar,LinearLayout.LayoutParams(dp(56),dp(56)))
        accountCard.addView(column().apply{
            setPadding(dp(14),0,0,0)
            addView(text(user?.optString("username").orEmpty().ifBlank{"已登录用户"},20f,ink,true))
            addView(text(if(user?.optString("role")=="superadmin")"超级管理员" else "个人账号",12f,muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(5)})
        },LinearLayout.LayoutParams(0,-2,1f))
        accountCard.addView(text("›",25f,muted));accountCard.setOnClickListener{onAccount()}
        content.addView(accountCard,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)})
        fun entry(title:String,detail:String,icon:String,click:()->Unit){
            val entry=row().apply{background=shape(Color.WHITE,15);setPadding(dp(16),dp(14),dp(16),dp(14));minimumHeight=dp(76);setOnClickListener{click()}}
            entry.addView(AppGlyph(activity,icon,teal).apply{background=shape(softTeal,10);setPadding(dp(8),dp(8),dp(8),dp(8))},LinearLayout.LayoutParams(dp(38),dp(38)))
            entry.addView(column().apply{setPadding(dp(12),0,dp(8),0);addView(text(title,14f,ink,true));addView(text(detail,11f,muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})},LinearLayout.LayoutParams(0,-2,1f))
            entry.addView(text("›",23f,muted))
            content.addView(entry,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(9)})
        }
        entry("账号、模型与订阅","个人模型 · 使用额度 · 账号安全","user"){onAccount()}
        entry("使用偏好","相机来源 · 防抖取题 · 后台与语音","settings"){onSettings()}
        entry("识题记录","查看和搜索已保存的题目与答案","history"){selectTab(1)}
        if(user?.optString("role")=="superadmin")entry("管理控制台","在账号中心打开后台管理入口","dashboard"){onAccount()}
        val speech=row().apply{setPadding(0,dp(10),0,dp(10))}
        speech.addView(muteAction(),LinearLayout.LayoutParams(0,dp(48),1f));speech.addGap(8)
        speech.addView(answerDetailAction(),LinearLayout.LayoutParams(0,dp(48),1f))
        content.addView(speech)
        val version=runCatching{activity.packageManager.getPackageInfo(activity.packageName,0).versionName}.getOrNull().orEmpty()
        content.addView(text("识题助手  ·  v$version",11f,muted).apply{gravity=Gravity.CENTER;setPadding(0,dp(22),0,dp(10))})
        profilePage.addView(ScrollView(activity).apply{isVerticalScrollBarEnabled=false;addView(content)},LinearLayout.LayoutParams(-1,0,1f))
    }

    fun setTouchLocked(locked: Boolean) {
        cancelUnlock()
        touchShield.visibility = if (locked) View.VISIBLE else View.GONE
        interactiveContent.importantForAccessibility = if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        interactiveContent.descendantFocusability = if (locked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (locked) { interactiveContent.clearFocus(); touchShield.requestFocus() }
        onTouchLockChanged(locked)
    }

    fun setMouthState(enabled:Boolean,message:String,front:Boolean=true){
        mouthMode=enabled;mouthFront=front;mouthHint.visibility=if(enabled)View.VISIBLE else View.GONE;mouthHint.text=message
        if(enabled)cameraMode.text=if(front)"前置 · 等待张嘴" else "后置 · 拍题解答"
    }

    fun setCameraFacing(front: Boolean, available: Boolean, starting: Boolean) {
        cameraSwitchAvailable = available
        cameraStarting = starting
        cameraMode.text = if(mouthMode){if(mouthFront)"前置 · 等待张嘴" else "后置 · 拍题解答"} else if (front) "前置 · 多题" else "后置 · 多题"
        flipButton.contentDescription = if (!available) "设备不支持前后摄像头切换" else if (front) "当前前置摄像头，点击切换后置" else "当前后置摄像头，点击切换前置"
        overlay.setMirrored(front)
    }

    fun setZoom(ratio: Float) { zoomText.text = String.format(java.util.Locale.US, "%.1f×", ratio) }
    fun setNetworkCamera(enabled:Boolean){
        networkPreview.visibility=if(enabled)View.VISIBLE else View.GONE
        preview.visibility=if(enabled)View.GONE else View.VISIBLE
        overlay.setFitCenter(enabled)
        if(enabled){cameraMode.text="网络 · Action 4";cameraSwitchAvailable=false;overlay.setMirrored(false)}
    }
    fun showNetworkFrame(bitmap:Bitmap?){networkPreview.setImageBitmap(bitmap)}

    private fun muteAction(): TextView = action("一键静音") { onMute() }.also {
        muteButtons.add(it)
        setMuted(isMuted)
    }

    private fun answerDetailAction()=action(if(answerDetailEnabled)"详读：开" else "详读：关") {onAnswerDetail()}.also {answerDetailButtons.add(it)}
    fun setAnswerDetail(enabled:Boolean) {
        answerDetailEnabled=enabled
        answerDetailButtons.forEach {it.text=if(enabled)"详读：开" else "详读：关";it.contentDescription=if(enabled)"播报答案具体内容已开启，点击关闭" else "播报答案具体内容已关闭，点击开启"}
    }

    fun setMuted(value: Boolean) {
        isMuted = value
        muteButtons.forEach {
            it.text = if (value) "已静音 · 恢复声音" else "一键静音"
            it.contentDescription = if (value) "已静音，点击恢复声音" else "一键静音，立即停止所有播报"
            it.isSelected = value
            it.setTextColor(if (value) Color.WHITE else teal)
            it.background = RippleDrawable(ColorStateList.valueOf(Color.argb(30, 0, 0, 0)), shape(if (value) teal else Color.WHITE, 14, border), null)
        }
    }

    fun updateSession(running: Boolean, paused: Boolean, busy: Boolean, capturing: Boolean) {
        flipButton.isEnabled = cameraSwitchAvailable && !cameraStarting && !busy && !capturing
        flipButton.alpha = if (flipButton.isEnabled) 1f else 0.4f
        primary.text = if (!running) "开始识题" else if (paused) "继续识题" else "暂停识题"
        cameraHint.visibility = if (running) View.GONE else View.VISIBLE
        sessionBadge.text = if (!running) "●  待开始" else if (paused) "Ⅱ  已暂停" else "●  自动识题中"
        sessionBadge.setTextColor(if (running && !paused) Color.rgb(157, 190, 255) else Color.WHITE)
        uploadButton.isEnabled = !capturing && !busy && !cameraStarting
        uploadButton.alpha = if (uploadButton.isEnabled) 1f else 0.45f
        primary.isEnabled = !cameraStarting
        if(busy||capturing)primary.text="中断当前"
        primary.alpha = if (primary.isEnabled) 1f else 0.45f
        captureButton.isEnabled = !capturing && !busy && !cameraStarting
        captureButton.alpha = if (captureButton.isEnabled) 1f else 0.45f
        captureButton.text = if (capturing) "正在拍照" else if (busy) "正在解题" else "拍照识题"
    }

    fun setStatus(message: String) {
        val processing = message.contains("识别中") || message.contains("正在拍照") || message.contains("正在启动") || message.contains("正在处理图片") || message.contains("正在切换") || message.contains("自动调整取景") || message.contains("正在自动对焦")
        val warning = message.contains("失败") || message.contains("过暗") || message.contains("不清") || message.contains("未找到") || message.contains("反光") || message.contains("需要摄像头")
        val title = when {
            message.startsWith("等待开始") -> "准备好开始了"
            message.contains("识别中") -> "正在解答这组题目"
            message.contains("正在启动") -> "正在打开相机"
            message.contains("正在切换") -> "正在切换摄像头"
            message.contains("自动调整取景") -> "正在自动调整取景"
            message.contains("正在自动对焦") -> "正在自动对焦题目"
            message.startsWith("已选择") -> "相机已切换"
            message.contains("正在处理图片") -> "正在准备所选图片"
            message.contains("已暂停") -> "识题已暂停"
            message == "已停止" -> "识题已结束"
            message.contains("失败") -> "这次识别未完成"
            warning -> "调整一下取景"
            message.startsWith("已解答") || message.startsWith("已获得") -> "答案已准备好"
            message.contains("发现新题") -> "发现了新题目"
            else -> "正在寻找题目"
        }
        statusTitle.text = title
        statusDetail.text = when {
            message.startsWith("等待开始") -> "拍摄的题目图片将发送至模型服务解答"
            message.contains("识别中") -> "稍等片刻，答案将按题目顺序显示"
            message == "已停止" -> "可继续查看答案，或开始新一轮识题"
            message.contains("已暂停") -> "镜头保持预览，点击继续后恢复自动识题"
            else -> message
        }
        progress.visibility = if (processing) View.VISIBLE else View.GONE
        statusDot.visibility = if (processing) View.GONE else View.VISIBLE
        statusDot.background = shape(if (warning) Color.rgb(203, 132, 41) else teal, 8)
    }

    fun showMessage(title: String, detail: String) {
        batch = null
        countText.text = "0 题"
        expandButton.isEnabled = false; expandButton.alpha = 0.4f
        results.removeAllViews()
        val empty = column().apply { gravity = Gravity.CENTER; background = shape(Color.WHITE, 20, border); setPadding(dp(20), dp(22), dp(20), dp(22)) }
        empty.addView(text(title, 14f, ink, true))
        empty.addView(text(detail, 12f, muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        results.addView(empty, LinearLayout.LayoutParams(-1, -2))
    }

    fun showBatch(value: SolveBatch) {
        batch = value
        results.removeAllViews()
        countText.text = "${value.questions.size} 题"
        expandButton.isEnabled = true; expandButton.alpha = 1f
        addResultCards(results, value)
        resultsScroll.post { resultsScroll.smoothScrollTo(0, 0) }
    }

    private fun addResultCards(parent: LinearLayout, value: SolveBatch) {
        value.questions.forEachIndexed { index, question ->
            parent.addView(questionCard(question, index + 1), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        if (value.uncertainty.isNotBlank()) {
            parent.addView(text("部分题目未纳入\n${value.uncertainty}", 12f, Color.rgb(143, 103, 46)).apply {
                background = shape(Color.rgb(255, 247, 230)); setPadding(dp(14), dp(12), dp(14), dp(12))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun questionCard(question: SolveResult, number: Int): View {
        var currentQuestion=question
        val card = column().apply { background = shape(Color.WHITE, 20, border); setPadding(dp(16), dp(16), dp(16), dp(14)) }
        val heading = row()
        heading.addView(text(if (number > 0) "第 $number 题" else "已保存", 11f, teal, true).apply { background = shape(softTeal, 8); setPadding(dp(8), dp(5), dp(8), dp(5)) })
        heading.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        heading.addView(text("播报", 12f, teal, true).apply { gravity = Gravity.CENTER; minHeight = dp(40); minWidth = dp(48); setOnClickListener { onSpeakQuestion(currentQuestion) } })
        heading.addView(text("复制", 12f, muted).apply { gravity = Gravity.CENTER; minHeight = dp(40); minWidth = dp(44); setOnClickListener {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("题目与答案", "${currentQuestion.full}\n\n答案：${currentQuestion.answer}"))
            Toast.makeText(activity, "已复制题目与答案", Toast.LENGTH_SHORT).show()
        } })
        card.addView(heading)
        card.addView(markdownText(question.brief, 15f, ink, question.photoPath).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(12) })
        val answer = column().apply { background = shape(softTeal, 12); setPadding(dp(12), dp(10), dp(12), dp(12)) }
        answer.addView(text("参考答案", 10f, teal))
        answer.addView(markdownText(question.answer, 20f, teal, question.photoPath), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        card.addView(answer)
        val details = column().apply { visibility = View.GONE }
        details.addView(text("完整题目", 11f, muted, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(7) })
        val full = if (QuestionImages.resolve(activity, question.photoPath) != null && !question.full.contains("](question-source)"))
            question.full + "\n\n![原始题图，含题目示意图](question-source)" else question.full
        details.addView(markdownText(full, 14f, ink, question.photoPath))
        if (QuestionImages.resolve(activity, question.photoPath) != null) {
            details.addView(action("查看原始题图 · 可缩放") { showQuestionImage(question.photoPath) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        }
        details.addView(text("解题思路", 11f, muted, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(7) })
        details.addView(markdownText(question.solution, 14f, ink, question.photoPath))
        if(question.fast) {
            details.addView(text("极速模式：以上为简短依据，题干可能经过精简；完整题目请查看原图。",11f,muted))
            val explain=action("生成详细解析（另计一次调用）") {}
            explain.setOnClickListener {
                explain.isEnabled=false;explain.text="正在生成详细解析…"
                onExplainQuestion(currentQuestion){result->
                    result.onSuccess {detail->
                        currentQuestion=detail
                        answer.removeAllViews();answer.addView(text("重新核对后的参考答案",10f,teal));answer.addView(markdownText(detail.answer,20f,teal,detail.photoPath))
                        details.removeAllViews();details.addView(text("完整题目",11f,muted,true));details.addView(markdownText(detail.full,14f,ink,detail.photoPath));details.addView(text("详细解析",11f,muted,true));details.addView(markdownText(detail.solution,14f,ink,detail.photoPath))
                        if(detail.uncertainty.isNotBlank())details.addView(markdownText(detail.uncertainty,12f,muted,detail.photoPath))
                    }.onFailure {explain.isEnabled=true;explain.text="重试生成详细解析（另计一次调用）";Toast.makeText(activity,it.message?:"生成失败",Toast.LENGTH_LONG).show()}
                }
            }
            details.addView(explain,LinearLayout.LayoutParams(-1,dp(48)).apply {topMargin=dp(10)})
        }
        if (question.uncertainty.isNotBlank()) details.addView(markdownText("核对提示：${question.uncertainty}", 12f, Color.rgb(143, 103, 46), question.photoPath), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(details)
        val toggle = text("查看题目与解析  ⌄", 12f, muted).apply { gravity = Gravity.CENTER; minHeight = dp(42) }
        toggle.setOnClickListener {
            val expanded = details.visibility == View.VISIBLE
            details.visibility = if (expanded) View.GONE else View.VISIBLE
            toggle.text = if (expanded) "查看题目与解析  ⌄" else "收起解析  ⌃"
        }
        card.addView(toggle)
        return card
    }

    private fun showAllResults() {
        val value = batch ?: return
        showReadingDialog("本次识题 · ${value.questions.size} 题", "播报全部", { onReplay(value) }) { content -> addResultCards(content, value) }
    }

    private val historyScreen by lazy { HistoryScreen(activity) }
    internal fun showHistory(repo:HistoryRepository) {
        if(currentTab!=1){selectTab(1);return}
        historyScreen.show(libraryPage,repo){entry,toggle->
            val question=entry.question()
            showReadingDialog("题目详情","播报",{onSpeakQuestion(question)},afterClose={historyScreen.reload()}){content->
                var selected=entry.favorite
                lateinit var star:TextView
                star=action(if(selected)"★ 已收藏 · 点击取消" else "☆ 收藏题目"){selected=!selected;toggle(selected);star.text=if(selected)"★ 已收藏 · 点击取消" else "☆ 收藏题目"}
                content.addView(star)
                content.addView(questionCard(question,0))
            }
        }
    }
    fun close(){historyScreen.close();cancelUnlock()}

    private fun showQuestionImage(path: String) {
        val bitmap = QuestionImages.resolve(activity, path)?.let { QuestionImages.decode(it, 3200) }
        if (bitmap == null) { Toast.makeText(activity, "题图已不存在或无法读取", Toast.LENGTH_SHORT).show(); return }
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        val root = column().apply { fitsSystemWindows = true; setBackgroundColor(surfaceBackground) }
        root.addView(action("返回 · 原始题图") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(ZoomImageView(activity, bitmap), LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("双指缩放 · 拖动查看 · 双击放大或复位", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12)) })
        dialog.setContentView(root)
        dialog.setOnDismissListener { bitmap.recycle(); leaveReading() }
        enterReading()
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }

    private fun showReadingDialog(title: String, actionText: String, action: () -> Unit, afterClose: () -> Unit = {}, buildContent: (LinearLayout) -> Unit): Dialog {
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = column().apply { setBackgroundColor(surfaceBackground); fitsSystemWindows = true }
        val header = row().apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        header.addView(this.action("返回") { dialog.dismiss() })
        header.addView(text(title, 17f, ink, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        if (actionText.isNotBlank()) header.addView(this.action(actionText) { action() })
        root.addView(header)
        val content = column().apply { setPadding(dp(16), dp(8), dp(16), dp(20)) }
        buildContent(content)
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val speechControls = row()
        val muteControl = muteAction()
        speechControls.addView(muteControl, LinearLayout.LayoutParams(0, dp(48), 1f))
        speechControls.addGap(8)
        speechControls.addView(this.action("停止播报") { onStopSpeech() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(speechControls, margins(-1, -2, bottom = 12))
        dialog.setContentView(root)
        dialog.window?.apply { statusBarColor = surfaceBackground; navigationBarColor = surfaceBackground; decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR }
        dialog.setOnDismissListener { muteButtons.remove(muteControl); leaveReading();afterClose() }
        enterReading()
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        return dialog
    }
}

private class AppGlyph(context:Context,private val kind:String,color:Int):View(context){
    var tint=color
        set(value){field=value;invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);canvas.save()
        canvas.translate(paddingLeft.toFloat(),paddingTop.toFloat())
        canvas.scale((width-paddingLeft-paddingRight)/24f,(height-paddingTop-paddingBottom)/24f)
        paint.color=tint
        when(kind){
            "scan"->{
                val path=Path().apply{moveTo(8f,3f);lineTo(4f,3f);quadTo(3f,3f,3f,4f);lineTo(3f,8f);moveTo(16f,3f);lineTo(20f,3f);quadTo(21f,3f,21f,4f);lineTo(21f,8f);moveTo(3f,16f);lineTo(3f,20f);quadTo(3f,21f,4f,21f);lineTo(8f,21f);moveTo(16f,21f);lineTo(20f,21f);quadTo(21f,21f,21f,20f);lineTo(21f,16f)}
                canvas.drawPath(path,paint);canvas.drawLine(8f,10f,16f,10f,paint);canvas.drawLine(8f,14f,13f,14f,paint)
            }
            "history"->{canvas.drawCircle(12f,12f,8f,paint);canvas.drawLine(12f,7f,12f,12f,paint);canvas.drawLine(12f,12f,16f,14f,paint)}
            "user"->{canvas.drawCircle(12f,8f,3.5f,paint);canvas.drawArc(4f,13f,20f,27f,195f,150f,false,paint)}
            "settings"->{canvas.drawRoundRect(4f,4f,20f,20f,4f,4f,paint);canvas.drawLine(8f,7f,8f,17f,paint);canvas.drawLine(16f,7f,16f,17f,paint);canvas.drawCircle(8f,10f,2f,paint);canvas.drawCircle(16f,14f,2f,paint)}
            else->{canvas.drawRoundRect(3f,3f,10f,10f,1f,1f,paint);canvas.drawRoundRect(14f,3f,21f,10f,1f,1f,paint);canvas.drawRoundRect(3f,14f,10f,21f,1f,1f,paint);canvas.drawRoundRect(14f,14f,21f,21f,1f,1f,paint)}
        }
        canvas.restore()
    }
}

private class ScanGlyph(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(157, 190, 255); style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val m = w * .12f; val l = w * .2f
        canvas.drawLine(m, m + l, m, m, paint); canvas.drawLine(m, m, m + l, m, paint)
        canvas.drawLine(w - m - l, m, w - m, m, paint); canvas.drawLine(w - m, m, w - m, m + l, paint)
        canvas.drawLine(m, h - m - l, m, h - m, paint); canvas.drawLine(m, h - m, m + l, h - m, paint)
        canvas.drawLine(w - m - l, h - m, w - m, h - m, paint); canvas.drawLine(w - m, h - m, w - m, h - m - l, paint)
        paint.alpha = 120
        canvas.drawLine(w * .32f, h * .4f, w * .68f, h * .4f, paint)
        canvas.drawLine(w * .32f, h * .58f, w * .58f, h * .58f, paint)
        paint.alpha = 255
    }
}
