package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import com.example.wristai.ui.theme.Brand
import kotlinx.coroutines.delay
import java.io.File

/** 会议录音播放器的状态（详情页的逐字稿也用它跳到某个时间点）。 */
@Stable
class PlayerState internal constructor(private val file: File) {
    private var player: MediaPlayer? = null
    var playing by mutableStateOf(false); private set
    var positionMs by mutableIntStateOf(0); private set
    var durationMs by mutableIntStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var error by mutableStateOf<String?>(null); private set

    private fun ensure(): MediaPlayer? {
        player?.let { return it }
        return runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { this@PlayerState.playing = false }
            }
        }.onSuccess { player = it; durationMs = it.duration }
            .onFailure { error = "無法播放：{0}".trf(it.message) }
            .getOrNull()
    }

    fun toggle() {
        val p = ensure() ?: return
        if (p.isPlaying) { p.pause(); playing = false } else { applySpeed(p); p.start(); playing = true }
    }

    fun seekTo(ms: Int, play: Boolean = false) {
        val p = ensure() ?: return
        p.seekTo(ms.coerceIn(0, maxOf(0, p.duration)))
        positionMs = p.currentPosition
        if (play && !p.isPlaying) { applySpeed(p); p.start(); playing = true }
    }

    fun skip(deltaMs: Int) = seekTo(positionMs + deltaMs)

    fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.let { if (it.isPlaying) applySpeed(it) }
    }

    private fun applySpeed(p: MediaPlayer) {
        runCatching { p.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    internal fun tick() { player?.let { if (it.isPlaying) positionMs = it.currentPosition } }

    internal fun release() { player?.release(); player = null; playing = false }

    /** 先读出长度（不开始播放），让进度条一开始就有总长度。 */
    internal fun prime() { if (durationMs == 0) ensure() }
}

@Composable
fun rememberPlayerState(file: File): PlayerState {
    val state = remember(file.absolutePath) { PlayerState(file) }
    DisposableEffect(state) { onDispose { state.release() } }
    LaunchedEffect(state) {
        state.prime()
        while (true) { state.tick(); delay(200) }
    }
    return state
}

fun formatMs(ms: Int): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** 逐字稿时间（mm:ss 或 h:mm:ss）→ 毫秒。 */
fun parseStampMs(t: String): Int? {
    val p = t.split(':').map { it.toIntOrNull() ?: return null }
    return when (p.size) {
        2 -> (p[0] * 60 + p[1]) * 1000
        3 -> (p[0] * 3600 + p[1] * 60 + p[2]) * 1000
        else -> null
    }
}

/** 播放列：播放 / 暂停、拖拉进度、倒退 / 快进 15 秒、播放速度。 */
@Composable
fun AudioPlayerBar(state: PlayerState, modifier: Modifier = Modifier) {
    GlassCard(modifier, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 主按钮：渐层圆 + 播放三角 / 暂停双竖线
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Brand.Accent).clickable { state.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    if (state.playing) {
                        val w = size.width * 0.3f
                        drawRoundRect(Color.White, Offset(size.width * 0.1f, 0f), Size(w, size.height), CornerRadius(3f))
                        drawRoundRect(Color.White, Offset(size.width * 0.6f, 0f), Size(w, size.height), CornerRadius(3f))
                    } else {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.2f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.2f, size.height); close()
                        }
                        drawPath(path, Color.White)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Slider(
                    value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                    onValueChange = { state.seekTo((it * state.durationMs).toInt()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Brand.Cyan, activeTrackColor = Brand.Cyan, inactiveTrackColor = Brand.SurfaceHigher,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall, color = Brand.TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                }
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ChipButton("−15 秒".tr) { state.skip(-15_000) }
            ChipButton("+15 秒".tr) { state.skip(15_000) }
            ChipButton("{0}× 速度".trf(if (state.speed % 1f == 0f) state.speed.toInt() else state.speed)) { state.cycleSpeed() }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Warning) }
    }
}

@Composable
private fun ChipButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Brand.SurfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Brand.Text)
    }
}
