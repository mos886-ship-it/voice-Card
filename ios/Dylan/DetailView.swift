import AVFoundation
import SwiftUI

/// 錄音詳情（照 Android `ui/DetailScreen.kt`）：頂欄（返回、刪除）→ 標題（點了改名）＋錄音起訖＋狀態 →
/// 狀態卡（AI 分析中 / 失敗可重試 / 還沒報告）→ 播放器 → 「報告 / 逐字稿」。
struct DetailView: View {
    let id: String
    let onBack: () -> Void
    @EnvironmentObject private var store: MeetingStore
    @State private var confirmDelete = false
    @State private var editingTitle = false
    @State private var newTitle = ""
    @StateObject private var player = PlayerState()

    var body: some View {
        VStack(spacing: 0) {
            TopBar(title: "錄音詳情", onBack: onBack) {
                IconCircle(systemImage: "trash", label: "刪除", tint: Brand.error) { confirmDelete = true }
            }
            if let m = store.meetings.first(where: { $0.id == id }) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        // ---------- 標題區 ----------
                        VStack(alignment: .leading, spacing: 0) {
                            Button {
                                newTitle = m.title
                                editingTitle = true
                            } label: {
                                HStack(spacing: 8) {
                                    Text(m.title).font(.headlineSmall).foregroundStyle(Brand.text)
                                        .multilineTextAlignment(.leading)
                                    Image(systemName: "pencil").font(.system(size: 14)).foregroundStyle(Brand.textFaint)
                                }
                            }
                            .buttonStyle(.plain)
                            Text("錄音  \(m.recordingRange)").font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                            StatusChip(style: m.statusStyle).padding(.top, 10)
                        }

                        // ---------- 狀態卡 ----------
                        if m.status == .recording {
                            TimelineView(.periodic(from: .now, by: 30)) { ctx in
                                if MeetingStore.looksStuck(m, now: ctx.date) {
                                    // 很久沒有新分段：可能手錶在最後一段傳到前被重置 → 讓使用者用已收到的完成
                                    GlassCard(highlight: true) {
                                        Text("錄音好像中斷了").font(.titleMedium).foregroundStyle(Brand.text)
                                        Text("已經一段時間沒有收到手錶的新分段。可以先用已收到的 \(m.segments.count) 段（\(Fmt.duration(m.durationSec))）完成這筆錄音；缺的部分會跳過。")
                                            .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                                        GradientButton(text: "用已收到的分段完成") {
                                            Task { await store.finishWithReceived(m.id) }
                                        }
                                        .padding(.top, 12)
                                    }
                                } else {
                                    GlassCard {
                                        HStack(spacing: 8) {
                                            PulseDot(color: Brand.recording, size: 8)
                                            Text("手錶錄音中").font(.titleMedium).foregroundStyle(Brand.text)
                                        }
                                        Text("已收到 \(m.segments.count) 段，錄音中每 5 分鐘同步一次，停止後會自動合併。")
                                            .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                                        ScanningBar().padding(.top, 12)
                                    }
                                }
                            }
                        }
                        // ---------- AI 狀態卡 ----------
                        if m.status == .summarizing {
                            GlassCard {
                                HStack(spacing: 8) {
                                    PulseDot(color: Brand.cyan, size: 8)
                                    Text("AI 分析中").font(.titleMedium).foregroundStyle(Brand.text)
                                    Spacer()
                                    if let s = m.processStartedAt {
                                        TimelineView(.periodic(from: s, by: 1)) { ctx in
                                            Text(Fmt.clock(ctx.date.timeIntervalSince(s))).font(.labelSmall).foregroundStyle(Brand.textMuted)
                                        }
                                    }
                                }
                                Text(Self.stepText(m.aiStep))
                                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
                                Text("可以關掉螢幕或切到別的 App，分析會在背景繼續，完成後手錶會收到通知。")
                                    .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 4)
                                ScanningBar().padding(.top, 12)
                            }
                        }
                        if m.status == .failed, let e = m.error {
                            GlassCard {
                                Text("處理失敗").font(.titleMedium).foregroundStyle(Brand.error)
                                Text(e).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                                if hasAudio(m) {
                                    GradientButton(text: "重試") { Task { await store.process(m.id) } }.padding(.top, 12)
                                }
                            }
                        }
                        if m.status == .received {
                            GlassCard(highlight: true) {
                                Text("還沒有 AI 報告").font(.titleMedium).foregroundStyle(Brand.text)
                                Text(LocalAI.configured ? "按下面的按鈕，用你的 Mac 整理出重點、待辦與分說話者的逐字稿。"
                                     : MeetingStore.hasGeminiKey && !MeetingStore.privacyMode
                                     ? "按下面的按鈕，用 Gemini 整理出重點、待辦與分說話者的逐字稿。"
                                     : "沒有 Gemini 金鑰或開著隱私模式：會用 Apple 在手機上產生逐字稿與重點（不分說話者，需要 iOS 26 以上）。")
                                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                                GradientButton(text: "產生報告", systemImage: "sparkles") { Task { await store.process(m.id) } }
                                    .padding(.top, 12)
                            }
                        }

                        // ---------- 飲食：這一餐的熱量與營養素 ----------
                        if let n = m.nutrition { NutritionCard(n: n) }

                        // ---------- 這次為什麼用 Apple ----------
                        if let note = m.engineNote, m.status == .done || m.status == .summarizing {
                            Label(note, systemImage: note.contains("Gemini") ? "arrow.triangle.branch" : "apple.logo")
                                .font(.bodySmall).foregroundStyle(Brand.textMuted)
                                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: 14).fill(Brand.surfaceHigh))
                        }

                        // ---------- 播放器（錄音中不播放，檔案還在寫） ----------
                        if hasAudio(m) {
                            AudioPlayerBar(url: MeetingStore.audioURL(m.id), p: player)
                        }

                        // ---------- 報告 / 逐字稿 ----------
                        if m.status == .done || m.aiModel != nil || m.transcriptPreview == true {   // 有過報告（重新產生中 / 失敗時也保留舊報告）
                            ReportSection(meeting: m, player: player) { Task { await store.process(m.id) } }
                        }
                    }
                    .pageColumn(maxWidth: 760)
                    .padding(.bottom, 24)
                }
            } else {
                Spacer()
            }
        }
        .background(Brand.backdrop.ignoresSafeArea())
        .confirmationDialog("刪除這筆錄音？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("刪除", role: .destructive) {
                player.stop()
                store.delete(id)
                onBack()
            }
        } message: { Text("錄音檔、報告與逐字稿會一起刪除，無法復原。") }
        .alert("改標題", isPresented: $editingTitle) {
            TextField("標題", text: $newTitle)
            Button("取消", role: .cancel) {}
            Button("儲存") {
                let t = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                if !t.isEmpty, var m = store.load(id) {
                    m.title = t
                    m.titleEdited = true
                    store.save(m)
                }
            }
        }
        .onDisappear { player.stop() }
    }

    static func stepText(_ step: String?) -> String {
        switch step {
        case "AI 聆聽整理中": return "Gemini 正在聆聽錄音，整理成報告與逐字稿。"
        case "上傳錄音中": return "正在把錄音上傳給 Gemini。"
        case "傳給這台 Mac 處理中": return "錄音已傳給你的 Mac，正在辨識說話者並整理報告（約錄音長度的一半到一倍時間）。"
        case "Apple 語音辨識中": return "Apple 正在手機上把錄音轉成文字（不上傳）。"
        case "Apple 整理中": return "Apple 正在整理重點。"
        default: return "處理中…"
        }
    }

    private func hasAudio(_ m: Meeting) -> Bool {
        m.status != .recording && FileManager.default.fileExists(atPath: MeetingStore.audioURL(m.id).path)
    }
}

/// 播放列（照 Android `ui/AudioPlayer.kt`）：漸層圓播放鍵＋進度條＋時間，下面「−15 秒」「+15 秒」「1× 速度」。
struct AudioPlayerBar: View {
    let url: URL
    @ObservedObject var p: PlayerState

    var body: some View {
        GlassCard(padding: 14) {
            HStack(spacing: 12) {
                Button { p.toggle() } label: {
                    Image(systemName: p.playing ? "pause.fill" : "play.fill")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 48, height: 48)
                        .background(Circle().fill(Brand.accent))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(p.playing ? "暫停" : "播放")
                VStack(spacing: 0) {
                    Slider(value: Binding(get: { p.time }, set: { p.seek($0) }), in: 0...max(p.duration, 0.1))
                        .tint(Brand.cyan)
                    HStack {
                        Text(Fmt.clock(p.time)).foregroundStyle(Brand.textMuted)
                        Spacer()
                        Text(Fmt.clock(p.duration)).foregroundStyle(Brand.textFaint)
                    }
                    .font(.labelSmall)
                }
            }
            HStack {
                ChipButton(text: "−15 秒") { p.skip(-15) }
                Spacer()
                ChipButton(text: "+15 秒") { p.skip(15) }
                Spacer()
                ChipButton(text: "\(p.rate == 1.5 ? "1.5" : String(Int(p.rate)))× 速度") { p.cycleRate() }
            }
            .padding(.top, 8)
            .padding(.horizontal, 8)
            if let e = p.error { Text(e).font(.bodySmall).foregroundStyle(Brand.warning).padding(.top, 6) }
        }
        .onAppear { p.load(url) }
    }
}

private struct ChipButton: View {
    let text: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(text).font(.labelMedium).foregroundStyle(Brand.text)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Capsule().fill(Brand.surfaceHigh))
        }
        .buttonStyle(.plain)
    }
}

@MainActor
final class PlayerState: ObservableObject {
    @Published var time: Double = 0
    @Published var duration: Double = 0
    @Published var playing = false
    @Published var rate: Float = 1
    @Published var error: String?
    private var player: AVAudioPlayer?
    private var timer: Timer?

    func load(_ url: URL) {
        guard player == nil else { return }
        do {
            let p = try AVAudioPlayer(contentsOf: url)
            p.enableRate = true
            p.prepareToPlay()
            player = p
            duration = p.duration
        } catch {
            self.error = "無法播放：\(error.localizedDescription)"
        }
    }

    func toggle() {
        guard let player else { return }
        if player.isPlaying {
            player.pause()
            playing = false
            timer?.invalidate()
        } else {
            try? AVAudioSession.sharedInstance().setCategory(.playback)
            try? AVAudioSession.sharedInstance().setActive(true)
            player.rate = rate
            player.play()
            playing = true
            timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
        }
    }

    func seek(_ t: Double) {
        player?.currentTime = t
        time = t
    }

    /// 逐字稿點一句：跳到那個時間並播放（照 Android seekTo(play = true)）。
    func play(at t: Double) {
        seek(min(max(0, t), duration))
        if !playing { toggle() }
    }

    func skip(_ d: Double) { seek(min(max(0, (player?.currentTime ?? 0) + d), duration)) }

    func cycleRate() {
        rate = rate == 1 ? 1.5 : rate == 1.5 ? 2 : 1
        player?.rate = rate
    }

    func stop() {
        player?.stop()
        timer?.invalidate()
        playing = false
    }

    private func tick() {
        guard let player else { return }
        time = player.currentTime
        if !player.isPlaying {
            playing = false
            timer?.invalidate()
        }
    }
}

/// 飲食記錄：這一餐的熱量（大字）與三大營養素（照 Android NutritionHeader）。
struct NutritionCard: View {
    let n: AppleAI.Nutrition
    var body: some View {
        GlassCard(highlight: true) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(String(n.kcal)).font(.system(size: 34, weight: .bold)).foregroundStyle(Brand.text)
                Text("大卡").font(.titleSmall).foregroundStyle(Brand.textMuted)
                Spacer()
                if !n.meal.isEmpty { StatusChip(style: StatusStyle(label: n.meal, color: Brand.success, busy: false)) }
            }
            HStack(spacing: 10) {
                StatTile(value: "\(n.protein)g", label: "蛋白質")
                StatTile(value: "\(n.carbs)g", label: "碳水")
                StatTile(value: "\(n.fat)g", label: "脂肪")
            }
            .padding(.top, 10)
            Text("AI 依口述估算，不是精確值").font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
        }
    }
}
