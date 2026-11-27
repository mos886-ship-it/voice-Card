package com.example.wristai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings as SysSettings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.voiceai.shared.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮即时翻译：一个浮在其他 App 上面的小字幕框（可以拖），显示最新一句的译文（大）和原文（小），
 * 按钮：⇄ 对调方向、⏸ / ▶ 暂停继续、✕ 关闭。从桌面小工具「懸浮翻譯」开（[start]）。
 * 用 [LiveCaptions]（跟翻译页同一套：手机辨识＋装置端翻译、不要提示声、存记录）。
 * 前景服务（麦克风）：App 退到后面也能一直听。需要「显示在其他应用程式上层」权限，没有就带使用者去设定。
 */
class FloatingCaptionService : Service() {
    companion object {
        private const val CHANNEL = "floating_captions"
        private const val NOTIF_ID = 4711
        private const val ACTION_CLOSE = "com.example.wristai.FLOAT_CLOSE"
        @Volatile var running = false
            private set

        /** 从前景（App 画面）呼叫：有悬浮权限就开，没有就开设定页让使用者允许。回传有没有开。 */
        fun start(c: Context): Boolean {
            if (!SysSettings.canDrawOverlays(c)) {
                Toast.makeText(c, "請允許「顯示在其他應用程式上層」，再按一次懸浮翻譯".tr, Toast.LENGTH_LONG).show()
                c.startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return false
            }
            ContextCompat.startForegroundService(c, Intent(c, FloatingCaptionService::class.java))
            return true
        }

        fun stop(c: Context) = c.stopService(Intent(c, FloatingCaptionService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: View? = null
    private var watch: Job? = null
    private lateinit var tvLang: TextView
    private lateinit var tvMain: TextView
    private lateinit var tvOrig: TextView
    private lateinit var btnPause: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) { stopSelf(); return START_NOT_STICKY }
        startInForeground()
        running = true
        HomeWidget.refresh(this)
        if (view == null) addOverlay()
        if (!LiveCaptions.state.value.running) startCaptions()
        return START_NOT_STICKY
    }

    private fun startCaptions() {
        LiveCaptions.start(this, Settings.translateSource(this), Settings.translateTarget(this))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "懸浮翻譯".tr, NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 1, Intent(this, FloatingCaptionService::class.java).setAction(ACTION_CLOSE), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("懸浮翻譯中".tr)
            .setContentText("字幕框可以拖到任何位置；按 ✕ 關閉".tr)
            .setOngoing(true)
            .addAction(0, "關閉".tr, close)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x332BC8D6) }
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(0xFA0B1A27.toInt()); setStroke(dp(1), 0x552BC8D6)
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvLang = TextView(this).apply {
            setTextColor(0xFF2BC8D6.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(tvLang)
        top.addView(button("⇄") { LiveCaptions.swap(this) })
        btnPause = button("⏸") {
            if (LiveCaptions.state.value.running) LiveCaptions.stop() else startCaptions()
        }
        top.addView(btnPause)
        top.addView(button("✕") { stopSelf() })
        root.addView(top)
        tvMain = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            maxLines = 4; ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(6), 0, 0)
        }
        tvOrig = TextView(this).apply {
            setTextColor(0xFF8FA3B3.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(tvMain); root.addView(tvOrig)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val lp = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
        }
        // 拖曳：按住字幕框上下左右移动
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(root, lp) }; true
                }
                else -> false
            }
        }
        wm.addView(root, lp)
        view = root
        watch = scope.launch {
            LiveCaptions.state.collect { s ->
                tvLang.text = TranslateLangs.shortName(TranslateLangs.mlkitOf(s.source) ?: s.source) + " → " + TranslateLangs.shortName(s.target, true)
                btnPause.text = if (s.running) "⏸" else "▶"
                val last = s.lines.lastOrNull()
                when {
                    s.error != null -> { tvMain.text = s.error; tvOrig.text = "" }
                    last == null -> { tvMain.text = if (s.running) "請說話…".tr else "已暫停".tr; tvOrig.text = s.status.orEmpty() }
                    else -> {
                        tvMain.text = last.translated ?: "…"
                        tvMain.alpha = if (last.partial) 0.75f else 1f
                        tvOrig.text = last.original
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        running = false
        HomeWidget.refresh(this)
        watch?.cancel()
        scope.cancel()
        view?.let { v -> runCatching { getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        if (LiveCaptions.state.value.running) LiveCaptions.stop()
        super.onDestroy()
    }
}
