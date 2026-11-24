package com.example.wristai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.wristai.ui.theme.Brand

/** 底部分页。 */
enum class Tab(val route: String, val label: String) {
    HOME("tab:home", "主頁"),
    RECORDS("tab:records", "錄音"),
    FOOD("tab:food", "飲食"),
    /** 檔案管理：手机上的空间、Google 云端硬碟备份 / 取回（自己一个分页，不放在设定里）。 */
    FILES("tab:files", "檔案"),
    SETTINGS("tab:settings", "設定"),
}

@Composable
fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Outline))
        Row(
            Modifier.fillMaxWidth().background(Brand.Bg).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (t in Tab.entries) NavItem(t, t == current) { onSelect(t) }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Color.White else Brand.TextMuted
    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选中的分页：图示后面一个渐层胶囊
        Box(
            Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                .then(if (selected) Modifier.background(Brand.Accent) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            when (tab) {
                Tab.HOME -> Icon(Icons.Filled.Home, null, tint = tint, modifier = Modifier.size(20.dp))
                Tab.RECORDS -> Waveform(Modifier.size(width = 20.dp, height = 16.dp), tint)
                Tab.FOOD -> BowlGlyph(tint)
                Tab.FILES -> FolderGlyph(tint)
                Tab.SETTINGS -> Icon(Icons.Filled.Settings, null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = if (selected) Brand.Text else Brand.TextMuted)
    }
}

/** 饮食图示：一个碗 + 两缕热气。 */
@Composable
private fun FolderGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = 2.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        // 资料夹：上面的小签＋外框（空心，跟其他分页的线条图示一致）
        drawRoundRect(color, Offset(w * 0.06f, h * 0.14f), Size(w * 0.38f, h * 0.22f), r)
        drawRoundRect(color, Offset(w * 0.06f, h * 0.26f), Size(w * 0.88f, h * 0.60f), r, style = Stroke(s))
    }
}

@Composable
private fun BowlGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val s = 2.dp.toPx()
        // 碗
        drawArc(color, 0f, 180f, true, Offset(w * 0.08f, h * 0.30f), Size(w * 0.84f, h * 0.62f))
        drawLine(color, Offset(w * 0.02f, h * 0.61f), Offset(w * 0.98f, h * 0.61f), s, StrokeCap.Round)
        // 热气
        for (x in listOf(0.38f, 0.62f)) {
            drawArc(color, 200f, 140f, false, Offset(w * (x - 0.08f), h * 0.02f), Size(w * 0.16f, h * 0.26f), style = Stroke(s * 0.8f, cap = StrokeCap.Round))
        }
    }
}
