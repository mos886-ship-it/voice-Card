package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.wristai.ui.theme.Brand
import kotlin.math.roundToInt

/** 报告的区块（简易 Markdown 解析结果）。 */
sealed interface ReportBlock {
    data class Heading(val level: Int, val text: String) : ReportBlock
    data class Paragraph(val text: String) : ReportBlock
    data class Bullet(val text: String, val ordered: String?) : ReportBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : ReportBlock
}

/** 只支持报告会用到的 Markdown：标题、段落、清单、表格、粗体。 */
fun parseReport(markdown: String): List<ReportBlock> {
    val out = mutableListOf<ReportBlock>()
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val t = lines[i].trim()
        when {
            t.isEmpty() || t == "---" -> {}
            t.startsWith("#") -> {
                val level = t.takeWhile { it == '#' }.length
                out += ReportBlock.Heading(level, t.drop(level).trim())
            }
            t.startsWith("|") -> {
                val table = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (!cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }) table += cells
                    i++
                }
                if (table.isNotEmpty()) out += ReportBlock.Table(table.first(), table.drop(1))
                continue
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") ->
                out += ReportBlock.Bullet(t.drop(2).trim(), null)
            Regex("^\\d+[.)] ").containsMatchIn(t) -> {
                val num = t.substringBefore(' ').trimEnd('.', ')')
                out += ReportBlock.Bullet(t.substringAfter(' ').trim(), num)
            }
            else -> out += ReportBlock.Paragraph(t)
        }
        i++
    }
    return out
}

/** 把 **粗体** 转成样式。 */
fun richText(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { idx, p ->
        if (idx % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Brand.Text)) { append(p) }
        else append(p)
    }
}

/** AI 写的「不知道」：卡片上不显示这一栏。 */
private val EMPTY_VALUES = com.example.wristai.ReportLang.UNKNOWN + setOf("无", "Unassigned", "미지정")

/**
 * [names] = 使用者 / AI 定好的 代号 → 名字（报告已经套用过）；[onRenameSpeaker] 有给的话，
 * 「說話者」表格的卡片可以点来命名（没有名字的卡片会显示「＋ 命名」）。
 */
@Composable
fun ReportView(
    markdown: String, modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(), onRenameSpeaker: ((String) -> Unit)? = null,
) {
    val blocks = parseReport(markdown)
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var section = ""
            for (b in blocks) when (b) {
                is ReportBlock.Heading -> if (b.level <= 1) {
                    // 一级标题已在页面顶部显示，这里略过
                } else {
                    section = b.text
                    Row(Modifier.padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Brand.Accent))
                        Spacer(Modifier.width(10.dp))
                        Text(b.text, style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                    }
                }
                is ReportBlock.Paragraph ->
                    if (com.example.wristai.ReportLang.isSummaryHeading(section) && com.example.wristai.ReportLang.isNone(b.text))
                        Text("這份報告沒有摘要，按上方「重新產生」可以補上。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint)
                    else Text(richText(b.text), style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted)
                is ReportBlock.Bullet -> Row(Modifier.padding(start = 4.dp)) {
                    if (b.ordered != null) {
                        Text(b.ordered, style = MaterialTheme.typography.labelMedium, color = Brand.Cyan,
                            modifier = Modifier.widthIn(min = 20.dp).padding(top = 2.dp))
                    } else {
                        Box(Modifier.padding(top = 9.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(Brand.Cyan))
                    }
                    Text(richText(b.text), style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                }
                is ReportBlock.Table -> ReportTable(b, names, onRenameSpeaker)
            }
        }
    }
}

/** 表格改成卡片列：手机宽度放不下多栏表格，每一列一张小卡，第一栏当标题。 */
@Composable
private fun ReportTable(t: ReportBlock.Table, names: Map<String, String>, onRenameSpeaker: ((String) -> Unit)?) {
    // 饮食内容表格（有「熱量」栏）：用紧凑的清单，不用一栏一栏排
    if (t.header.any { it.contains("熱量") || it.contains("kcal", true) }) { FoodTable(t); return }
    // 「說話者」表格：第一栏是代号（或已套用的名字）
    val speakerTable = t.header.firstOrNull()?.let { com.example.wristai.ReportLang.isSpeakerColumn(it) } == true
    val nameCol = t.header.indexOfFirst { com.example.wristai.ReportLang.isNameColumn(it) }
    val rows = t.rows.filter { r -> r.any { c -> c.trim() !in EMPTY_VALUES && !c.trim().startsWith("未知") && !c.trim().startsWith("Unknown", true) } }
    if (rows.isEmpty()) {
        Text("無".tr, style = MaterialTheme.typography.bodyMedium, color = Brand.TextFaint)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            val first = row.firstOrNull().orEmpty()
            // 报告已把代号换成名字：反查回代号，改名时才找得到
            val label = names.entries.firstOrNull { it.value == first }?.key ?: first
            val named = label in names || (nameCol > 0 && row.getOrNull(nameCol).orEmpty().let { it !in EMPTY_VALUES && !it.startsWith("未知") })
            val canRename = speakerTable && onRenameSpeaker != null
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceHigh)
                    .border(1.dp, Brand.Outline, RoundedCornerShape(14.dp))
                    .then(if (canRename) Modifier.clickable { onRenameSpeaker?.invoke(label) } else Modifier)
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(richText(first), style = MaterialTheme.typography.titleSmall, color = Brand.Text, modifier = Modifier.weight(1f, fill = false))
                    if (canRename) {
                        Spacer(Modifier.width(8.dp))
                        Text(if (named) "改名".tr else "＋ 命名".tr, style = MaterialTheme.typography.labelSmall,
                            color = if (named) Brand.TextFaint else Brand.Cyan)
                    }
                }
                if (row.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    // 栏位多时自动换行（不要挤成一字一行）
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in 1 until row.size) {
                            if (row[c] == row[0]) continue // 改名后「說話者」与「名字」栏重复，略过
                            if (row[c] in EMPTY_VALUES || row[c].startsWith("未知")) continue // 「未知」之类的空白栏位不显示
                            Column {
                                Text(t.header.getOrElse(c) { "" }, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                                Text(row[c], style = MaterialTheme.typography.bodySmall, color = Brand.Cyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 饮食内容：一张卡、一样食物一段，每段三行——
 * 「食物名 …… 366 大卡」／「一碗 · 約 200g · 蛋白質 6g · 碳水 82g · 脂肪 1g」／来源小标签（食藥署 / 營養標示 / AI 估算）。
 * 栏位用表头名字找（AI 旧格式没有「來源」栏、栏位顺序也可能不同）。
 */
@Composable
private fun FoodTable(t: ReportBlock.Table) {
    fun col(vararg keys: String) = t.header.indexOfFirst { h -> keys.any { h.contains(it) } }
    val iName = col("食物", "品項", "名稱", "Food", "음식").takeIf { it >= 0 } ?: 0
    val iPortion = col("份量", "Portion", "분량")
    val iKcal = col("熱量", "Calories", "열량")
    val iP = col("蛋白", "Protein", "단백질")
    val iC = col("碳水".tr, "Carbs", "탄수")
    val iF = col("脂肪".tr, "Fat", "지방")
    val iSrc = col("來源", "Source", "출처")
    val iFib = col("纖維", "Fiber", "식이섬유")
    val rows = t.rows.filter { r -> r.getOrNull(iName).orEmpty().isNotBlank() }
    fun cell(r: List<String>, i: Int) = if (i < 0) "" else r.getOrNull(i).orEmpty().trim()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceHigh)
            .border(1.dp, Brand.Outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        rows.forEachIndexed { idx, r ->
            if (idx > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
            Column(Modifier.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(richText(cell(r, iName)), style = MaterialTheme.typography.titleSmall, color = Brand.Text, modifier = Modifier.weight(1f))
                    val kcal = cell(r, iKcal)
                    if (kcal.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("{0} 大卡".trf(kcal), style = MaterialTheme.typography.titleSmall, color = Brand.Cyan)
                    }
                }
                val detail = listOf(
                    cell(r, iPortion),
                    cell(r, iP).takeIf { it.isNotEmpty() }?.let { "蛋白質 {0}g".trf(it) }.orEmpty(),
                    cell(r, iC).takeIf { it.isNotEmpty() }?.let { "碳水 {0}g".trf(it) }.orEmpty(),
                    cell(r, iF).takeIf { it.isNotEmpty() }?.let { "脂肪 {0}g".trf(it) }.orEmpty(),
                    cell(r, iFib).takeIf { it.isNotEmpty() && it != "0" }?.let { "纖維 {0}g".trf(it) }.orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                }
                val src = cell(r, iSrc)
                if (src.isNotEmpty()) {
                    val color = when {
                        src.startsWith("食藥署") || src.startsWith("Taiwan FDA") || src.startsWith("대만 식약청") -> Brand.Success
                        src.startsWith("營養標示") || src.startsWith("Nutrition label") || src.startsWith("영양성분표") -> Brand.Blue
                        else -> Brand.TextFaint
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(src, style = MaterialTheme.typography.labelSmall, color = color,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

/** 逐字稿一行：`[mm:ss] 說話者A：內容` 或 `[mm:ss] 內容`。 */
data class TranscriptLine(val time: String?, val speaker: String?, val text: String)

fun parseTranscript(text: String): List<TranscriptLine> = text.lines().mapNotNull { raw ->
    val l = raw.trim()
    if (l.isEmpty()) return@mapNotNull null
    var rest = l
    var time: String? = null
    Regex("^\\[(\\d{1,2}:\\d{2}(:\\d{2})?)]\\s*").find(rest)?.let { m -> time = m.groupValues[1]; rest = rest.substring(m.range.last + 1) }
    var speaker: String? = null
    val colon = rest.indexOfFirst { it == '：' || it == ':' }
    if (colon in 1..12) { speaker = rest.substring(0, colon).trim(); rest = rest.substring(colon + 1).trim() }
    TranscriptLine(time, speaker, rest)
}

/** 每位说话者的统计：发言段数、字数占比。 */
data class SpeakerStat(val label: String, val lines: Int, val share: Float, val color: Color)

fun speakerStats(lines: List<TranscriptLine>): List<SpeakerStat> {
    val order = lines.mapNotNull { it.speaker }.distinct()
    val total = lines.filter { it.speaker != null }.sumOf { it.text.length }.coerceAtLeast(1)
    return order.mapIndexed { i, sp ->
        val mine = lines.filter { it.speaker == sp }
        SpeakerStat(sp, mine.size, mine.sumOf { it.text.length }.toFloat() / total, Brand.Speakers[i % Brand.Speakers.size])
    }
}

/** 头像上显示的字：有名字取名字第一个字，否则取代号最后一个字（A、B…）。 */
private fun initial(label: String?, name: String?): String =
    name?.firstOrNull()?.toString() ?: label?.lastOrNull()?.toString() ?: "•"

/** 逐字稿上方的说话者面板：几位说话者、各自发言占比，点一下改名。 */
@Composable
fun SpeakerPanel(stats: List<SpeakerStat>, names: Map<String, String>, onRename: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("{0} 位說話者  ·  點名字可改名".trf(stats.size), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
        for (st in stats) {
            val name = names[st.label]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onRename(st.label) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(initial(st.label, name), st.color, 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name ?: st.label, style = MaterialTheme.typography.labelLarge, color = st.color)
                        if (name != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(st.label, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Edit, "改名".tr, Modifier.size(14.dp), tint = Brand.TextFaint)
                    }
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brand.SurfaceHigher)) {
                        Box(Modifier.fillMaxWidth(st.share.coerceIn(0.02f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(st.color))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text("${(st.share * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = Brand.Text)
            }
        }
    }
}

@Composable
private fun Avatar(text: String, color: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** 语言标签颜色：國＝灰、台＝绿、客＝琥珀（跟说话者颜色分开，不抢眼；跟 iOS 一样）。 */
fun langTagColor(l: com.example.wristai.SpeechLang): Color = when (l) {
    com.example.wristai.SpeechLang.ZH -> Brand.TextMuted
    com.example.wristai.SpeechLang.NAN -> Brand.Success
    com.example.wristai.SpeechLang.HAK -> Brand.Warning
    else -> Brand.Cyan
}

/** 逐字稿每段前的小胶囊「國／台／客」：标的是使用者选的辨识语言，不是逐句判断（台语、客语模型没有语言标记）。 */
@Composable
private fun LangTag(l: com.example.wristai.SpeechLang, dialect: String?) {
    val c = langTagColor(l)
    Text(
        l.tag, style = MaterialTheme.typography.labelSmall, color = c,
        modifier = Modifier.clip(CircleShape).background(c.copy(alpha = 0.15f)).border(1.dp, c.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = l.tagLabel(dialect) },
    )
}

@Composable
fun TranscriptView(
    text: String,
    modifier: Modifier = Modifier,
    names: Map<String, String> = emptyMap(),
    onRename: ((String) -> Unit)? = null,
    /** 点一行 → 播放器跳到这个时间（毫秒） */
    onSeek: ((Int) -> Unit)? = null,
    /** 播放中的位置（毫秒），用来标示正在播放的那一行；-1 = 不标示 */
    currentMs: Int = -1,
    /** 这笔录音的辨识语言：每段前显示「國／台／客」标签（null = 不显示，例如汇入的文字）。 */
    langTag: com.example.wristai.SpeechLang? = null,
    dialect: String? = null,
) {
    val lines = parseTranscript(text)
    val starts = lines.map { l -> l.time?.let { parseStampMs(it) } }
    // 正在播放的行：开始时间 ≤ 目前位置 的最后一行
    val currentIdx = if (currentMs < 0) -1 else starts.indexOfLast { it != null && it <= currentMs }
    val stats = speakerStats(lines)
    val colorOf = stats.associate { it.label to it.color }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (stats.isNotEmpty() && onRename != null) {
            SpeakerPanel(stats, names, onRename)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                var prev: String? = null
                for ((idx, l) in lines.withIndex()) {
                    val color = l.speaker?.let { colorOf[it] } ?: Brand.TextMuted
                    val name = l.speaker?.let { names[it] ?: it }
                    val sameAsPrev = l.speaker != null && l.speaker == prev
                    prev = l.speaker
                    val active = idx == currentIdx
                    val seekMs = starts[idx]
                    Row(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (active) color.copy(alpha = 0.10f) else Color.Transparent)
                            .then(if (onSeek != null && seekMs != null) Modifier.clickable { onSeek(seekMs) } else Modifier)
                            .padding(vertical = 2.dp)
                    ) {
                        // 同一人连续发言：不重复头像，只留一条色线，读起来像对话气泡
                        if (sameAsPrev) {
                            Box(Modifier.width(28.dp), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.width(2.dp).height(20.dp).clip(RoundedCornerShape(1.dp)).background(color.copy(alpha = 0.35f)))
                            }
                        } else {
                            Avatar(initial(l.speaker, l.speaker?.let { names[it] }), color, 28.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                langTag?.let { LangTag(it, dialect); Spacer(Modifier.width(8.dp)) }
                                if (!sameAsPrev) name?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = color) }
                                l.time?.let {
                                    if (!sameAsPrev && name != null) Spacer(Modifier.width(8.dp))
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                                }
                            }
                            Text(l.text, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 报告的「基本資料」栏位：時間（起 – 訖、長度）、地點（GPS 戳记，点了开地图）、來源（手錶 / 手機 / 通話 / 匯入）、
 * AI（整理报告的模型）、匯入時間。
 * 资料来自录音本身，不是 AI 写的，所以旧报告也有、也不会写错。
 */
@Composable
fun ReportFacts(m: com.example.wristai.Meeting, geo: com.example.voiceai.shared.GeoFix?, onOpenMap: (com.example.voiceai.shared.GeoFix) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.SurfaceHigh)
            .border(1.dp, Brand.Outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("基本資料".tr, style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint)
        FactRow("時間".tr, recordingRange(m))
        if (geo != null) {
            FactRow(
                "地點".tr,
                (geo.place ?: "%.5f, %.5f".format(geo.lat, geo.lon)) + (if (geo.accuracy > 0) "（±{0} m）".trf(geo.accuracy.toInt()) else ""),
                link = true, onClick = { onOpenMap(geo) },
            )
        } else FactRow("地點".tr, "未記錄".tr, faint = true)
        FactRow("來源".tr, m.origin().label)
        // AI 两种模式都显示（用了哪个模型）
        FactRow("AI", aiModelLabel(m.aiModel), faint = m.aiModel == null)
        m.importedAt?.let { FactRow("匯入".tr, formatWhen(it)) }
        // 给大D的主机处理过：主机上的录音、报告删了没（两种模式都显示）
        m.hostCleanup?.let { FactRow("隱私".tr, it.tr) }
        // 科技模式：耗时、编号、为什么改用别的引擎
        if (UiMode.tech) {
            if (m.processStartedAt > 0 && m.processEndedAt > m.processStartedAt)
                FactRow("耗時".tr, durationWords(m.processEndedAt - m.processStartedAt))
            FactRow("編號".tr, m.id, faint = true)
            m.engineNote?.let { FactRow("引擎".tr, it, faint = true) }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, link: Boolean = false, faint: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Brand.TextFaint, modifier = Modifier.widthIn(min = 44.dp).padding(end = 10.dp))
        Text(value + if (link) "  ›" else "", style = MaterialTheme.typography.bodySmall,
            color = when { link -> Brand.Cyan; faint -> Brand.TextFaint; else -> Brand.Text }, modifier = Modifier.weight(1f))
    }
}
