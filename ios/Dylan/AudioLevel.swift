import AVFoundation
import SwiftUI

/// 手機錄音的即時音量（給錄音卡的音量條用）。
/// 跟 PhoneRecorder 分開：一秒更新十幾次，只讓音量條自己重畫，不牽動整個主頁。
@MainActor
final class AudioLevel: ObservableObject {
    static let shared = AudioLevel()
    /// 音量條顯示幾根（最新的在右邊）。
    static let count = 56

    /// 每根 0…1（已換成 dB 刻度）。
    @Published private(set) var history = [Float](repeating: 0, count: count)
    /// 目前的音量（dBFS，-60…0）。
    @Published private(set) var db: Float = -60
    /// 最近太大聲（快破音）。
    @Published private(set) var loud = false
    /// 一直沒什麼聲音（超過 4 秒）。
    @Published private(set) var quiet = false

    private var lastSound = Date()
    private var lastLoud = Date.distantPast

    /// 一個 buffer 的量測：切成幾小段各算一個音量，外加整段的 dB 與峰值。
    struct Measure: Sendable { let levels: [Float]; let db: Float; let peak: Float }

    nonisolated static let floorDB: Float = -60

    /// 一個 buffer（約 85 ms）一根：56 根大約 5 秒，看得出說話的起伏節奏。
    nonisolated static func measure(_ buffer: AVAudioPCMBuffer, parts: Int = 1) -> Measure? {
        guard let ch = buffer.floatChannelData?[0] else { return nil }
        let n = Int(buffer.frameLength)
        guard n > 0 else { return nil }
        let step = max(1, n / parts)
        var levels: [Float] = []
        var sumAll: Float = 0
        var peak: Float = 0
        var i = 0
        while i < n {
            let end = min(n, i + step)
            var sum: Float = 0
            for k in i..<end {
                let v = ch[k]
                sum += v * v
                peak = max(peak, abs(v))
            }
            sumAll += sum
            levels.append(norm(sum / Float(end - i)))
            i = end
        }
        return Measure(levels: levels, db: dB(sumAll / Float(n)), peak: peak)
    }

    /// 均方 → dBFS（下限 -60）。
    nonisolated static func dB(_ meanSquare: Float) -> Float {
        max(floorDB, 10 * log10(max(meanSquare, 1e-12)))
    }

    /// 均方 → 0…1：-52 dB 以下（安靜的房間）是 0，-8 dB 以上滿格；平方加大對比，說話的高低才看得出來。
    nonisolated static func norm(_ meanSquare: Float) -> Float {
        let x = (dB(meanSquare) + 52) / 44
        return pow(max(0, min(1, x)), 2)
    }

    func push(_ m: Measure) {
        var h = history
        h.append(contentsOf: m.levels)
        if h.count > Self.count { h.removeFirst(h.count - Self.count) }
        history = h
        db = m.db
        let now = Date()
        if m.db > -45 { lastSound = now }
        if m.peak > 0.97 { lastLoud = now }
        let q = now.timeIntervalSince(lastSound) > 4
        if q != quiet { quiet = q }
        let l = now.timeIntervalSince(lastLoud) < 1.2
        if l != loud { loud = l }
    }

    func reset() {
        history = [Float](repeating: 0, count: Self.count)
        db = Self.floorDB
        loud = false
        quiet = false
        lastSound = Date()
        lastLoud = .distantPast
    }
}

/// 錄音卡上的音量條：左右對稱的波形柱，最新的在右邊；下面一行顯示收音狀態。
struct LevelMeter: View {
    @ObservedObject var level = AudioLevel.shared
    var paused = false
    var detailed = false
    var height: CGFloat = 40

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Canvas { ctx, size in
                let n = level.history.count
                // 自動增益：最近 5 秒最大聲的那根拉到約滿格（但安靜時不放大雜音）
                let gain = 0.92 / CGFloat(max(level.history.max() ?? 0, 0.3))
                let gap: CGFloat = 2
                let w = max(1.5, (size.width - gap * CGFloat(n - 1)) / CGFloat(n))
                let mid = size.height / 2
                for (i, v) in level.history.enumerated() {
                    let lv = paused ? 0 : min(1, CGFloat(v) * gain)
                    let h = max(3, lv * size.height)
                    let x = CGFloat(i) * (w + gap)
                    let rect = CGRect(x: x, y: mid - h / 2, width: w, height: h)
                    // 越新越亮；太大聲時最新的幾根變暖色
                    let age = CGFloat(i) / CGFloat(max(1, n - 1))
                    let hot = level.loud && i >= n - 6
                    let color: Color = hot ? Color(red: 1, green: 0.78, blue: 0.35) : .white
                    ctx.fill(Path(roundedRect: rect, cornerRadius: w / 2),
                             with: .color(color.opacity(0.28 + 0.72 * Double(age) * Double(0.45 + 0.55 * lv))))
                }
            }
            .frame(height: height)
            .accessibilityHidden(true)

            HStack(spacing: 6) {
                Circle().fill(statusColor).frame(width: 6, height: 6)
                Text(status).font(.labelMedium).foregroundColor(Color.white.opacity(0.85))
                Spacer(minLength: 0)
                if detailed && !paused {
                    Text(String(format: "%.0f dB", level.db))
                        .font(.labelMedium).monospacedDigit().foregroundColor(Color.white.opacity(0.7))
                }
            }
            .accessibilityElement(children: .combine)
        }
    }

    private var status: String {
        if paused { return "收音中斷，正在恢復…" }
        if level.loud { return "聲音太大，請離遠一點" }
        if level.quiet { return "幾乎沒有聲音，請靠近一點" }
        return "收音正常"
    }

    private var statusColor: Color {
        if paused || level.loud { return Color(red: 1, green: 0.78, blue: 0.35) }
        if level.quiet { return Color.white.opacity(0.5) }
        return Color(red: 0.45, green: 1, blue: 0.7)
    }
}
