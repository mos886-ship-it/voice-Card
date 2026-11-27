package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.wristai.ui.DetailScreen
import androidx.compose.foundation.layout.Column
import com.example.wristai.ui.DashboardScreen
import com.example.wristai.ui.BottomNav
import com.example.wristai.ui.Tab
import com.example.wristai.ui.OnboardingScreen
import com.example.wristai.ui.SettingsScreen
import com.example.wristai.ui.theme.Brand
import com.example.wristai.ui.theme.WristAI_ProjectTheme
import com.example.wristai.ui.theme.AppTheme
import com.example.wristai.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /** 画面在前台（手表遥控录音时，前台可以直接开麦克风）。 */
        @Volatile var isVisible = false
            private set
    }

    /** Android 12 以下的个别 App 语言（13+ 系统处理）。 */
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(newBase)) }

    override fun onStart() { super.onStart(); isVisible = true }
    override fun onStop() { isVisible = false; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WristAiApp.restartIfLanguageChanged(this)) return
        // 浅色主题 → 状态列 / 导航列用深色图示
        AppTheme.mode = ThemeMode.from(Settings.themeMode(this))
        AppTheme.layout = com.example.wristai.ui.theme.LayoutMode.from(Settings.layoutMode(this))
        AppTheme.inkBg = com.example.wristai.ui.theme.InkBackground.from(Settings.inkBackground(this))
        AppTheme.ukiyoeBg = com.example.wristai.ui.theme.UkiyoeBackground.from(Settings.ukiyoeBackground(this))
        AppTheme.taiwanBg = com.example.wristai.ui.theme.TaiwanBackground.from(Settings.taiwanBackground(this))
        com.example.wristai.ui.UiMode.load(this)
        SpeechLang.load(this)
        applySystemBars(statusOnDark = false)
        MeetingStore.reload(this)
        CallRecordingWatcher.schedule(this)
        WatchNotifier.broadcastTheme(this)
        handleShare(intent)
        setContent {
            WristAI_ProjectTheme(AppTheme.mode, onBarsChanged = ::applySystemBars) {
                Box(Modifier.fillMaxSize().background(Brand.Backdrop)) {
                    // 水墨配色的背景画：铺满整个萤幕（含状态列后面），画面内容照常在上面
                    AppTheme.art?.let { res ->
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(res), null, Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) { App() }
                }
            }
        }
    }

    /**
     * 状态列 / 导航列图示：后面是深色就用浅色图示。[statusOnDark] = 状态列后面是深色（台灣，或浮世繪背景画最上面的靛蓝），
     * [navOnDark] = 导航列后面是深色（台灣）。
     */
    private var bars: Pair<Boolean, Boolean>? = null
    private fun applySystemBars(statusOnDark: Boolean, navOnDark: Boolean = false) {
        if (bars == statusOnDark to navOnDark) return
        bars = statusOnDark to navOnDark
        val light = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = if (statusOnDark) dark else light, navigationBarStyle = if (navOnDark) dark else light)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** 从录音机等 App「分享」音频到 WristAI。 */
    private fun handleShare(intent: Intent?) {
        // 测试版才有：adb 送一个 WAV 进即时翻译（模拟器没办法真的说话）
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) intent?.getStringExtra("debug_caption_wav")?.let { path ->
            val src = intent.getStringExtra("debug_caption_lang") ?: "en-US"
            val dst = intent.getStringExtra("debug_caption_target") ?: "zh"
            com.example.wristai.ui.TranslatePage.open()
            LiveCaptions.start(this, src, dst, fromRecording = true)
            LiveCaptions.debugFeedWav(java.io.File(path))
            return
        }
        // 测试版才有：模拟 Android Auto 浏览车机画面、播放一项（模拟器没有 Android Auto），结果写进 logcat「CarTest」
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) intent?.getStringExtra("debug_car_play")?.let { play ->
            var browser: android.support.v4.media.MediaBrowserCompat? = null
            browser = android.support.v4.media.MediaBrowserCompat(this, android.content.ComponentName(this, CarMediaService::class.java),
                object : android.support.v4.media.MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        val b = browser ?: return
                        for (parent in listOf(b.root, "today", "reports")) b.subscribe(parent, object : android.support.v4.media.MediaBrowserCompat.SubscriptionCallback() {
                            override fun onChildrenLoaded(p: String, items: MutableList<android.support.v4.media.MediaBrowserCompat.MediaItem>) {
                                android.util.Log.i("CarTest", "$p -> " + items.joinToString(" | ") { "${it.mediaId}: ${it.description.title} / ${it.description.subtitle}" })
                            }
                        })
                        val ctl = android.support.v4.media.session.MediaControllerCompat(this@MainActivity, b.sessionToken)
                        // 「delay:xxx」：5 秒后才播（先按 Home，测 App 在背景时车机按钮能不能用）
                        val delay = if (play.startsWith("delay:")) 5000L else 0L
                        val id = play.removePrefix("delay:")
                        if (id.isNotBlank()) android.os.Handler(mainLooper).postDelayed({ ctl.transportControls.playFromMediaId(id, null) }, delay)
                        android.os.Handler(mainLooper).postDelayed({
                            android.util.Log.i("CarTest", "state=${ctl.playbackState?.state} title=${ctl.metadata?.description?.title} err=${ctl.playbackState?.errorMessage}")
                        }, 2500 + delay)
                    }
                }, null)
            browser.connect()
            return
        }
        // 捷径（长按图示、三星「模式與日常」、语音助理）：开始 / 停止录音、记录饮食、念最新报告、念今天待办（车用）
        if (intent?.action?.startsWith(CarShortcuts.PREFIX) == true) {
            CarShortcuts.handle(this, intent.action!!)
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        // 大D的主機配对连结 dylan://pair?…（相机扫 QR、配对页的连结）→ 设定页处理
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "dylan") {
            com.example.wristai.ui.PendingPair.link = intent.data.toString()
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        importAudio(this, uri)
        setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        MeetingStore.reload(this)
    }
}

/** 页面：首页 / 设置 / 会议详情（"detail:<id>"）。 */
@Composable
private fun App() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 路由：底部三个分页（tab:records / tab:food / tab:settings）、详情（detail:<id>）、第一次使用引导
    var route by rememberSaveable { mutableStateOf(if (Settings.onboarded(context)) Tab.HOME.route else "onboarding") }
    // 从详情返回时回到原本的分页
    var lastTab by rememberSaveable { mutableStateOf(Tab.HOME.route) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        if (!Settings.onboarded(context)) return@LaunchedEffect  // 引导页会自己要权限
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO) // 系統語音辨識需要
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }
    fun go(r: String) { if (r.startsWith("tab:")) lastTab = r; route = r }
    // 收到配对连结 → 到设定页（「大D的主機」卡会接着配对）
    LaunchedEffect(com.example.wristai.ui.PendingPair.link) {
        if (com.example.wristai.ui.PendingPair.link != null) go(Tab.SETTINGS.route)
    }
    // 即时翻译（主页「翻譯」、录音中「即時翻譯」）→「翻譯」分页的第一个小分页
    LaunchedEffect(com.example.wristai.ui.TranslatePage.requested) {
        if (com.example.wristai.ui.TranslatePage.requested) {
            com.example.wristai.ui.TranslatePage.requested = false
            com.example.wristai.ui.Hubs.translate = 0
            go(Tab.TRANSLATE.route)
        }
    }
    // 小工具点最新报告 → 打开那份报告
    LaunchedEffect(PendingOpen.meetingId) {
        PendingOpen.meetingId?.let { id -> PendingOpen.meetingId = null; route = "detail:$id" }
    }
    val overview = com.example.wristai.ui.theme.AppTheme.layout == com.example.wristai.ui.theme.LayoutMode.OVERVIEW
    // 旧版本存下来的路由（档案分页、单独的翻译页、档案管理）→ 现在都在主轴分页里
    if (route == "tab:files" || route == "files") { com.example.wristai.ui.Hubs.records = 2; route = Tab.RECORDS.route }
    if (route == "translate") route = Tab.TRANSLATE.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    // 返回键：详情 → 原分页；其他分页 → 主页
    if (route.startsWith("detail:")) BackHandler { route = lastTab }
    else if (tab != null && tab != Tab.HOME) BackHandler { go(Tab.HOME.route) }
    /** 各主轴的「設定」小分页（从共用设定页的连结过去）。 */
    fun openPillarSettings(t: Tab) {
        when (t) {
            Tab.RECORDS -> com.example.wristai.ui.Hubs.records = com.example.wristai.ui.Hubs.SETTINGS
            Tab.FOOD -> com.example.wristai.ui.Hubs.food = com.example.wristai.ui.Hubs.SETTINGS
            Tab.TRANSLATE -> com.example.wristai.ui.Hubs.translate = com.example.wristai.ui.Hubs.SETTINGS
            else -> {}
        }
        go(t.route)
    }

    Column(Modifier.fillMaxSize()) {
        AnimatedContent(route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route",
            modifier = Modifier.weight(1f)) { r ->
            when {
                r == "onboarding" -> OnboardingScreen { cloud ->
                    Settings.setOnboarded(context)
                    go(if (cloud) Tab.SETTINGS.route else Tab.HOME.route)
                }
                r == Tab.SETTINGS.route -> SettingsScreen(onOpenPillar = ::openPillarSettings)
                r == Tab.TRANSLATE.route -> com.example.wristai.ui.TranslateHub(
                    onOpen = { route = "detail:${it}" },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r == Tab.HOME.route && overview -> com.example.wristai.ui.OverviewDashboardScreen(
                    onOpen = { route = "detail:${it}" },
                    onOpenRecords = { go(Tab.RECORDS.route) },
                    onOpenFood = { go(Tab.FOOD.route) },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r == Tab.HOME.route -> DashboardScreen(
                    onOpen = { route = "detail:${it}" },
                    onOpenRecords = { go(Tab.RECORDS.route) },
                    onOpenFood = { go(Tab.FOOD.route) },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r == Tab.FOOD.route -> com.example.wristai.ui.FoodHub(
                    onOpen = { route = "detail:${it}" },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r.startsWith("detail:") -> DetailScreen(
                    id = r.removePrefix("detail:"),
                    onBack = { route = lastTab },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                else -> com.example.wristai.ui.RecordsHub(
                    onOpen = { route = "detail:${it}" },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                    onImport = { ctx, uri -> importAudio(ctx, uri) },
                    overview = overview,
                )
            }
        }
        if (tab != null) BottomNav(tab) { go(it.route) }
    }
}

fun importAudio(context: Context, uri: Uri) {
    val app = context.applicationContext
    Toast.makeText(app, "匯入中…".tr, Toast.LENGTH_SHORT).show()
    CoroutineScope(Dispatchers.IO).launch {
        val msg = runCatching { AudioImporter.import(app, uri) }
            .fold({ id ->
                val m = MeetingStore.meetings.value.firstOrNull { it.id == id }
                if (m != null && m.importedAt != null && m.importedAt - m.createdAt > 120_000)
                    "已匯入（錄音時間 ${java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(m.createdAt))}），開始處理"
                else "已匯入，開始處理".tr
            }, {
                android.util.Log.e("AudioImporter", "Import failed: ${uri}", it)
                "匯入失敗：{0}".trf(it.message)
            })
        withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }
}
