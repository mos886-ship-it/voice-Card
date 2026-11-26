import SwiftUI

/// 手錶主畫面（照 Android `wear/.../MainActivity.kt` 2026-11-19 的兩頁版）：
/// ① 錄音頁：logo＋大D → 狀態 → 三個一樣大的功能鍵排成正三角（手錶錄音 / 飲食 / 手機錄音）。
/// ② 報告書：重送（有待傳時）＋錄音清單（清單 / 明細在里程碑 2）。
/// 錄音中是不能滑走的全螢幕錄音畫面。
struct RecordView: View {
    @ObservedObject var recorder: Recorder
    @ObservedObject private var link = WatchLink.shared
    @State private var page = 0
    @State private var note: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            RadialGradient(colors: [Brand.bgCenter, Brand.bg], center: .center, startRadius: 0, endRadius: 130)
                .ignoresSafeArea()
            if Brand.isInk {
                // 系統時間固定是白字：水墨的宣紙底上方加一道淡墨，時間才看得清楚
                VStack {
                    LinearGradient(colors: [Color.black.opacity(0.55), Color.black.opacity(0)],
                                   startPoint: .top, endPoint: .bottom)
                        .frame(height: 44)
                    Spacer()
                }
                .ignoresSafeArea()
                .allowsHitTesting(false)
            }
            if recorder.isRecording {
                RecordingScreen(recorder: recorder, waiting: link.pendingCount) { recorder.stop() }
            } else {
                TabView(selection: $page) {
                    RecordPage(hero: hero, sending: showSending, locked: recorder.finalizing,
                               onRecord: { recorder.start() },
                               onFood: { recorder.start(food: true) },
                               onPhone: { note = "手機錄音下一版加上" })
                        .tag(0)
                    ReportBookPage(pending: link.pendingCount) { link.resendAll(force: true) }
                        .tag(1)
                }
                .tabViewStyle(.page)
            }
        }
        .onChange(of: recorder.isRecording) { _, _ in note = nil }
        // 回到前景：錄音中但收音停了（被打斷 / 系統暫停）就接著錄
        .onChange(of: scenePhase) { _, phase in if phase == .active { recorder.checkAlive() } }
        #if DEBUG
        // 測試用：simctl launch <watch> tw.dylan.wristai.watchkitapp -autoRecordSeconds 12 [-autoRecordFood YES]
        .task {
            let sec = UserDefaults.standard.double(forKey: "autoRecordSeconds")
            guard sec > 0, !recorder.isRecording else { return }
            recorder.start(food: UserDefaults.standard.bool(forKey: "autoRecordFood"))
            try? await Task.sleep(for: .seconds(sec))
            recorder.stop()
        }
        #endif
    }

    /// 剛停止：最後一段還在收尾，或還有分段等手機確認 → 「正在傳送到手機…」
    private var showSending: Bool { recorder.sending && (recorder.finalizing || link.pendingCount > 0) }

    /// 中間的主狀態文字與顏色（照 Android Hero）。
    private var hero: (String, Color) {
        if let note { return (note, Brand.textMuted) }
        switch recorder.state {
        case .denied: return ("需要麥克風權限才能錄音", Brand.warning)
        case let .failed(msg): return (msg, Brand.warning)
        default: break
        }
        // 手機在即時翻譯：顯示最新一句譯文
        if !link.caption.isEmpty { return ("文A " + link.caption, Brand.text) }
        let status = link.phoneStatus
        if !status.isEmpty {
            // 手機傳來的進度：完成才打勾綠色，失敗橘色，其他（AI 分析中…）青色
            if status.contains("失敗") { return (status, Brand.warning) }
            if status.hasPrefix("手機已收到") || status.hasPrefix("報告完成") { return ("✓ " + status, Brand.success) }
            return (status, Brand.cyan)
        }
        if recorder.sending && !recorder.finalizing && link.pendingCount == 0 { return ("✓ 已傳送到手機", Brand.success) }
        return ("點一下開始錄音", Brand.textMuted)
    }
}

/// 品牌：dw 書法印章 logo ＋「大D」。
private struct BrandHeader: View {
    var body: some View {
        HStack(spacing: 6) {
            BrandMark(size: 22)
            Text("大D").font(.system(size: 13, weight: .bold)).tracking(1).foregroundStyle(Brand.textMuted)
        }
    }
}

/// 錄音頁：三個功能鍵一樣大，排成正三角形——上：手錶錄音；左下：飲食；右下：手機錄音。
private struct RecordPage: View {
    let hero: (String, Color)
    let sending: Bool
    /// 只有最後一段收尾的那一下鎖住按鈕；分段在等手機確認時照樣可以再錄（手機可能不在身邊）。
    let locked: Bool
    let onRecord: () -> Void
    let onFood: () -> Void
    let onPhone: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            BrandHeader()
            Group {
                if sending {
                    HStack(spacing: 6) {
                        Spinner().frame(width: 14, height: 14)
                        Text("正在傳送到手機…").font(.system(size: 12)).foregroundStyle(Brand.cyan)
                    }
                } else {
                    Text(hero.0).font(.system(size: 12)).foregroundStyle(hero.1)
                        .multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
                }
            }
            .padding(.top, 2)
            GeometryReader { geo in
                // 正三角形：三個鍵一樣大，中心連線是等邊三角形（邊長 = 鍵徑 × 1.18，鍵之間留一點空隙）。
                // 鍵徑依可用空間算：高 = 鍵 + 字 + 邊長 × √3/2；寬 = 邊長 + 鍵。
                let gap: CGFloat = 1.18
                let size = min(56, (geo.size.height - labelHeight) / (1 + gap * 0.866),
                               (geo.size.width - 4) / (1 + gap))
                let side = size * gap
                let h = side * 0.866
                let cx = geo.size.width / 2
                let top = (geo.size.height - (size + labelHeight + h)) / 2 + size / 2
                ZStack {
                    TriangleAction(label: "手錶", size: size,
                                   fill: AnyShapeStyle(Brand.accent),
                                   enabled: !locked, action: onRecord) { c in
                        WatchGlyph(color: c, dot: locked ? nil : Brand.recording)
                    }
                    .position(x: cx, y: top + labelHeight / 2)
                    TriangleAction(label: "飲食", size: size, fill: AnyShapeStyle(Brand.success),
                                   enabled: !locked, action: onFood) { c in
                        BowlGlyph(color: c)
                    }
                    .position(x: cx - side / 2, y: top + h + labelHeight / 2)
                    TriangleAction(label: "手機", size: size, fill: AnyShapeStyle(Brand.blue),
                                   enabled: !locked, action: onPhone) { c in
                        PhoneGlyph(color: c, dot: c == .white ? Brand.recording : nil)
                    }
                    .position(x: cx + side / 2, y: top + h + labelHeight / 2)
                }
            }
        }
        .padding(.horizontal, 6)
        .padding(.bottom, 8) // 頁點指示
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(edges: .bottom)
    }
}

private let labelHeight: CGFloat = 14

/// 三個一樣大的功能鍵：外圈細線＋實心圓＋圖示（佔鍵徑一半），下方小字。
private struct TriangleAction<Icon: View>: View {
    let label: String
    let size: CGFloat
    let fill: AnyShapeStyle
    let enabled: Bool
    let action: () -> Void
    @ViewBuilder let icon: (Color) -> Icon

    var body: some View {
        VStack(spacing: 0) {
            Button(action: action) {
                ZStack {
                    Circle().stroke(Brand.outline, lineWidth: 1)
                    Circle().fill(enabled ? fill : AnyShapeStyle(Brand.surfaceHigh)).padding(4)
                    icon(enabled ? .white : Brand.textMuted).frame(width: size * 0.48, height: size * 0.48)
                }
                .frame(width: size, height: size)
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .accessibilityLabel(label)
            Text(label).font(.system(size: 10)).foregroundStyle(Brand.textMuted).lineLimit(1)
                .frame(height: labelHeight)
        }
    }
}

/// 中央大圓鈕：待機 = 品牌漸層＋手錶圖示（紅色錄音點）；錄音中 = 紅色停止＋擴散光環；傳送中 = 灰色。
struct RecordCircle: View {
    let recording: Bool
    let sending: Bool
    let action: () -> Void
    private let btn: CGFloat = 64

    var body: some View {
        ZStack {
            if recording {
                TimelineView(.animation) { ctx in
                    let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.6) / 1.6
                    ZStack {
                        ForEach(0..<2) { k in
                            let p = (t + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                            Circle().stroke(Brand.recording.opacity(0.45 * (1 - p)), lineWidth: 2)
                                .frame(width: btn + p * 26, height: btn + p * 26)
                        }
                    }
                }
            } else {
                Circle().stroke(Brand.outline, lineWidth: 1).frame(width: btn + 12, height: btn + 12)
            }
            Button(action: action) {
                ZStack {
                    if recording { Circle().fill(Brand.record) }
                    else if sending { Circle().fill(Brand.surfaceHigh) }
                    else { Circle().fill(Brand.accent) }
                    if recording {
                        RoundedRectangle(cornerRadius: 5).fill(.white).frame(width: 22, height: 22)
                    } else {
                        WatchGlyph(color: sending ? Brand.textMuted : .white, dot: sending ? nil : Brand.recording)
                            .frame(width: 30, height: 30)
                    }
                }
                .frame(width: btn, height: btn)
            }
            .buttonStyle(.plain)
            .disabled(sending)
            .accessibilityLabel(recording ? "停止錄音" : "開始錄音")
        }
        .frame(height: 92)
    }
}

/// 錄音中的全螢幕畫面：脈衝紅點＋「錄音中」、大計時、停止鈕、同步提示。
private struct RecordingScreen: View {
    @ObservedObject var recorder: Recorder
    let waiting: Int
    let onStop: () -> Void

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 5) {
                PulseRing().frame(width: 12, height: 12)
                Text(recorder.isFood ? "飲食記錄中" : "錄音中").font(.system(size: 12, weight: .semibold)).foregroundStyle(Brand.recording)
            }
            if case let .recording(_, since) = recorder.state {
                TimelineView(.periodic(from: since, by: 1)) { ctx in
                    let sec = ctx.date.timeIntervalSince(since)
                    Text(Fmt.clock(sec))
                        .font(.system(size: sec >= 3600 ? 28 : 32, weight: .bold, design: .monospaced))
                        .foregroundStyle(Brand.text)
                }
            }
            RecordCircle(recording: true, sending: false, action: onStop)
            Text(syncLine).font(.system(size: 11))
                .foregroundStyle(waiting > 0 || recorder.interrupted ? Brand.warning : Brand.textMuted)
                .lineLimit(1)
        }
        .padding(.horizontal, 22)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var syncLine: String {
        if recorder.interrupted { return "收音中斷，恢復中…" }
        if recorder.isFood { return "說出吃了什麼，說完按停止" }
        if waiting > 0 { return "\(waiting) 段待傳" }
        if recorder.closedSegments > 0 { return "已同步 \(recorder.closedSegments) 段" }
        return "每 5 分鐘同步到手機"
    }
}

/// ② 報告書（清單、搜尋、月份、明細在里程碑 2）。
private struct ReportBookPage: View {
    let pending: Int
    let onResend: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("報告書").font(.system(size: 15, weight: .semibold)).foregroundStyle(Brand.text)
                if pending > 0 {
                    Button(action: onResend) {
                        Label("重送（\(pending) 段）", systemImage: "arrow.clockwise")
                            .font(.system(size: 13, weight: .medium))
                            .frame(maxWidth: .infinity)
                    }
                    .tint(Brand.warning)
                }
                VStack(spacing: 4) {
                    Waveform(color: Brand.cyan).frame(width: 28, height: 20)
                    Text("錄音清單與 AI 報告下一版加上；錄好的錄音可以在 iPhone 上播放。")
                        .font(.system(size: 12)).foregroundStyle(Brand.textMuted)
                        .multilineTextAlignment(.center)
                }
                .padding(10)
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: 16).fill(Brand.surface))
            }
            .padding(.horizontal, 6)
        }
    }
}

private struct Spinner: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let a = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1 * 360
            ZStack {
                Circle().stroke(Brand.outline, lineWidth: 2)
                Circle().trim(from: 0, to: 100.0 / 360).stroke(Brand.accent, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .rotationEffect(.degrees(a))
            }
        }
    }
}

private struct PulseRing: View {
    var body: some View {
        TimelineView(.animation) { ctx in
            let p = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.4) / 1.4
            ZStack {
                Circle().fill(Brand.recording.opacity(0.35 * (1 - p))).scaleEffect(0.5 + p * 0.5)
                Circle().fill(Brand.recording).scaleEffect(0.5)
            }
        }
    }
}
