package com.example.wristai

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机画面（Android Auto，媒体类）：开车时用车上的萤幕和喇叭。
 *
 * - 「今天」分页：念今天待办、念最新报告、开始 / 停止录音、记录饮食。
 * - 「報告」分页：最近 15 份报告，点了念标题＋摘要＋待办。
 * - 播放器上的按钮：录音 / 停止（自订动作）。
 * - 「Hey Google，播放大D」（[MediaSessionCompat.Callback.onPlayFromSearch]）：念今天待办＋最新报告。
 * 念的部分用手机内建 TTS（[Speaker]），经媒体声道从车上喇叭出来。
 * 只让 Android Auto、系统、自己浏览（报告标题、摘要是私人资料）。
 * 不是 Play 商店装的：Android Auto 要开发者模式 →「未知來源」才看得到（设定 → 車用 有说明）。
 */
class CarMediaService : MediaBrowserServiceCompat() {
    companion object {
        private const val ROOT = "root"
        private const val TAB_TODAY = "today"
        private const val TAB_REPORTS = "reports"
        const val ID_TODOS = "todos"
        const val ID_LATEST = "latest"
        const val ID_RECORD = "record"
        const val ID_FOOD = "food"
        const val REPORT_PREFIX = "report:"
        private const val ACTION_RECORD = "record_toggle"

        /** 可以浏览的套件（Android Auto、它的模拟器 / 车机端、系统、自己）。 */
        private val TRUSTED = setOf(
            "com.google.android.projection.gearhead", "com.google.android.autosimulator",
            "com.google.android.carassistant", "com.google.android.googlequicksearchbox",
            "android", "com.android.systemui", "com.android.car.media",
        )

        @Volatile private var instance: CarMediaService? = null

        /** 录音开始 / 停止时更新车机画面的按钮（[PhoneRecordingService.broadcastState]）。 */
        fun recordingChanged() {
            instance?.let { s -> Handler(Looper.getMainLooper()).post { s.refreshState(); s.notifyChildrenChanged(TAB_TODAY) } }
        }
    }

    private lateinit var session: MediaSessionCompat
    private var playing: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        session = MediaSessionCompat(this, "DylanCar").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        Speaker.onIdle = { Handler(Looper.getMainLooper()).post { playing = null; refreshState() } }
        refreshState()
    }

    override fun onDestroy() {
        instance = null
        Speaker.onIdle = null
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        val ok = clientPackageName == packageName || clientPackageName in TRUSTED || clientUid == android.os.Process.SYSTEM_UID
        if (!ok) return null
        val extras = Bundle().apply {
            // 车机显示方式：可浏览的是格子、可播的是清单
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 2)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            putBoolean("android.media.browse.SEARCH_SUPPORTED", false)
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(children(this, parentId).toMutableList())
    }

    private fun item(id: String, title: String, subtitle: String?, playable: Boolean, icon: Int? = null) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle)
                .apply { icon?.let { setIconUri(android.net.Uri.parse("android.resource://$packageName/$it")) } }.build(),
            if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)

    private fun children(c: Context, parent: String): List<MediaBrowserCompat.MediaItem> = when (parent) {
        ROOT -> listOf(item(TAB_TODAY, "今天".tr, null, false), item(TAB_REPORTS, "報告".tr, null, false))
        TAB_TODAY -> {
            val rec = PhoneRecorder.active.value
            val todos = runCatching { CarMode.todayTodos(c).size }.getOrDefault(0)
            listOf(
                item(ID_RECORD, if (rec == null) "開始錄音".tr else "停止錄音".tr,
                    if (rec == null) "用設定的麥克風錄（設定 → 車用）".tr else "錄音中".tr, true,
                    if (rec == null) R.drawable.ic_sc_record else R.drawable.ic_sc_stop),
                item(ID_TODOS, "唸今天待辦".tr, if (todos == 0) "今天沒有待辦事項".tr else "{0} 項".trf(todos), true, R.drawable.ic_sc_todo),
                item(ID_LATEST, "唸最新報告".tr, CarMode.latestText(c)?.substringBefore("。")?.take(40), true, R.drawable.ic_sc_speak),
                item(ID_FOOD, "記錄飲食".tr, "說出吃了什麼，再按一次停止".tr, true, R.drawable.ic_sc_food),
            )
        }
        TAB_REPORTS -> {
            MeetingStore.reload(c)
            val fmt = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
            MeetingStore.meetings.value
                .filter { it.status == MeetingStatus.DONE && !FoodLog.isFood(it.id) }
                .sortedByDescending { it.createdAt }.take(15)
                .map { m -> item(REPORT_PREFIX + m.id, m.title ?: "錄音".tr, fmt.format(Date(m.createdAt)), true, R.drawable.ic_sc_speak) }
        }
        else -> emptyList()
    }

    /** 念一份报告：标题＋摘要＋待办。 */
    private fun reportText(id: String): String? {
        val m = MeetingStore.get(this, id) ?: return null
        val md = MeetingStore.reportFile(this, id).takeIf { it.exists() }?.readText()
        val summary = com.example.wristai.ui.summaryOf(md)?.let { Speakers.apply(it, m.speakerNames) }
        val todos = md?.let { ActionItems.parse(id, it).map { i -> Speakers.apply(i.task, m.speakerNames) } }.orEmpty()
        return listOfNotNull(m.title, summary).joinToString("。") + "。" +
            (if (todos.isNotEmpty()) "待辦：".tr + todos.joinToString("；") + "。" else "")
    }

    private fun say(title: String, text: String) {
        Speaker.stop()
        Speaker.speak(this, text, ReportLang.current(this).code, CarMode.rate(this))
        playing = title
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "大D".tr)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, text.take(80))
            .build())
        refreshState()
    }

    /**
     * 开始 / 停止录音。App 在背景时 Android 不让直接开麦克风：跟手表遥控一样，先把录音画面叫到前面一下
     * （有「显示在其他应用程式上层」就直接开；没有就跳通知，在手机上点一下）。
     */
    private fun toggleRecording(food: Boolean = false) {
        if (PhoneRecorder.active.value != null) { PhoneRecordingService.stop(this); return }
        PhoneRecorder.setError(null)
        runCatching { RemoteRecordActivity.requestFromWatch(this, null, food) }
        Handler(Looper.getMainLooper()).postDelayed({
            if (PhoneRecorder.active.value == null)
                showError(if (android.provider.Settings.canDrawOverlays(this)) "沒有開始錄音：{0}".trf(PhoneRecorder.error.value ?: "請看手機".tr)
                    else "請在手機點通知開始錄音。到 設定 → 車用 允許「顯示在其他應用程式上層」，之後在車機上就能直接開始。".tr)
            else refreshState()
            notifyChildrenChanged(TAB_TODAY)
        }, 3000)
    }

    private fun showError(msg: String) {
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, msg)
            .addCustomAction(recordAction())
            .build())
    }

    private fun recordAction(): PlaybackStateCompat.CustomAction {
        val rec = PhoneRecorder.active.value != null
        return PlaybackStateCompat.CustomAction.Builder(ACTION_RECORD,
            if (rec) "停止錄音".tr else "開始錄音".tr,
            if (rec) R.drawable.widget_rec_stop else R.drawable.widget_rec_dot).build()
    }

    internal fun refreshState() {
        val rec = PhoneRecorder.active.value
        val state = when {
            playing != null -> PlaybackStateCompat.STATE_PLAYING
            rec != null -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        if (playing == null && rec != null) {
            session.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "錄音中".tr)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "大D".tr).build())
        }
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(state, if (rec != null) System.currentTimeMillis() - rec.startedAt else PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (rec != null) 1f else 0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
            .addCustomAction(recordAction())
            .build())
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            when {
                mediaId == ID_RECORD -> toggleRecording()
                mediaId == ID_FOOD -> toggleRecording(food = true)
                mediaId == ID_TODOS -> say("今天的待辦".tr, CarMode.todosText(this@CarMediaService))
                mediaId == ID_LATEST -> say("最新報告".tr, CarMode.latestText(this@CarMediaService) ?: "還沒有做好的報告。".tr)
                mediaId?.startsWith(REPORT_PREFIX) == true -> {
                    val id = mediaId.removePrefix(REPORT_PREFIX)
                    reportText(id)?.let { say(MeetingStore.get(this@CarMediaService, id)?.title ?: "報告".tr, it) }
                }
            }
        }

        /** 「Hey Google，播放大D」：今天待办＋最新报告。 */
        override fun onPlayFromSearch(query: String?, extras: Bundle?) = onPlay()

        override fun onPlay() {
            val c = this@CarMediaService
            say("今天".tr, CarMode.todosText(c) + (CarMode.latestText(c)?.let { "最新報告：".tr + it } ?: ""))
        }

        override fun onPause() { Speaker.stop(); playing = null; refreshState() }
        override fun onStop() = onPause()

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_RECORD) toggleRecording()
        }
    }
}
