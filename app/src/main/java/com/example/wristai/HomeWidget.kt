package com.example.wristai

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 桌面小工具（4×2）：
 * - 上排：日期＋最新报告标题；录音中改成红色计时。右边大按钮：开始 / 停止录音。
 * - 中间：今天几段录音、几大卡 / 目标、几项待办。
 * - 下排：记录饮食、即时翻译、念今天待办。
 * 按钮都开 [MainActivity]（[CarShortcuts] 的动作）：录音要 App 在前景才能开麦克风。
 * 录音开始 / 停止、报告做完、饮食记录做完时 [refresh]；系统另外每 30 分钟更新一次（日期、换日）。
 */
class HomeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 重画全部小工具（读档在背景）。没放小工具就什么都不做。 */
        fun refresh(context: Context) {
            val c = context.applicationContext
            val manager = AppWidgetManager.getInstance(c) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(c, HomeWidget::class.java)) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            scope.launch {
                val views = runCatching { build(c) }.getOrNull() ?: return@launch
                runCatching { manager.updateAppWidget(ids, views) }
            }
        }

        private fun action(c: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getActivity(c, code,
                Intent(c, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun build(c: Context): RemoteViews {
            MeetingStore.reload(c)
            val all = MeetingStore.meetings.value
            val start = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = all.filter { it.createdAt >= start }
            val recordings = today.count { !FoodLog.isFood(it.id) }
            val kcal = today.filter { FoodLog.isFood(it.id) }.sumOf { it.nutrition?.kcal ?: 0 }
            val todos = runCatching { CarMode.todayTodos(c).size }.getOrDefault(0)
            val latest = all.filter { it.status == MeetingStatus.DONE && !FoodLog.isFood(it.id) }.maxByOrNull { it.createdAt }
            val active = PhoneRecorder.active.value

            val v = RemoteViews(c.packageName, R.layout.widget_home)
            v.setTextViewText(R.id.w_date, SimpleDateFormat("M/d EEE", Locale.getDefault()).format(Date()) + "  ·  " + "大D".tr)
            if (active != null) {
                // 计时：Chronometer 以开机后的时间为基准
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - active.startedAt)
                v.setViewVisibility(R.id.w_timer, View.VISIBLE)
                v.setChronometer(R.id.w_timer, base, null, true)
                v.setTextViewText(R.id.w_title, if (FoodLog.isFood(active.meetingId)) "記錄飲食中…".tr else "錄音中，點右邊停止".tr)
                v.setImageViewResource(R.id.w_rec_icon, R.drawable.widget_rec_stop)
            } else {
                v.setViewVisibility(R.id.w_timer, View.GONE)
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), null, false)
                v.setTextViewText(R.id.w_title, latest?.title ?: "按右邊開始錄音".tr)
                v.setImageViewResource(R.id.w_rec_icon, R.drawable.widget_rec_dot)
            }
            v.setTextViewText(R.id.w_stats, "今天 {0} 段錄音 · {1} / {2} 大卡 · {3} 項待辦".trf(recordings, kcal, Settings.dailyKcalGoal(c), todos))
            v.setTextViewText(R.id.w_food, "記錄飲食".tr)
            v.setTextViewText(R.id.w_translate, "翻譯".tr)
            v.setTextViewText(R.id.w_todos, "唸待辦".tr)

            v.setOnClickPendingIntent(R.id.w_rec, action(c, CarShortcuts.TOGGLE, 701))
            v.setOnClickPendingIntent(R.id.w_food, action(c, CarShortcuts.FOOD, 702))
            v.setOnClickPendingIntent(R.id.w_translate, action(c, CarShortcuts.TRANSLATE, 703))
            v.setOnClickPendingIntent(R.id.w_todos, action(c, CarShortcuts.READ_TODOS, 704))
            // 点标题 / 空白处：打开 App（有最新报告就打开那份）
            v.setOnClickPendingIntent(R.id.w_title, action(c, CarShortcuts.OPEN_LATEST, 705))
            v.setOnClickPendingIntent(R.id.w_stats, action(c, Intent.ACTION_MAIN, 706))
            return v
        }
    }
}
