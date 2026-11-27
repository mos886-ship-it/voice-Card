import SwiftUI

/// 別的畫面要打開即時翻譯頁（主頁的「翻譯」、錄音中的「即時翻譯」）。
@MainActor
final class TranslatePage: ObservableObject {
    static let shared = TranslatePage()
    @Published var presented = false
    func open() { presented = true }
}

/// 即時翻譯頁（照 Android `LiveTranslateScreen.kt`）：用手機錄音，邊錄邊顯示原文與譯文（Apple 語音辨識＋Apple 翻譯）。
/// 錄音照常存成一筆記錄、錄完照常產生完整報告；離開這頁字幕繼續跑，回來還看得到。
struct LiveTranslateView: View {
    /// 放在「翻譯」分頁裡（沒有返回鍵）；false = 全螢幕打開的那種
    var embedded = false
    @Environment(\.dismiss) private var dismiss
    @AppStorage(LiveCaptions.textSizeKey) private var textSize = 1
    @ObservedObject private var captions = LiveCaptions.shared
    @ObservedObject private var rec = PhoneRecorder.shared
    @ObservedObject private var langs = LangSettings.shared
    @State private var target: LiveCaptions.Target = .en

    private var recording: Bool { rec.isRecording && !rec.isFood }

    var body: some View {
        VStack(spacing: 0) {
            if !embedded { TopBar(title: "即時翻譯".tr, onBack: { dismiss() }) { EmptyView() } }
            VStack(alignment: .leading, spacing: 10) {
                // 說的語言（跟錄音的辨識語言同一個設定）
                Text("說的語言".tr).font(.titleSmall).foregroundStyle(Brand.text)
                LangPicker(value: langs.lang, disabled: captions.running) { langs.lang = $0 }
                Text("翻譯成".tr).font(.titleSmall).foregroundStyle(Brand.text)
                Picker("翻譯成".tr, selection: $target) {
                    ForEach(LiveCaptions.Target.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .onChange(of: target) { t in
                    if captions.running { captions.start(source: langs.lang, target: t) }   // 換語言：重新開始
                }

                if !LiveCaptions.supported(langs.lang) {
                    Text("台語、客語、粵語手機辨識不了，沒有即時字幕；錄完的報告不受影響。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.warning)
                } else if captions.running {
                    HStack(spacing: 8) {
                        GhostButton(text: "停止翻譯".tr) { captions.stop() }
                        if recording { GradientButton(text: "停止錄音".tr) { rec.stop() } }
                    }
                } else if recording {
                    GradientButton(text: "開始翻譯（目前的錄音）".tr) { startCaptions() }
                } else {
                    GradientButton(text: "開始錄音並翻譯".tr, recordDot: true) {
                        rec.start()
                        startCaptions()
                    }
                }
                if let s = captions.error ?? captions.status {
                    Text(s).font(.bodySmall).foregroundStyle(captions.error != nil ? Brand.warning : Brand.textMuted)
                }
            }
            .pageColumn()

            captionList
                .padding(.top, 10)

            Text("手機內建辨識＋裝置端翻譯，僅供參考；完整、準確的內容以錄完的報告為準。".tr)
                .font(.labelSmall).foregroundStyle(Brand.textFaint)
                .frame(maxWidth: .infinity, alignment: .leading)
                .pageColumn()
                .padding(.vertical, 8)
        }
        .background(AppBackdrop())
        .onAppear {
            target = captions.running ? captions.target : LiveCaptions.defaultTarget(langs.lang, uiLang: I18n.lang)
        }
    }

    private func startCaptions() { captions.start(source: langs.lang, target: target) }

    /// 字幕：新的在下面，自動捲到最新。
    private var captionList: some View {
        let shape = RoundedRectangle(cornerRadius: Brand.cardRadius, style: .continuous)
        return ZStack(alignment: .topLeading) {
            shape.fill(Brand.cardFill)
            if captions.lines.isEmpty {
                Text(captions.running ? "開始說話，字幕會出現在這裡…".tr
                     : "邊錄邊翻譯：上面選好語言，按開始。辨識與翻譯都在手機上做（手機沒有那個語言的離線語音時，會改用 Apple 線上辨識）；錄完照常產生完整報告。".tr)
                    .font(.bodyMedium).foregroundStyle(Brand.textFaint).padding(18)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 14) {
                            ForEach(captions.lines) { l in
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(l.original).font(.system(size: [12, 14, 17, 20][min(max(textSize, 0), 3)]))
                                        .foregroundStyle(Brand.textMuted)
                                        .italic(l.partial)
                                    if !l.partial, l.translated != "" {
                                        HStack(alignment: .top, spacing: 8) {
                                            Circle().fill(Brand.cyan).frame(width: 5, height: 5).padding(.top, 8)
                                            Text(l.translated ?? "…").font(.system(size: [16, 20, 26, 34][min(max(textSize, 0), 3)], weight: .medium))
                                                .foregroundStyle(l.translated == nil ? Brand.textFaint : Brand.text)
                                        }
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(l.id)
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
}

private extension Text {
    func italic(_ on: Bool) -> Text { on ? self.italic() : self }
}
