package com.example.wristai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 透明的「跳板」画面：手表遥控开始手机录音时用。
 *
 * Android 14 起，App 在背景时不能开麦克风（前台服务也一样）。解法：
 * 1. 使用者允许过「显示在其他应用程式上层」→ 系统允许从背景开 Activity → 这个透明画面在前台启动录音，
 *    录音开始后马上关掉（锁屏时也能用，画面只会亮一下）。
 * 2. 没有这个权限 → 发通知，使用者在手机上点一下通知就开始（点通知也算前台操作）。
 */
class RemoteRecordActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_NODE = "watch_node"
        private const val CHANNEL_ID = "remote_record"
        private const val NOTIFICATION_ID = 4

        fun intent(context: Context, watchNode: String?) =
            Intent(context, RemoteRecordActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_NODE, watchNode)

        /** 能不能不经使用者操作，直接从手表启动手机录音（锁屏时靠全萤幕通知，萤幕开着时靠「显示在其他 App 上层」）。 */
        fun canStartFromBackground(context: Context) =
            android.provider.Settings.canDrawOverlays(context) || canUseFullScreen(context)

        fun canUseFullScreen(context: Context) =
            Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        /** 手表要求开始手机录音。 */
        fun requestFromWatch(context: Context, watchNode: String?) {
            if (PhoneRecorder.active.value != null) { PhoneRecordingService.broadcastState(context); return }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                WatchNotifier.sendStatus(context, watchNode, "請先在手機打開 大D 允許麥克風")
                return
            }
            if (android.provider.Settings.canDrawOverlays(context) || MainActivity.isVisible) {
                runCatching { context.startActivity(intent(context, watchNode)) }
                    .onSuccess { return }
            }
            // 退回：全萤幕通知（锁屏 / 熄屏时系统会像来电一样直接打开录音画面）；不能用时就是普通通知，点一下开始
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "手錶遙控錄音", NotificationManager.IMPORTANCE_HIGH))
            }
            val pi = PendingIntent.getActivity(context, 2, intent(context, watchNode),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("手錶要求開始手機錄音")
                .setContentText("點一下開始錄音（到設定開啟「手錶遠端啟動」就不用點）")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build())
            if (!canUseFullScreen(context)) WatchNotifier.sendStatus(context, watchNode, "請在手機點通知開始錄音")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        PhoneRecordingService.start(this, intent.getStringExtra(EXTRA_NODE))
        // 等录音真的开始（麦克风要在 App 前台时打开）才关掉这个画面
        lifecycleScope.launch {
            withTimeoutOrNull(5_000) { PhoneRecorder.active.first { it != null } }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
