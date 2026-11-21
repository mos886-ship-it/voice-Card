package com.example.wristai.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import com.example.voiceai.shared.MeetingSummary
import androidx.wear.compose.material3.timeTextCurvedText
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.TitleCard
import com.example.voiceai.shared.WristAiProtocol
import com.example.wristai.wear.R
import com.example.wristai.wear.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手表主画面（Wear OS 专用元件）：
 * - 上方弯曲的时间（TimeText），跟系统 App 一样；
 * - 内容是会随圆形萤幕缩放、可用表圈 / 旋钮卷动的清单（TransformingLazyColumn）；
 * - 主要动作「錄音 / 停止」是画面中央的大圆钮（录音 App 最重要的按钮要一打开就看得到）；
 * - 颜色跟着手机的主题（水墨 / 深色）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchState.load(this)
        Brand.load(this)   // 主题跟着手机（水墨 / 深色）
        setContent { WatchTheme { WatchApp() } }
    }
}

/** 把品牌色套进 Wear Material 3 的配色（按钮、卡片、时间都会跟着换）。 */
@Composable
private fun WatchTheme(content: @Composable () -> Unit) {
    val scheme = ColorScheme(
        primary = if (Brand.isInk) Brand.Text else Brand.Cyan,
        onPrimary = if (Brand.isInk) Brand.BgCenter else Color.Black,
        primaryContainer = Brand.SurfaceHigh,
        onPrimaryContainer = Brand.Text,
        secondary = Brand.Violet,
        onSecondary = Color.White,
        background = Brand.Bg,
        onBackground = Brand.Text,
        onSurface = Brand.Text,
        onSurfaceVariant = Brand.TextMuted,
        surfaceContainer = Brand.Surface,
        surfaceContainerHigh = Brand.SurfaceHigh,
        surfaceContainerLow = Brand.Surface,
        outline = Brand.Outline,
        error = Brand.Recording,
        onError = Color.White,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun WatchApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder by WatchState.recorder.collectAsState()
    val phoneStatus by WatchState.phoneStatus.collectAsState()
    val summaries by WatchState.summaries.collectAsState()
    val foodToday by WatchState.foodToday.collectAsState()
    val pending by WatchState.pendingCount.collectAsState()
    val phoneRec by WatchState.phoneRecordingSince.collectAsState()
    val pendingFood = remember { mutableStateOf(false) }
    // 打开 App 时问手机：是不是正在录音？主题是什么？
    LaunchedEffect(Unit) { runCatching { PhoneLink.sendPhoneCommand(context, WristAiProtocol.CMD_QUERY) } }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        // GPS 戳记（可拒绝，录音照常）
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) RecordingService.start(context, pendingFood.value)
        else WatchState.setRecorder(RecorderStatus.Failed("需要麥克風權限才能錄音"))
    }
    val isRecording = recorder is RecorderStatus.Recording
    val sending = recorder is RecorderStatus.Sending
    // 手机在录（手表自己没在录）：主要按钮变成「停止手机录音」
    val phoneMode = phoneRec != null && !isRecording
    val busy = isRecording || phoneMode || sending
    fun startWatch(food: Boolean) { pendingFood.value = food; permissionLauncher.launch(permissions) }
    fun phoneCommand(cmd: String, waiting: String) {
        WatchState.setPhoneStatus(waiting)
        scope.launch { runCatching { PhoneLink.sendPhoneCommand(context, cmd) }.onFailure { WatchState.setPhoneStatus("找不到手機") } }
    }

    // 两个左右滑的分页：① 录音（手表 / 饮食 / 手机录音都在这页）② 摘要（重送、最近摘要）
    val pagerState = rememberPagerState(pageCount = { 2 })
    // 正在看哪一笔摘要的明细（null = 没在看）
    var openId by remember { mutableStateOf<String?>(null) }
    // 报告书：查询条件、月份模式、各查询的结果（手机回传）
    var query by remember { mutableStateOf(ListQuery()) }
    var monthMode by remember { mutableStateOf(false) }
    val lists by WatchState.lists.collectAsState()
    val listResult = lists[query.key]
    var listLoading by remember { mutableStateOf(false) }
    var listJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun fetchList(offset: Int) {
        listJob?.cancel()
        listJob = scope.launch {
            listLoading = true
            runCatching { PhoneLink.requestList(context, query.request(offset)) }
            // 等手机回（最多 5 秒；回了就提早结束）
            val before = WatchState.lists.value[query.key]
            for (i in 0 until 25) { delay(200); if (WatchState.lists.value[query.key] !== before) break }
            listLoading = false
        }
    }
    // 滑到报告书、或换了查询条件，就跟手机要最新清单；手机不在就用上次存的
    LaunchedEffect(pagerState.currentPage, query) { if (pagerState.currentPage == 1) fetchList(0) }
    // 🔍 语音 / 键盘输入关键字（Wear OS 标准输入画面：说话、键盘、手写）
    val searchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { android.app.RemoteInput.getResultsFromIntent(it)?.getCharSequence("q")?.toString() }?.trim()
        if (!text.isNullOrEmpty()) { query = query.copy(q = text, month = ""); monthMode = false }
    }
    fun openSearch() {
        val intent = androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent()
        androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(intent,
            listOf(android.app.RemoteInput.Builder("q").setLabel("說出或輸入關鍵字").build()))
        runCatching { searchLauncher.launch(intent) }
    }
    AppScaffold(
        timeText = { TimeText(backgroundColor = Color.Transparent) { time -> timeTextCurvedText(time, CurvedTextStyle(color = Brand.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)) } },
        modifier = Modifier.background(Brush.radialGradient(listOf(Brand.BgCenter, Brand.Bg))),
    ) {
        // 录音中：固定的全萤幕录音画面（不卷动、不能滑走），像系统录音机——计时、停止钮都在正中央
        if (isRecording || phoneMode) {
            RecordingScreen(
                recorder = recorder,
                phoneRec = phoneRec?.takeIf { phoneMode },
                phoneStatus = phoneStatus,
                onStop = {
                    if (phoneMode) phoneCommand(WristAiProtocol.CMD_STOP, "正在停止手機錄音…")
                    else RecordingService.stop(context)
                },
            )
            return@AppScaffold
        }
        openId?.let { id ->
            DetailScreen(id, summaries.firstOrNull { it.recordingId == id }, WatchState.findEntry(id), onBack = { openId = null })
            return@AppScaffold
        }
        BackHandler(enabled = pagerState.currentPage == 1 && (monthMode || query.filtered)) {
            if (monthMode) monthMode = false else query = ListQuery(kind = query.kind)
        }
        HorizontalPagerScaffold(
            pagerState = pagerState,
            pageIndicator = { HorizontalPageIndicator(pagerState, selectedColor = Brand.Text, unselectedColor = Brand.TextFaint) },
        ) {
            HorizontalPager(state = pagerState) { page ->
                AnimatedPage(page, pagerState) {
                    when (page) {
                        0 -> RecordPage(
                            recorder, phoneStatus, sending, foodToday,
                            onRecord = { if (!sending) startWatch(food = false) },
                            onFood = { if (!sending) startWatch(food = true) },
                            onPhone = { phoneCommand(WristAiProtocol.CMD_START, "正在啟動手機錄音…") },
                        )
                        else -> SummaryPage(
                            pending = pending,
                            query = query,
                            result = listResult,
                            loading = listLoading,
                            monthMode = monthMode,
                            onSearch = ::openSearch,
                            onToggleMonths = { monthMode = !monthMode },
                            onKind = { query = query.copy(kind = it) },
                            onPickMonth = { query = query.copy(month = it); monthMode = false },
                            onClear = { query = ListQuery(kind = query.kind); monthMode = false },
                            onMore = { fetchList(listResult?.items?.size ?: 0) },
                            onOpen = { openId = it },
                            onResend = {
                                scope.launch {
                                    WatchState.setRecorder(RecorderStatus.Sending)
                                    val result = runCatching { PhoneLink.sendPending(context) }
                                    WatchState.setRecorder(if (result.isSuccess) RecorderStatus.Sent("") else RecorderStatus.Failed("重送失敗，請確認手機連線"))
                                    if (result.isSuccess) WatchState.setPhoneStatus("已傳到手機，等待 AI 處理…")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * ① 录音分页（主页，整页固定不卷动）：所有「录音」都在这一页——
 * 中央大圆钮 = 手表录音；左边小钮 = 饮食记录（外圈是今天热量进度）；右边小钮 = 用手机录音。
 */
@Composable
private fun RecordPage(
    recorder: RecorderStatus, phoneStatus: String?, sending: Boolean, food: WatchState.FoodToday?,
    onRecord: () -> Unit, onFood: () -> Unit, onPhone: () -> Unit,
) {
    val today = food?.takeIf { it.day == WatchState.todayStart() }
    val goal = (food?.goal ?: 2000).coerceAtLeast(1)
    val kcal = today?.kcal ?: 0
    Column(
        Modifier.fillMaxSize().padding(top = 28.dp, bottom = 20.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandHeader()
        Hero(recorder = recorder, phoneRec = null, phoneStatus = phoneStatus)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            SideAction(
                label = if (kcal > 0) "$kcal 大卡" else "飲食",
                color = Brand.Success, enabled = !sending, onClick = onFood,
                progress = if (today != null) (kcal.toFloat() / goal).coerceIn(0f, 1f) else null,
                over = kcal > goal,
            ) { c -> BowlIcon(c, 22.dp) }
            Box(Modifier.width(96.dp)) { RecordCircle(recording = false, sending = sending, onClick = onRecord, compact = true) }
            SideAction(label = "手機", color = Brand.Blue, enabled = !sending, onClick = onPhone) { c ->
                PhoneGlyph(c, 24.dp, dotColor = if (c == Color.White) Brand.Recording else Color.Unspecified)
            }
        }
    }
}

/** 大圆钮两旁的小圆钮（下方有小字）；[progress] 不是 null 就在外圈画进度环。 */
@Composable
private fun SideAction(
    label: String, color: Color, enabled: Boolean, onClick: () -> Unit,
    progress: Float? = null, over: Boolean = false,
    icon: @Composable (Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val sw = 3.dp.toPx(); val inset = sw / 2
                val arc = Size(size.width - sw, size.height - sw)
                drawArc(Brand.Outline, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(if (progress != null) sw else 1.dp.toPx()))
                if (progress != null && progress > 0f) drawArc(if (over) Brand.Warning else color, -90f, 360f * progress, false,
                    Offset(inset, inset), arc, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (enabled) color else Brand.SurfaceHigh)
                    .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier),
                contentAlignment = Alignment.Center,
            ) { icon(if (enabled) Color.White else Brand.TextMuted) }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = if (over) Brand.Warning else Brand.TextMuted, maxLines = 1)
    }
}

/** 报告书的查询条件：关键字 / 类别（all、rec、food）/ 月份（"2026-11"）。 */
private data class ListQuery(val q: String = "", val kind: String = "all", val month: String = "") {
    /** 预设查询的代号是空字串（手机主动推的清单也是这个代号）。 */
    val key: String get() = if (q.isEmpty() && kind == "all" && month.isEmpty()) "" else "$kind|$month|$q"
    val filtered: Boolean get() = q.isNotEmpty() || month.isNotEmpty()
    fun request(offset: Int): String = org.json.JSONObject()
        .put("q", q).put("kind", kind).put("month", month)
        .put("offset", offset).put("limit", 30).put("key", key).toString()
}

/** 报告书标题两旁的小圆钮（有在用的筛选会亮起来）。 */
@Composable
private fun HeaderIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (active) Brand.Cyan else Brand.SurfaceHigh).clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = if (active) Brand.Bg else Brand.Cyan) }
}

/** "2026-11" → "26年11月" */
private fun monthLabel(ym: String): String {
    val p = ym.split('-'); if (p.size != 2) return ym
    return "${p[0].takeLast(2)}年${p[1].trimStart('0')}月"
}

/**
 * ② 报告书：报告上百笔也找得到——
 * 🔍 搜寻（说出或输入关键字，手机比对标题、报告内容、逐字稿、说话者）、📅 依月份、类别（全部 / 錄音 / 飲食），
 * 清单一次载入 30 笔，底下「顯示更多」。每列只有来源图示＋标题＋时间 · 长度，点进去才显示内容。
 */
@Composable
private fun SummaryPage(
    pending: Int, query: ListQuery, result: WatchState.ListResult?, loading: Boolean, monthMode: Boolean,
    onSearch: () -> Unit, onToggleMonths: () -> Unit, onKind: (String) -> Unit, onPickMonth: (String) -> Unit,
    onClear: () -> Unit, onMore: () -> Unit, onOpen: (String) -> Unit, onResend: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val items = result?.items ?: emptyList()
    val groups = remember(items) { items.groupBy { dayLabel(it.recordedAt) }.toList() }
    // 换查询 / 切换月份模式时回到最上面
    LaunchedEffect(query, monthMode) { runCatching { listState.scrollToItem(0) } }
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            // 标题列：🔍 搜寻　報告書　📅 月份（小圆钮，省一整列，结果能早点出现）
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    HeaderIconButton(Icons.Filled.Search, "搜尋", active = query.q.isNotEmpty(), onClick = onSearch)
                    Text("報告書", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.Text,
                        modifier = Modifier.padding(horizontal = 14.dp))
                    HeaderIconButton(Icons.Filled.DateRange, "月份", active = monthMode || query.month.isNotEmpty(), onClick = onToggleMonths)
                }
            }
            // 类别
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                    for ((k, label) in listOf("all" to "全部", "rec" to "錄音", "food" to "飲食")) {
                        val on = query.kind == k
                        Box(
                            Modifier.clip(CircleShape).background(if (on) Brand.Text else Brand.SurfaceHigh)
                                .clickableNoRipple { onKind(k) }.padding(horizontal = 9.dp, vertical = 5.dp),
                        ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) Brand.Bg else Brand.TextMuted) }
                    }
                }
            }
            // 目前的筛选（点一下清除）
            if (query.filtered) item {
                Button(
                    onClick = onClear, modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Filled.Close, null, tint = Brand.Warning) },
                    secondaryLabel = { Text(if (result != null) "共 ${result.total} 筆 · 點此清除" else "點此清除") },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Text(listOfNotNull(query.q.takeIf { it.isNotEmpty() }?.let { "「$it」" }, query.month.takeIf { it.isNotEmpty() }?.let(::monthLabel))
                        .joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (pending > 0 && !query.filtered && !monthMode) item {
                Button(
                    onClick = onResend,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Filled.Refresh, null, tint = Brand.Warning) },
                    secondaryLabel = { Text("手機連上後送出") },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text("重送 $pending 段") }
            }
            if (monthMode) {
                // 📅 依月份：每个月几笔，点了只看那个月
                val months = result?.months ?: emptyList()
                if (months.isEmpty()) item {
                    Text(if (loading) "正在從手機取得…" else "沒有資料", fontSize = 12.sp, color = Brand.TextMuted,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                for ((ym, count) in months) item(key = "m_$ym") {
                    Button(
                        onClick = { onPickMonth(ym) }, modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Filled.DateRange, null, tint = Brand.TextMuted) },
                        secondaryLabel = { Text("$count 筆") },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) { Text(monthLabel(ym)) }
                }
            } else {
                if (items.isEmpty()) item {
                    Text(
                        when {
                            loading -> "正在從手機取得…"
                            query.filtered || query.kind != "all" -> "找不到符合的報告"
                            else -> "還沒有錄音。\n錄完後，手機整理好的報告會列在這裡。"
                        },
                        fontSize = 12.sp, color = if (loading) Brand.Cyan else Brand.TextMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                for ((day, list) in groups) {
                    item(key = "h_$day") {
                        Text(day, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Brand.TextMuted,
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
                    }
                    for (e in list) item(key = e.id) {
                        val ready = e.status == "DONE"
                        Button(
                            onClick = { onOpen(e.id) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = { KindIcon(e.kind) },
                            secondaryLabel = {
                                Text(
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(e.recordedAt)) +
                                        durationText(e.durationSec) + if (ready) "" else " · ${e.status}",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = when { ready || e.status == "已存錄音機" -> Brand.TextMuted; e.status == "失敗" -> Brand.Warning; else -> Brand.Cyan },
                                )
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                        ) { Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                // 分页：一次 30 笔
                if (result != null && items.size < result.total) item {
                    Button(
                        onClick = onMore, modifier = Modifier.fillMaxWidth(), enabled = !loading,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) { Text(if (loading) "載入中…" else "顯示更多（還有 ${result.total - items.size} 筆）", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** 清单分组标题：今天 26/11/19 / 昨天 26/11/18 / 26/11/17 週一。 */
private fun dayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = WatchState.todayStart()
    return when {
        ms >= today -> "今天 " + java.text.SimpleDateFormat("yy/M/d", java.util.Locale.TAIWAN).format(java.util.Date(ms))
        ms >= today - 86_400_000L -> "昨天 " + java.text.SimpleDateFormat("yy/M/d", java.util.Locale.TAIWAN).format(java.util.Date(ms))
        else -> {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            val week = "日一二三四五六"[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            val fmt = "yy/M/d"   // 一律带两位数年份
            java.text.SimpleDateFormat(fmt, java.util.Locale.TAIWAN).format(java.util.Date(ms)) + " 週$week"
        }
    }
}

/** 清单上的来源图示：手表 / 手机 / 饮食 / 通话 / 汇入。 */
@Composable
private fun KindIcon(kind: String) {
    val c = Brand.TextMuted
    when (kind) {
        "food" -> BowlIcon(Brand.Success, 20.dp)
        "phone" -> PhoneGlyph(c, 22.dp)
        "call" -> Icon(Icons.Filled.Call, null, Modifier.size(18.dp), tint = c)
        "import" -> Canvas(Modifier.size(20.dp)) {
            // 汇入：向下箭头进到托盘
            val w = size.width; val h = size.height; val sw = w * 0.1f
            drawLine(c, Offset(w / 2, h * 0.08f), Offset(w / 2, h * 0.58f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.3f, h * 0.4f), Offset(w / 2, h * 0.6f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.7f, h * 0.4f), Offset(w / 2, h * 0.6f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.12f, h * 0.62f), Offset(w * 0.12f, h * 0.9f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.12f, h * 0.9f), Offset(w * 0.88f, h * 0.9f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.88f, h * 0.62f), Offset(w * 0.88f, h * 0.9f), sw, StrokeCap.Round)
        }
        else -> WatchGlyph(c, 22.dp)
    }
}


/** 时间戳记：两位数年份，例如 26/11/18 18:06（[seconds] = 加秒）。 */
private fun stamp(ms: Long, seconds: Boolean = false): String {
    val sameYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ==
        java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
    val p = "yy/M/d " + (if (seconds) "HH:mm:ss" else "HH:mm")   // 一律带两位数年份，例如 26/11/19 00:13
    return java.text.SimpleDateFormat(p, java.util.Locale.TAIWAN).format(java.util.Date(ms))
}

/** 「 · 12:34」录音长度；不知道就空字串。 */
private fun durationText(sec: Double): String {
    val s = sec.toInt(); if (s <= 0) return ""
    return if (s >= 3600) " · %d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else " · %d:%02d".format(s / 60, s % 60)
}

/** 报告里的一小块，手表上一块一个清单项目（表圈卷动时会跟着缩放）。 */
private sealed class Block {
    data class Header(val text: String) : Block()
    data class Para(val text: String) : Block()
    data class Bullet(val text: String) : Block()
    /** 表格的一列：第一格当标题，其余是「栏名：值」。 */
    data class Row(val head: String, val rest: List<String>) : Block()
}

private val EMPTY_CELLS = setOf("", "無", "无", "未指定", "未知", "-", "—", "N/A")

/**
 * 把手机的 Markdown 报告转成手表好读的小块：
 * 去掉大标题（上面已经显示）、内容只有「無」的段落整段略过、表格每列变一张小卡、去掉 ** 粗体记号。
 */
private fun reportBlocks(markdown: String): List<Block> {
    fun clean(s: String) = s.replace("**", "").replace("`", "").trim()
    val out = mutableListOf<Block>()
    var section: String? = null
    var body = mutableListOf<Block>()
    var tableHeader: List<String>? = null
    fun flush() {
        if (section != null && body.isNotEmpty()) { out += Block.Header(section!!); out += body }
        else if (section == null) out += body
        body = mutableListOf(); tableHeader = null
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("# ") -> Unit
            line.startsWith("#") -> { flush(); section = clean(line.trimStart('#')) }
            line.startsWith("|") -> {
                val cells = line.trim('|').split('|').map { clean(it) }
                if (cells.all { c -> c.all { it == '-' || it == ':' || it == ' ' } }) continue   // 分隔线
                if (tableHeader == null) { tableHeader = cells; continue }
                if (cells.first() in EMPTY_CELLS) continue
                val rest = cells.drop(1).mapIndexedNotNull { i, v ->
                    if (v in EMPTY_CELLS) null else (tableHeader!!.getOrNull(i + 1)?.let { "$it：" } ?: "") + v
                }
                body += Block.Row(cells.first(), rest)
            }
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> {
                val t = clean(line.drop(2)); if (t !in EMPTY_CELLS) body += Block.Bullet(t)
            }
            Regex("^\\d+[.、)]\\s*").containsMatchIn(line) -> {
                val t = clean(line.replace(Regex("^\\d+[.、)]\\s*"), "")); if (t !in EMPTY_CELLS) body += Block.Bullet(t)
            }
            line.isEmpty() -> Unit
            else -> { val t = clean(line); if (t !in EMPTY_CELLS) body += Block.Para(t) }
        }
    }
    flush()
    return out
}

/**
 * 摘要明细：点「最近摘要」的卡片进来，跟手机要完整报告（摘要、说话者、重点、决定、待办…）。
 * 先显示存档，手机回传后更新；向右滑回到摘要页。
 */
@Composable
private fun DetailScreen(id: String, summary: MeetingSummary?, entry: WatchState.Entry?, onBack: () -> Unit) {
    val context = LocalContext.current
    val details by WatchState.details.collectAsState()
    val d = details[id]
    var waiting by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        WatchState.loadDetail(context, id)
        waiting = true; error = null
        runCatching { PhoneLink.requestDetail(context, id) }.onFailure { error = "找不到手機，先顯示存下來的內容" }
        delay(8000)
        waiting = false
    }
    BackHandler(onBack = onBack)
    val blocks = remember(d?.markdown) { d?.markdown?.let(::reportBlocks) ?: emptyList() }
    val listState = rememberTransformingLazyColumnState()
    // 第一次从手机拿到内容时，清单项目整个换掉，捲动位置会跑掉（标题被推到时间底下）→ 回到最上面
    LaunchedEffect(d?.status) { if (d != null) runCatching { listState.scrollToItem(0) } }
    // 往左或往右滑都回到清单（直向卷动不受影响：横向位移超过 56dp 才算）
    val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    var dragX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragX = 0f },
                onDragEnd = { if (kotlin.math.abs(dragX) > swipe) onBack() },
            ) { change, amount -> dragX += amount; change.consume() }
        },
    ) {
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                Text(d?.title?.takeIf { it.isNotBlank() } ?: summary?.title ?: entry?.title ?: "錄音明細",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Brand.Text, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
            // 两个时间戳记分开：录音时间（录音档本身）＋ 报告整理好的时间
            val recAt = d?.createdAt?.takeIf { it > 0 } ?: summary?.recordedAt?.takeIf { it > 0 } ?: entry?.recordedAt
            val dur = d?.durationSec?.takeIf { it > 0 } ?: summary?.durationSec?.takeIf { it > 0 } ?: entry?.durationSec ?: 0.0
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // 录音起讫：开始 – 最后录到的时间（开始 + 长度）
                    if (recAt != null) {
                        val end = recAt + (dur * 1000).toLong()
                        val sameDay = stamp(recAt).substringBefore(' ') == stamp(end).substringBefore(' ')
                        Text("錄音 " + stamp(recAt, seconds = true) + " –" + (if (sameDay) "" else "\n" + stamp(end).substringBefore(' ')) +
                            " " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.TAIWAN).format(java.util.Date(end)),
                            fontSize = 11.sp, color = Brand.TextMuted, textAlign = TextAlign.Center)
                    }
                    val meta = listOfNotNull(
                        durationText(dur).removePrefix(" · ").takeIf { it.isNotEmpty() }?.let { "長度 $it" },
                        summary?.createdAt?.let { "報告 " + stamp(it) },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, fontSize = 11.sp, color = Brand.TextFaint, textAlign = TextAlign.Center)
                    // GPS 戳记：地名（没有地名就显示座标）
                    d?.geo?.let { g ->
                        Text("📍 " + (g.place ?: "%.4f, %.4f".format(g.lat, g.lon)), fontSize = 11.sp, color = Brand.TextMuted,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            when {
                d == null || d.status == "找不到" -> {
                    item {
                        Text(when {
                            d?.status == "找不到" -> "手機上已經沒有這筆錄音"
                            waiting && error == null -> "正在從手機取得明細…"
                            else -> error ?: "手機沒有回應"
                        }, fontSize = 12.sp, color = if (waiting && error == null) Brand.Cyan else Brand.Warning,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    summary?.let { s -> item { Text(s.summary, fontSize = 13.sp, color = Brand.Text, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) } }
                }
                d.status != "DONE" -> item {
                    Text(if (d.status.contains("錄音機")) "已存到手機的三星錄音機，\n可在手機用 Galaxy AI 整理" else "手機還在整理：${d.status}", fontSize = 12.sp, color = Brand.Cyan, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                else -> for (b in blocks) item {
                    when (b) {
                        is Block.Header -> ListHeader { Text(b.text) }
                        is Block.Para -> Text(b.text, fontSize = 13.sp, lineHeight = 18.sp, color = Brand.Text,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp))
                        is Block.Bullet -> Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("•", fontSize = 13.sp, color = Brand.TextMuted, modifier = Modifier.padding(end = 6.dp))
                            Text(b.text, fontSize = 13.sp, lineHeight = 18.sp, color = Brand.Text)
                        }
                        is Block.Row -> Column(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                                .background(Brand.Surface).padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(b.head, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.Text)
                            for (r in b.rest) Text(r, fontSize = 11.sp, color = Brand.TextMuted)
                        }
                    }
                }
            }
            if (!waiting && (d == null || error != null)) item {
                Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Filled.Refresh, null) }, colors = ButtonDefaults.filledTonalButtonColors()) { Text("重新取得") }
            }
        }
    }
    }
}

/** 品牌：dw 书法印章 logo ＋「大D」。 */
@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(22.dp).clip(CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("大D", fontSize = 13.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Brand.TextMuted)
    }
}

/** 中间的主状态：待机标题 / 录音计时（脉冲红点）/ 传送中（旋转弧）/ 结果。 */
@Composable
private fun Hero(recorder: RecorderStatus, phoneRec: Long?, phoneStatus: String?) {
    val t = rememberInfiniteTransition(label = "hero")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            recorder is RecorderStatus.Sending -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(14.dp)) {
                        drawArc(Brand.Outline, 0f, 360f, false, style = Stroke(2.dp.toPx()))
                        drawArc(Brand.Accent, spin, 100f, false, topLeft = Offset.Zero, size = Size(size.width, size.height), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("正在傳送到手機…", fontSize = 12.sp, color = Brand.Cyan)
                }
            }
            else -> {
                val (msg, color) = when (recorder) {
                    is RecorderStatus.Sent -> (phoneStatus ?: "✓ 已傳送到手機") to Brand.Success
                    is RecorderStatus.Failed -> recorder.message to Brand.Warning
                    else -> (phoneStatus ?: "點一下開始錄音") to if (phoneStatus != null) Brand.Cyan else Brand.TextMuted
                }
                Text(msg, fontSize = 12.sp, lineHeight = 16.sp, color = color, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * 录音中的全萤幕画面（不卷动）：上方状态（脉冲红点＋录音中）、大计时、中央停止钮、下方同步提示。
 * 内容整组置中，上方留出 TimeText 的位置，圆形萤幕上不会被切到。
 */
@Composable
private fun RecordingScreen(recorder: RecorderStatus, phoneRec: Long?, phoneStatus: String?, onStop: () -> Unit) {
    val t = rememberInfiniteTransition(label = "recScreen")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")
    val r = recorder as? RecorderStatus.Recording
    val since = phoneRec ?: r?.startedAt ?: System.currentTimeMillis()
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now.longValue = System.currentTimeMillis(); delay(250) } }
    val sec = ((now.longValue - since) / 1000).coerceAtLeast(0)
    Column(
        Modifier.fillMaxSize().padding(top = 30.dp, bottom = 14.dp, start = 22.dp, end = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(12.dp)) {
                drawCircle(Brand.Recording.copy(alpha = 0.35f * (1 - pulse)), radius = size.minDimension / 2 * (0.5f + pulse * 0.5f))
                drawCircle(Brand.Recording, radius = size.minDimension / 4)
            }
            Spacer(Modifier.width(5.dp))
            Text(when { phoneRec != null -> "手機錄音中"; r?.food == true -> "飲食記錄中"; else -> "錄音中" },
                fontSize = 12.sp, color = Brand.Recording, fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60),
            fontSize = if (sec >= 3600) 28.sp else 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Brand.Text,
        )
        RecordCircle(recording = true, sending = false, onClick = onStop, compact = true)
        Text(
            when {
                phoneRec != null -> phoneStatus ?: "點一下停止"
                r?.food == true -> "說完按停止"
                r != null && r.waiting > 0 -> "${r.waiting} 段待傳"
                r != null && r.synced > 0 -> "已同步 ${r.synced} 段"
                else -> "每 5 分鐘同步到手機"
            },
            fontSize = 11.sp, color = if (r != null && r.waiting > 0 && !r.food) Brand.Warning else Brand.TextMuted,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 手表图示：圆形表面＋上下表带，表面中间一个录音点（＝用手表录）。 */
@Composable
private fun WatchGlyph(color: Color, iconSize: androidx.compose.ui.unit.Dp, dotColor: Color = Color.Unspecified) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width; val h = size.height; val sw = w * 0.085f
        val strapW = w * 0.40f; val r = w * 0.30f
        val cr = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        drawRoundRect(color, Offset((w - strapW) / 2, 0f), Size(strapW, h * 0.24f), cr)          // 上表带
        drawRoundRect(color, Offset((w - strapW) / 2, h * 0.76f), Size(strapW, h * 0.24f), cr)   // 下表带
        drawCircle(color, radius = r, center = Offset(w / 2, h / 2), style = Stroke(sw))           // 表框
        drawLine(color, Offset(w / 2 + r + sw * 0.2f, h * 0.44f), Offset(w / 2 + r + sw * 0.9f, h * 0.44f), sw * 0.9f, StrokeCap.Round) // 侧键
        drawCircle(if (dotColor == Color.Unspecified) color else dotColor, radius = r * 0.40f, center = Offset(w / 2, h / 2))   // 录音点（红）
    }
}

/** 手机图示：直立圆角长方形＋上方听筒，萤幕中间一个录音点（＝用手机录）。 */
@Composable
private fun PhoneGlyph(color: Color, iconSize: androidx.compose.ui.unit.Dp, dotColor: Color = Color.Unspecified) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width; val h = size.height; val sw = w * 0.085f
        val bw = w * 0.56f; val left = (w - bw) / 2
        drawRoundRect(color, Offset(left, h * 0.06f), Size(bw, h * 0.88f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(sw))
        drawLine(color, Offset(w * 0.43f, h * 0.17f), Offset(w * 0.57f, h * 0.17f), sw * 0.9f, StrokeCap.Round) // 听筒
        drawCircle(if (dotColor == Color.Unspecified) color else dotColor, radius = bw * 0.24f, center = Offset(w / 2, h * 0.53f))   // 录音点（红）
    }
}

/** 饮食图示：一个碗＋两缕热气。 */
@Composable
private fun BowlIcon(color: Color, iconSize: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = size.width; val h = size.height; val s = w * 0.07f
        // 碗身（半圆）＋碗沿
        drawArc(color, 0f, 180f, true, Offset(w * 0.08f, h * 0.30f), Size(w * 0.84f, h * 0.62f))
        drawLine(color, Offset(w * 0.02f, h * 0.61f), Offset(w * 0.98f, h * 0.61f), s, StrokeCap.Round)
        // 三缕直立的 S 形热气（之前用弧线，看起来像眯眼笑脸）
        for (x in listOf(0.32f, 0.5f, 0.68f)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * x, h * 0.50f)
                cubicTo(w * (x - 0.09f), h * 0.40f, w * (x + 0.09f), h * 0.28f, w * x, h * 0.16f)
            }
            drawPath(path, color, style = Stroke(s * 0.75f, cap = StrokeCap.Round))
        }
    }
}

/** 中央大圆钮：待机 = 品牌渐层＋麦克风；录音中 = 红色停止＋扩散光环；传送中 = 灰色。 */
@Composable
private fun RecordCircle(recording: Boolean, sending: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    val t = rememberInfiniteTransition(label = "rec")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    val btn = if (compact) 64.dp else 76.dp
    val area = if (compact) 92.dp else 104.dp
    Box(Modifier.fillMaxWidth().height(area), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(area)) {
            val r = btn.toPx() / 2
            if (recording) for (k in 0..1) {
                val p = (pulse + k * 0.5f) % 1f
                drawCircle(Brand.Recording.copy(alpha = 0.45f * (1 - p)), radius = r + p * 13.dp.toPx(), style = Stroke(2.dp.toPx()))
            } else drawCircle(Brand.Outline, radius = r + 6.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        Box(
            Modifier.size(btn).clip(CircleShape)
                .background(when { recording -> Brand.RecordBrush; sending -> Brush.linearGradient(listOf(Brand.SurfaceHigh, Brand.SurfaceHigh)); else -> Brand.Accent })
                .then(if (sending) Modifier else Modifier.clickableNoRipple(onClick)),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(Color.White))
            else WatchGlyph(if (sending) Brand.TextMuted else Color.White, if (compact) 30.dp else 34.dp, dotColor = if (sending) Color.Unspecified else Brand.Recording)
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

