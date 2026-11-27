package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 「新录音已存入录音机」通知：点一下直接打开三星录音机，用 Galaxy AI 转录 / 摘要。 */
object ReadyNotification {
    private const val CHANNEL_ID = "ready"

    fun show(context: Context, meetingId: String, durationSec: Double) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "新錄音".tr, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val target = VoiceRecorderExporter.launchIntent(context)
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, meetingId.hashCode(), target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = durationSec.toInt()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("手錶錄音已存入「錄音機」（%d:%02d）".tr.format(s / 60, s % 60))
            .setContentText("點此開啟錄音機 → 選最新的 Dylan_ 開頭錄音 → 轉錄 / 摘要".tr)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(meetingId.hashCode(), notification) }
    }

    /** 「报告完成」通知：点一下打开 WristAI 看报告。 */
    fun showReport(context: Context, meetingId: String, title: String, summary: String) {
        // 车用：设定要念就念出来（开车不用看萤幕）
        runCatching { CarMode.onReportReady(context, meetingId, title, summary) }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "新錄音".tr, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val target = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, meetingId.hashCode(), target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("報告完成：{0}".trf(title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(meetingId.hashCode(), notification) }
    }
}
