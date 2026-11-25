package com.example.wristai

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
import com.example.wristai.ui.HomeScreen
import androidx.compose.foundation.layout.Column
import com.example.wristai.ui.FoodScreen
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

    override fun onStart() { super.onStart(); isVisible = true }
    override fun onStop() { isVisible = false; super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 浅色主题 → 状态列 / 导航列用深色图示
        AppTheme.mode = ThemeMode.from(Settings.themeMode(this))
        AppTheme.layout = com.example.wristai.ui.theme.LayoutMode.from(Settings.layoutMode(this))
        com.example.wristai.ui.UiMode.load(this)
        SpeechLang.load(this)
        applySystemBars(dark = false)
        MeetingStore.reload(this)
        CallRecordingWatcher.schedule(this)
        WatchNotifier.broadcastTheme(this)
        handleShare(intent)
        setContent {
            WristAI_ProjectTheme(AppTheme.mode, onDarkChanged = ::applySystemBars) {
                Box(Modifier.fillMaxSize().background(Brand.Backdrop).safeDrawingPadding()) { App() }
            }
        }
    }

    /** 状态列 / 导航列图示：深色主题用浅色图示，浅色主题用深色图示。 */
    private var barsDark: Boolean? = null
    private fun applySystemBars(dark: Boolean) {
        if (barsDark == dark) return
        barsDark = dark
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** 从录音机等 App「分享」音频到 WristAI。 */
    private fun handleShare(intent: Intent?) {
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
    val overview = com.example.wristai.ui.theme.AppTheme.layout == com.example.wristai.ui.theme.LayoutMode.OVERVIEW
    // 快錄排布没有「檔案」分页：从總覽换过来时停在档案分页 → 回今天
    if (!overview && route == Tab.FILES.route) route = Tab.HOME.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    // 返回键：详情 → 原分页；其他分页 → 主页
    if (route.startsWith("detail:")) BackHandler { route = lastTab }
    else if (tab != null && tab != Tab.HOME) BackHandler { go(Tab.HOME.route) }

    Column(Modifier.fillMaxSize()) {
        AnimatedContent(route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route",
            modifier = Modifier.weight(1f)) { r ->
            when {
                r == "onboarding" -> OnboardingScreen { cloud ->
                    Settings.setOnboarded(context)
                    go(if (cloud) Tab.SETTINGS.route else Tab.HOME.route)
                }
                r == Tab.SETTINGS.route -> SettingsScreen()
                // 档案管理：资料库右上的「管理」打开（不是分页）
                r == "files" -> com.example.wristai.ui.FilesScreen(onBack = { route = lastTab }, onOpen = { route = "detail:$it" }, onOpenSettings = { go(Tab.SETTINGS.route) })
                // 總覽排布：档案是一个分页
                r == Tab.FILES.route -> com.example.wristai.ui.FilesScreen(onBack = null, onOpen = { route = "detail:$it" }, onOpenSettings = { go(Tab.SETTINGS.route) })
                r == Tab.HOME.route && overview -> com.example.wristai.ui.OverviewDashboardScreen(
                    onOpen = { route = "detail:$it" },
                    onOpenRecords = { go(Tab.RECORDS.route) },
                    onOpenFood = { go(Tab.FOOD.route) },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r == Tab.HOME.route -> DashboardScreen(
                    onOpen = { route = "detail:$it" },
                    onOpenRecords = { go(Tab.RECORDS.route) },
                    onOpenFood = { go(Tab.FOOD.route) },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r == Tab.FOOD.route -> FoodScreen(
                    onOpen = { route = "detail:$it" },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                r.startsWith("detail:") -> DetailScreen(
                    id = r.removePrefix("detail:"),
                    onBack = { route = lastTab },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                )
                else -> HomeScreen(
                    onOpen = { route = "detail:$it" },
                    onOpenSettings = { go(Tab.SETTINGS.route) },
                    onImport = { ctx, uri -> importAudio(ctx, uri) },
                    onManage = { route = "files" },
                    overview = overview,
                )
            }
        }
        if (tab != null) BottomNav(tab) { go(it.route) }
    }
}

fun importAudio(context: Context, uri: Uri) {
    val app = context.applicationContext
    Toast.makeText(app, "匯入中…", Toast.LENGTH_SHORT).show()
    CoroutineScope(Dispatchers.IO).launch {
        val msg = runCatching { AudioImporter.import(app, uri) }
            .fold({ id ->
                val m = MeetingStore.meetings.value.firstOrNull { it.id == id }
                if (m != null && m.importedAt != null && m.importedAt - m.createdAt > 120_000)
                    "已匯入（錄音時間 ${java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(m.createdAt))}），開始處理"
                else "已匯入，開始處理"
            }, {
                android.util.Log.e("AudioImporter", "Import failed: $uri", it)
                "匯入失敗：${it.message}"
            })
        withContext(Dispatchers.Main) { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
    }
}
