package com.autobot.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.FileDescriptor
import java.io.PrintWriter

/** Camera lifecycle, CPU wakefulness and speech survive the screen and Activity. */
class ReaderService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    lateinit var engine: ReaderEngine
        private set
    inner class LocalBinder : Binder() { val service get() = this@ReaderService }
    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private var background = false
    private var foregroundTypes=0
    private var closing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewedAt = 0L
    private var lastNotification = ""
    private var notifiedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            updateWorkState()
            if (background && !closing) handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        engine = ReaderEngine(this, this)
        engine.onStateChanged = { if (!closing) updateWorkState() }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:reader")
            .apply { setReferenceCounted(false) }
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "后台识题与播报", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
            engine.stopAll()
            disableBackground()
        } else if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("background_enabled", false)) {
            runCatching { enableBackground() }.onFailure {
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("background_enabled", false).apply()
                engine.stopScanning()
                disableBackground()
            }
        } else stopSelf()
        // Camera services must be started by the user from a visible Activity.
        return START_NOT_STICKY
    }

    fun enableBackground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= 30 && engine.requiresCamera()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if(Build.VERSION.SDK_INT>=30&&engine.requiresMicrophone()&&androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED)types=types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if(background&&foregroundTypes==types)return
        ServiceCompat.startForeground(this, NOTIFICATION, notification(), types)
        foregroundTypes=types
        background = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun disableBackground() {
        background = false
        foregroundTypes=0
        handler.removeCallbacks(tick)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateWorkState() {
        if (!background || closing) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (engine.hasWork()) {
            if (wakeLock?.isHeld != true || now - wakeRenewedAt >= 300_000L) {
                wakeLock?.acquire(600_000L)
                wakeRenewedAt = now
            }
        } else releaseWakeLock()
        val text = notificationText()
        if (text != lastNotification && now - notifiedAt >= 2000L) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
            lastNotification = text
            notifiedAt = now
        }
    }

    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }

    private fun notificationText(): String = when {
        engine.busy -> "正在处理题目，锁屏后继续运行"
        engine.voiceInputEnabled()&&engine.running&&!engine.paused -> "语音听题运行中，正在使用麦克风或等待嘴型启动"
        engine.isSoundListening() -> "声音控制拍照已开启，正在使用麦克风"
        engine.running && !engine.paused -> "摄像头识题运行中，锁屏后继续运行"
        engine.running -> "自动识题已暂停，可返回应用继续"
        else -> "后台模式已开启，请返回应用开始识题"
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReaderService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("识题助手 · 后台运行")
            .setContentText(notificationText())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "停止后台任务", stop)
            .build()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("background=$background running=${engine.running} paused=${engine.paused} busy=${engine.busy}")
        writer.println("frames=${engine.framesAnalyzed} requests=${engine.requestsSent} wakeHeld=${wakeLock?.isHeld == true}")
        writer.println("focusRequests=${engine.focusRequests} focusSuccesses=${engine.focusSuccesses} autoZooms=${engine.automaticZooms} zoom=${engine.currentZoom()}")
    }

    override fun onDestroy() {
        closing = true
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        engine.onStateChanged = null
        engine.close()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "reader_background"
        private const val NOTIFICATION = 1001
        const val STOP = "com.autobot.reader.STOP_BACKGROUND"
    }
}
