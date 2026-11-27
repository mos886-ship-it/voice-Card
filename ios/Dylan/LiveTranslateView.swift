import SwiftUI

/// 別的畫面要打開即時翻譯頁（主頁的「翻譯」、錄音中的「即時翻譯」）：RootView 改到「翻譯」分頁。
@MainActor
final class TranslatePage: ObservableObject {
    static let shared = TranslatePage()
    @Published var presented = false
    func open() { presented = true }
}

/// 即時翻譯頁（照 Android `LiveTranslateScreen.kt`）：
/// - 上面「說的 ⇄ 翻成」兩個按鈕（標手機上／線上），點了是可搜尋的語言清單；⇄ 對調方向（翻譯中也可以）。
/// - 兩種聽法：只翻譯（自己開麥克風，沒有錄音）、勾「同時錄音」（錄完照常產生報告）；錄音中直接用錄音的聲音。
/// - 工具列：A−／A+、原文開關、朗讀開關、放大（只顯示最新一句，可翻轉 180° 給對面的人看）、清除。
/// - 每句可以按 🔊 念、長按複製。翻譯中螢幕保持亮著。
struct LiveTranslateView: View {
    /// 放在「翻譯」分頁裡（沒有返回鍵）；false = 全螢幕打開的那種
    var embedded = false
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var captions = LiveCaptions.shared
    @ObservedObject private var rec = PhoneRecorder.shared
    @ObservedObject private var speaker = Speaker.shared
    @AppStorage(LiveCaptions.textSizeKey) private var textSize = 1
    @AppStorage(LiveCaptions.showOriginalKey) private var showOriginal = true
    @AppStorage(LiveCaptions.readAloudKey) private var readAloud = false
    @AppStorage("translate_with_recording") private var withRecording = false
    @State private var source = TranslateLangs.source
    @State private var target = TranslateLangs.target(for: TranslateLangs.source)
    @State private var picking: Side?
    @State private var enlarged = false

    enum Side: String, Identifiable { case source, target; var id: String { rawValue } }

    private var recording: Bool { rec.isRecording && !rec.isFood }

    var body: some View {
        VStack(spacing: 0) {
            if !embedded { TopBar(title: "即時翻譯".tr, onBack: { dismiss() }) { EmptyView() } }
            VStack(alignment: .leading, spacing: 10) {
                langBar
                controls
                if let s = captions.error ?? captions.status {
                    Text(s).font(.bodySmall).foregroundStyle(captions.error != nil ? Brand.warning : Brand.textMuted)
                }
                toolbar
            }
            .pageColumn()

            captionList.padding(.top, 8)

            Text("手機內建辨識＋裝置端翻譯，僅供參考；完整、準確的內容以錄完的報告為準。".tr)
                .font(.labelSmall).foregroundStyle(Brand.textFaint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .pageColumn()
                .padding(.vertical, 8)
        }
        .background(AppBackdrop())
        .onAppear {
            if captions.running { source = captions.source; target = captions.target }
            else { source = TranslateLangs.source; target = TranslateLangs.target(for: source) }
        }
        .onChange(of: captions.source) { s in source = s }
        .onChange(of: captions.target) { t in target = t }
        .sheet(item: $picking) { side in
            LangSheet(side: side, current: side == .source ? source : target) { id in
                if side == .source { source = id; TranslateLangs.source = id }
                else { target = id; UserDefaults.standard.set(id, forKey: LiveCaptions.targetKey) }
                if captions.running { captions.start(sourceId: source, targetId: target) }   // 換語言：重新開始
            }
        }
        .fullScreenCover(isPresented: $enlarged) { EnlargedCaption() }
    }

    // MARK: 說的 ⇄ 翻成

    private var langBar: some View {
        HStack(spacing: 8) {
            langButton(.source, title: "說的".tr, name: TranslateLangs.name(source), badge: sourceBadge)
            Button { captions.swap(); source = TranslateLangs.source; target = TranslateLangs.target(for: source) } label: {
                Image(systemName: "arrow.left.arrow.right").font(.system(size: 16, weight: .semibold)).foregroundStyle(Brand.cyan)
                    .frame(width: 40, height: 40).background(Circle().fill(Brand.surfaceHigh))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("對調方向".tr)
            langButton(.target, title: "翻成".tr, name: TranslateLangs.name(target), badge: nil)
        }
    }

    private var sourceBadge: String {
        guard let r = SFSpeechRecognizerCache.onDevice(source) else { return "" }
        return r ? "手機上".tr : "線上".tr
    }

    private func langButton(_ side: Side, title: String, name: String, badge: String?) -> some View {
        Button { picking = side } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.labelMedium).foregroundStyle(Brand.textMuted)
                HStack(spacing: 4) {
                    Text(name).font(.titleSmall).foregroundStyle(Brand.text).lineLimit(1).minimumScaleFactor(0.7)
                    Image(systemName: "chevron.down").font(.system(size: 11)).foregroundStyle(Brand.textMuted)
                }
                if let badge, !badge.isEmpty { Text(badge).font(.labelSmall).foregroundStyle(Brand.cyan) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Brand.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: 開始 / 停止

    @ViewBuilder private var controls: some View {
        if captions.running {
            HStack(spacing: 8) {
                GhostButton(text: "停止翻譯".tr) { captions.stop() }
                if recording { GradientButton(text: "停止錄音".tr) { rec.stop() } }
            }
        } else if recording {
            GradientButton(text: "開始翻譯（目前的錄音）".tr) { start() }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                GradientButton(text: withRecording ? "開始錄音並翻譯".tr : "開始翻譯".tr, recordDot: withRecording) {
                    if withRecording { rec.start() }
                    start()
                }
                Toggle(isOn: $withRecording) {
                    Text("同時錄音（錄完照常產生報告）".tr).font(.bodySmall).foregroundStyle(Brand.textMuted)
                }
                .tint(Brand.cyan)
            }
        }
    }

    private func start() {
        // 同時錄音時等錄音起來再開始（用錄音的聲音）
        if withRecording && !recording {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { captions.start(sourceId: source, targetId: target) }
        } else {
            captions.start(sourceId: source, targetId: target)
        }
    }

    // MARK: 工具列

    private var toolbar: some View {
        HStack(spacing: 6) {
            tool("textformat.size.smaller", "字小一點".tr, enabled: textSize > 0) { textSize -= 1 }
            tool("textformat.size.larger", "字大一點".tr, enabled: textSize < 3) { textSize += 1 }
            tool(showOriginal ? "text.quote" : "text.badge.xmark", showOriginal ? "隱藏原文".tr : "顯示原文".tr, on: showOriginal) {
                showOriginal.toggle()
            }
            tool(readAloud ? "speaker.wave.2.fill" : "speaker.slash", readAloud ? "朗讀：開".tr : "朗讀：關".tr, on: readAloud) {
                readAloud.toggle()
                if !readAloud { speaker.stop() }
            }
            tool("arrow.up.left.and.arrow.down.right", "放大".tr) { enlarged = true }
            tool("trash", "清除".tr, enabled: !captions.lines.isEmpty) { captions.clear() }
            Spacer(minLength: 0)
        }
    }

    private func tool(_ icon: String, _ label: String, on: Bool = false, enabled: Bool = true, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 15, weight: .medium))
                .foregroundStyle(!enabled ? Brand.textFaint : (on ? Brand.cyan : Brand.text))
                .frame(width: 38, height: 34)
                .background(RoundedRectangle(cornerRadius: 10).fill(on ? AnyShapeStyle(Brand.accentSoft) : AnyShapeStyle(Brand.surfaceHigh)))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    // MARK: 字幕

    private var captionList: some View {
        let shape = RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous)
        let size = min(max(textSize, 0), 3)
        return ZStack(alignment: .topLeading) {
            shape.fill(Brand.cardFill)
            if captions.lines.isEmpty {
                Text(captions.running ? "開始說話，字幕會出現在這裡…".tr
                     : "選好語言按開始：沒在錄音時只用麥克風翻譯；勾「同時錄音」或錄音中，錄完照常產生完整報告。辨識與翻譯在手機上做（手機上辨識不了的語言會改用 Apple 線上辨識）。".tr)
                    .font(.bodyMedium).foregroundStyle(Brand.textFaint).padding(18)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 14) {
                            ForEach(captions.lines) { l in
                                row(l, size: size).id(l.id)
                            }
                        }
                        .padding(16)
                    }
                    .onChange(of: captions.lines) { lines in
                        if let last = lines.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                }
            }
        }
        .pageColumn()
        .frame(maxHeight: .infinity)
    }

    private func row(_ l: LiveCaptions.Line, size: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if showOriginal || l.partial {
                Text(l.original).font(.system(size: [12, 14, 17, 20][size])).foregroundStyle(Brand.textMuted)
                    .italic(l.partial)
            }
            if l.partial, let d = l.draft {
                Text(d).font(.system(size: [16, 20, 26, 34][size], weight: .regular)).italic()
                    .foregroundStyle(Brand.textFaint)
            } else if !l.partial, l.translated != "" {
                HStack(alignment: .top, spacing: 8) {
                    Circle().fill(Brand.cyan).frame(width: 5, height: 5).padding(.top, 10)
                    Text(l.translated ?? "…").font(.system(size: [16, 20, 26, 34][size], weight: .medium))
                        .foregroundStyle(l.translated == nil ? Brand.textFaint : Brand.text)
                    Spacer(minLength: 0)
                    if let t = l.translated, !t.isEmpty {
                        Button { Speaker.shared.speak(t, lang: captions.target, queue: false) } label: {
                            Image(systemName: "speaker.wave.2").font(.system(size: 14)).foregroundStyle(Brand.textMuted)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("念這句".tr)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .contextMenu {
            Button { UIPasteboard.general.string = [l.original, l.translated ?? ""].filter { !$0.isEmpty }.joined(separator: "\n") } label: {
                Label("複製".tr, systemImage: "doc.on.doc")
            }
        }
    }
}

/// 手機上辨識得了嗎（建 SFSpeechRecognizer 要一點時間，記起來）。
enum SFSpeechRecognizerCache {
    @MainActor private static var cache: [String: Bool] = [:]
    @MainActor static func onDevice(_ id: String) -> Bool? {
        if let v = cache[id] { return v }
        let v = TranslateLangs.sources().first { $0.id == id }?.onDevice
        if let v { cache[id] = v }
        return v
    }
}

/// 選語言的面板：搜尋、最近使用、全部（說的：地區口音、手機上／線上；翻成：Apple 翻譯支援的）。
private struct LangSheet: View {
    let side: LiveTranslateView.Side
    let current: String
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var sources: [TranslateLangs.Source] = []
    @State private var targets: [String] = TranslateLangs.fallbackTargets

    var body: some View {
        NavigationStack {
            List {
                if side == .source {
                    let recent = TranslateLangs.recent.compactMap { id in sources.first { $0.id == id } }
                    if query.isEmpty && !recent.isEmpty {
                        Section("最近使用".tr) { ForEach(recent) { sourceRow($0) } }
                    }
                    Section("全部".tr + "（\(filteredSources.count)）") { ForEach(filteredSources) { sourceRow($0) } }
                } else {
                    Section("全部".tr + "（\(filteredTargets.count)）") {
                        ForEach(filteredTargets, id: \.self) { id in
                            row(TranslateLangs.name(id), sub: nil, selected: id == current) { choose(id) }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "搜尋語言".tr)
            .navigationTitle(side == .source ? "說的語言".tr : "翻成".tr)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消".tr) { dismiss() } } }
        }
        .task {
            if side == .source { sources = TranslateLangs.sources() } else { targets = await TranslateLangs.targets() }
        }
    }

    private var filteredSources: [TranslateLangs.Source] {
        query.isEmpty ? sources : sources.filter { $0.name.localizedCaseInsensitiveContains(query) || $0.id.localizedCaseInsensitiveContains(query) }
    }
    private var filteredTargets: [String] {
        query.isEmpty ? targets : targets.filter { TranslateLangs.name($0).localizedCaseInsensitiveContains(query) || $0.localizedCaseInsensitiveContains(query) }
    }

    private func sourceRow(_ s: TranslateLangs.Source) -> some View {
        row(s.name, sub: s.onDevice ? "手機上".tr : "線上".tr, selected: s.id == current) { choose(s.id) }
    }

    private func row(_ name: String, sub: String?, selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(name).foregroundStyle(.primary)
                Spacer()
                if let sub { Text(sub).font(.footnote).foregroundStyle(.secondary) }
                if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }
        }
    }

    private func choose(_ id: String) { pick(id); dismiss() }
}

/// 放大：全螢幕只顯示最新一句，可以翻轉 180° 給對面的人看，也可以對調、朗讀。
private struct EnlargedCaption: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var captions = LiveCaptions.shared
    @State private var flipped = false

    var body: some View {
        let last = captions.lines.last { !$0.partial && ($0.translated ?? "") != "" }
        let partial = captions.lines.last { $0.partial }
        VStack(spacing: 16) {
            HStack(spacing: 22) {
                Button { dismiss() } label: { Image(systemName: "xmark").font(.title2) }.accessibilityLabel("關閉".tr)
                Spacer()
                Button { captions.swap() } label: { Image(systemName: "arrow.left.arrow.right").font(.title2) }
                    .accessibilityLabel("對調方向".tr)
                Button { if let t = last?.translated { Speaker.shared.speak(t, lang: captions.target, queue: false) } } label: {
                    Image(systemName: "speaker.wave.2").font(.title2)
                }
                .accessibilityLabel("念這句".tr)
                Button { withAnimation { flipped.toggle() } } label: { Image(systemName: "arrow.triangle.2.circlepath").font(.title2) }
                    .accessibilityLabel("翻轉給對面的人看".tr)
            }
            .foregroundStyle(Brand.text)
            Spacer()
            VStack(spacing: 18) {
                Text(last?.translated ?? partial?.draft ?? "…")
                    .font(.system(size: 44, weight: .semibold)).foregroundStyle(Brand.text)
                    .multilineTextAlignment(.center).minimumScaleFactor(0.4)
                Text(last?.original ?? partial?.original ?? "")
                    .font(.system(size: 20)).foregroundStyle(Brand.textMuted).multilineTextAlignment(.center)
            }
            .rotationEffect(.degrees(flipped ? 180 : 0))
            Spacer()
        }
        .padding(24)
        .background(AppBackdrop())
    }
}

private extension Text {
    func italic(_ on: Bool) -> Text { on ? self.italic() : self }
}
