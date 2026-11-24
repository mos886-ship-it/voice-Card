package com.example.wristai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * 手机录音的即时音量（录音卡的音量条用；跟 iOS `AudioLevel.swift` 同规则）。
 * 录音迴圈每读到一段 PCM 就 [feed]；凑满约 85 ms 算一次 RMS → 一根柱，留最近 [COUNT] 根（约 5 秒）。
 * 跟录音状态分开的 StateFlow：一秒更新十几次，只让音量条自己重画。
 */
object AudioLevel {
    const val COUNT = 56
    const val FLOOR_DB = -60f
    private const val WINDOW_SEC = 0.085

    data class Snapshot(
        /** 每根 0…1（已换成 dB 刻度），最新在最后。 */
        val history: List<Float> = List(COUNT) { 0f },
        /** 目前音量（dBFS，-60…0）。 */
        val db: Float = FLOOR_DB,
        /** 最近太大声（峰值 > 0.97 之后 1.2 秒内）。 */
        val loud: Boolean = false,
        /** 超过 4 秒都低于 -45 dB。 */
        val quiet: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private var sum = 0.0
    private var n = 0
    private var peak = 0f
    private var lastSound = System.currentTimeMillis()
    private var lastLoud = 0L

    /** 均方 → dBFS（下限 -60）。 */
    fun dB(meanSquare: Double): Float = max(FLOOR_DB, (10 * log10(max(meanSquare, 1e-12))).toFloat())

    /** 均方 → 0…1：-52 dB 以下是 0，-8 dB 以上满格，再平方加大对比。 */
    fun norm(meanSquare: Double): Float {
        val x = (dB(meanSquare) + 52f) / 44f
        val c = min(1f, max(0f, x))
        return c * c
    }

    /** 录音迴圈呼叫：[bytes] 是 16-bit little-endian 单声道 PCM 的前 [len] 个位元组。 */
    @Synchronized
    fun feed(bytes: ByteArray, len: Int, sampleRate: Int) {
        val window = (sampleRate * WINDOW_SEC).toInt().coerceAtLeast(64)
        var i = 0
        while (i + 1 < len) {
            val v = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort() / 32768f
            sum += v * v
            peak = max(peak, abs(v))
            n++
            if (n >= window) push(sum / n, peak).also { sum = 0.0; n = 0; peak = 0f }
            i += 2
        }
    }

    private fun push(meanSquare: Double, pk: Float) {
        val now = System.currentTimeMillis()
        val d = dB(meanSquare)
        if (d > -45f) lastSound = now
        if (pk > 0.97f) lastLoud = now
        val s = _state.value
        _state.value = Snapshot(
            history = (s.history + norm(meanSquare)).takeLast(COUNT),
            db = d,
            loud = now - lastLoud < 1200,
            quiet = now - lastSound > 4000,
        )
    }

    @Synchronized
    fun reset() {
        sum = 0.0; n = 0; peak = 0f
        lastSound = System.currentTimeMillis(); lastLoud = 0L
        _state.value = Snapshot()
    }
}
