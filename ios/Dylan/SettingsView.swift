import SwiftUI

/// 「設定」分頁（照 Android `ui/SettingsScreen.kt` 的結構）：最上面系統狀態卡，沒有返回鍵。
/// AI 引擎、錄音地點、外觀、飲食目標等設定在之後的里程碑加入。
struct SettingsView: View {
    @EnvironmentObject private var link: PhoneLink
    @EnvironmentObject private var theme: ThemeStore
    @ObservedObject private var ai = AiStatus.shared

    private var themeNote: String {
        switch theme.mode {
        case .dark: return "深空藍底。手錶也是深色（OLED 省電）。"
        case .light: return "白底，戶外 / 白天比較好讀。手錶維持深色。"
        case .ink: return "宣紙底、墨黑字、朱紅點綴，跟 logo 同一套。手錶也會換成水墨。"
        case .auto: return "跟著 iPhone 的深色 / 淺色模式。手錶維持深色。"
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("設定").font(.headlineSmall).foregroundStyle(Brand.text)

                // ---------- 系統狀態 ----------
                GlassCard(highlight: true) {
                    Text("系統狀態").font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
                    StatusRow(label: "AI 報告引擎",
                              value: LocalAI.configured ? LocalAI.modelLabel : (ai.engine?.title ?? "偵測中…"),
                              detail: LocalAI.configured ? "你自己的 Mac：分說話者、免費、不出你的設備；連不到時改用 " + (MeetingStore.hasGeminiKey ? "Gemini" : "Apple")
                                                         : (ai.engine?.detail ?? ""),
                              color: (ai.engine?.ready ?? false) ? Brand.success : Brand.warning)
                        .padding(.top, 12)
                    DividerLine().padding(.vertical, 12)
                    StatusRow(label: "智慧手錶", value: watchValue, detail: watchDetail, color: watchColor)
                }

                SectionLabel(text: "AI 引擎")
                GlassCard {
                    Text("自動（依這支 iPhone）").font(.titleMedium).foregroundStyle(Brand.text)
                    Text("錄音報告用你的 Mac（有設定時）或 Google Gemini，兩者都能分出不同的說話者。Apple 內建的 AI 不分說話者，用來當輔助，以及整理飲食記錄（免費、不上傳）。")
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                    if let c = ai.capability {
                        VStack(spacing: 8) {
                            CapRow(name: "系統", value: c.osVersion, ok: true)
                            CapRow(name: "Apple Intelligence", value: c.onDeviceModel.label, ok: c.onDeviceModel == .available)
                            CapRow(name: "繁中語音辨識（裝置端）", value: c.speechZhTW ? "支援" : "不支援", ok: c.speechZhTW)
                            CapRow(name: "繁中 Apple 模型", value: c.modelZhTW ? "支援" : "不支援", ok: c.modelZhTW)
                            CapRow(name: "Apple 私有雲運算", value: c.pcc.label, ok: c.pcc == .available)
                        }
                        .padding(.top, 12)
                        DividerLine().padding(.vertical, 12)
                        Text("這支 iPhone 會用：").font(.labelMedium).foregroundStyle(Brand.textMuted)
                        VStack(alignment: .leading, spacing: 8) {
                            UseRow(task: "錄音報告", engine: LocalAI.configured ? LocalAI.modelLabel + "（連不到時改用 Gemini）" : (ai.engine?.title ?? ""))
                            UseRow(task: "輔助", engine: ai.helper?.title ?? "無（這支 iPhone 沒有 Apple 語音辨識）")
                            UseRow(task: "飲食", engine: ai.foodEngine?.title ?? "")
                        }
                        .padding(.top, 6)
                        Text("輔助：沒有 Gemini 金鑰、連不上或額度用完時，改用 Apple 先產生逐字稿與重點。Apple 引擎還在開發中，目前只有 Gemini 會實際執行。")
                            .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                    } else {
                        ProgressView().padding(.top, 12)
                    }
                }
                SectionLabel(text: "這台 Mac（自架）")
                LocalMacCard { Task { await ai.refresh() } }
                SectionLabel(text: "隱私模式")
                PrivacyCard()
                SectionLabel(text: "Gemini API Key")
                GeminiKeyCard { Task { await ai.refresh() } }
                SectionLabel(text: "外觀")
                GlassCard {
                    HStack(spacing: 8) {
                        ForEach(ThemeMode.allCases) { m in
                            ThemeChip(mode: m, selected: theme.mode == m) { theme.mode = m }
                        }
                    }
                    Text(themeNote).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 10)
                }
                SectionLabel(text: "隱私")
                placeholder("錄音存在這支 iPhone 與你的 Apple Watch。用你自己的 Mac 處理時，錄音只傳到你的 Mac，處理完不保留。使用 Gemini 時，錄音會上傳到 Google 分析，分析完立刻刪除上傳的檔案；免費方案的內容可能被 Google 用來改進產品。金鑰與存取碼只存在這支 iPhone 的鑰匙圈。")

                Text("大D \(version)").font(.bodySmall).foregroundStyle(Brand.textFaint)
                    .frame(maxWidth: .infinity).padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .task { await ai.refresh() }
    }

    private var watchValue: String {
        switch link.watchState {
        case .reachable, .connected: return "Apple Watch"
        case .notInstalled: return "手錶 App 未安裝"
        case .notPaired: return "沒有配對手錶"
        case .unsupported: return "未連線"
        }
    }

    private var watchDetail: String {
        switch link.watchState {
        case .reachable, .connected: return "在手錶按下錄音即可開始"
        case .notInstalled: return "請在 iPhone 的「Watch」App 安裝大D"
        default: return "請確認手錶已在「Watch」App 配對"
        }
    }

    private var watchColor: Color {
        switch link.watchState {
        case .reachable, .connected: return Brand.success
        case .notInstalled: return Brand.warning
        default: return Brand.textFaint
        }
    }

    private var version: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? ""
    }

    private func placeholder(_ text: String) -> some View {
        GlassCard { Text(text).font(.bodySmall).foregroundStyle(Brand.textMuted) }
    }
}

/// 外觀選項：選中的用漸層底，其他描邊（同篩選膠囊）；每個選項前面一個色票。
private struct ThemeChip: View {
    let mode: ThemeMode
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                swatch.frame(width: 28, height: 28).clipShape(Circle())
                    .overlay(Circle().stroke(Brand.outline, lineWidth: 1))
                Text(mode.label).font(.labelLarge).foregroundStyle(selected ? Color.white : Brand.textMuted)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.surfaceHigh)))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(selected ? Color.clear : Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private var swatch: some View {
        switch mode {
        case .dark: Palette.dark.bg.overlay(Circle().fill(Palette.dark.cyan).frame(width: 10, height: 10))
        case .light: Palette.light.bg.overlay(Circle().fill(Palette.light.blue).frame(width: 10, height: 10))
        case .ink: Palette.ink.bg.overlay(Circle().fill(Palette.ink.recording).frame(width: 10, height: 10))
        case .auto:
            HStack(spacing: 0) { Palette.light.bg; Palette.dark.bg }
        }
    }
}

/// 自架引擎：你自己的 Mac 跑 FunASR（分說話者）＋ Qwen（寫報告）。位址存 UserDefaults，存取碼存 Keychain。
private struct LocalMacCard: View {
    let onChange: () -> Void
    @AppStorage(LocalAI.urlKey) private var url = ""
    @State private var token = ""
    @State private var hasToken = !(Keychain.get(LocalAI.tokenAccount) ?? "").isEmpty
    @State private var result: (ok: Bool, text: String)?
    @State private var testing = false

    var body: some View {
        GlassCard {
            Text("用你自己的 Mac 跑 AI：會分說話者、免費、沒有額度，錄音不出你的設備。Mac 要開著，手機要連得到（同一個 Wi-Fi，或出門用 Tailscale）。")
                .font(.bodySmall).foregroundStyle(Brand.textMuted)
            field("位址", text: $url, prompt: LocalAI.defaultURL.isEmpty ? "http://Dylan-Mac-mini.local:8765" : LocalAI.defaultURL)
                .padding(.top, 10)
            if hasToken {
                HStack {
                    Image(systemName: "key.fill").foregroundStyle(Brand.success)
                    Text("存取碼已設定").font(.titleSmall).foregroundStyle(Brand.text)
                    Spacer()
                    Button("更換") { hasToken = false }.font(.labelLarge).foregroundStyle(Brand.cyan)
                    Button("刪除") {
                        Keychain.set("", for: LocalAI.tokenAccount); hasToken = false; result = nil; onChange()
                    }
                    .font(.labelLarge).foregroundStyle(Brand.error).padding(.leading, 8)
                }
                .padding(.top, 10)
            } else {
                HStack(spacing: 8) {
                    SecureField("", text: $token, prompt: Text("貼上存取碼（Mac 上 ~/dylan-local/config.json 的 token）").foregroundStyle(Brand.textFaint))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .foregroundStyle(Brand.text)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
                    GhostButton(text: "儲存", tint: Brand.cyan) {
                        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !t.isEmpty else { return }
                        Keychain.set(t, for: LocalAI.tokenAccount); token = ""; hasToken = true; onChange()
                    }
                }
                .padding(.top, 10)
            }
            GradientButton(text: testing ? "測試中…" : "測試連線", systemImage: "antenna.radiowaves.left.and.right",
                           enabled: !testing && hasToken) {
                testing = true
                Task {
                    do {
                        let h = try await LocalAI.health(url: LocalAI.url, token: LocalAI.token)
                        result = (true, "連線成功：\(h.asr ?? "")、\(h.llm ?? "")" + (h.busy == true ? "（正在處理別的錄音）" : ""))
                    } catch {
                        result = (false, error.localizedDescription)
                    }
                    testing = false
                }
            }
            .padding(.top, 10)
            if let result {
                Label(result.text, systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .font(.bodySmall).foregroundStyle(result.ok ? Brand.success : Brand.warning).padding(.top, 8)
            }
        }
    }

    private func field(_ label: String, text: Binding<String>, prompt: String) -> some View {
        HStack(spacing: 10) {
            Text(label).font(.labelMedium).foregroundStyle(Brand.textMuted).frame(width: 36, alignment: .leading)
            TextField("", text: text, prompt: Text(prompt).foregroundStyle(Brand.textFaint))
                .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                .foregroundStyle(Brand.text)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
        }
    }
}

/// 隱私模式：錄音只在手機上處理（Apple 語音辨識＋Apple 整理），不上傳 Gemini。
private struct PrivacyCard: View {
    @AppStorage(MeetingStore.privacyKey) private var on = false
    var body: some View {
        GlassCard {
            Toggle(isOn: $on) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("只在手機上處理").font(.titleMedium).foregroundStyle(Brand.text)
                    Text("開啟後錄音不上傳 Gemini，一律用 Apple 在手機上轉文字與整理（不分說話者，需要 iOS 26 以上）。適合醫療、法律、人事等敏感談話。")
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                }
            }
            .tint(Brand.success)
        }
    }
}

/// Gemini 金鑰：使用者自己貼上，存 Keychain。畫面上只顯示末 4 碼。
private struct GeminiKeyCard: View {
    let onChange: () -> Void
    @State private var saved = Keychain.get(Keychain.geminiKey) ?? ""
    @State private var input = ""
    @State private var editing = false

    var body: some View {
        GlassCard {
            if !saved.isEmpty && !editing {
                HStack {
                    Image(systemName: "key.fill").foregroundStyle(Brand.success)
                    Text("已設定（••••\(String(saved.suffix(4)))）").font(.titleSmall).foregroundStyle(Brand.text)
                    Spacer()
                    Button("更換") { input = ""; editing = true }.font(.labelLarge).foregroundStyle(Brand.cyan)
                    Button("刪除") {
                        Keychain.set("", for: Keychain.geminiKey)
                        saved = ""
                        onChange()
                    }
                    .font(.labelLarge).foregroundStyle(Brand.error).padding(.leading, 8)
                }
            } else {
                Text("到 Google AI Studio 免費申請金鑰（不用信用卡），複製後貼在這裡。").font(.bodySmall).foregroundStyle(Brand.textMuted)
                Link("aistudio.google.com/apikey", destination: URL(string: "https://aistudio.google.com/apikey")!)
                    .font(.labelLarge).foregroundStyle(Brand.cyan).padding(.top, 4)
                SecureField("", text: $input, prompt: Text("貼上 Gemini API Key").foregroundStyle(Brand.textFaint))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .foregroundStyle(Brand.text)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
                    .padding(.top, 10)
                HStack(spacing: 10) {
                    GradientButton(text: "儲存", enabled: !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                        let k = input.trimmingCharacters(in: .whitespacesAndNewlines)
                        Keychain.set(k, for: Keychain.geminiKey)
                        saved = k
                        input = ""
                        editing = false
                        onChange()
                    }
                    if editing { GhostButton(text: "取消") { editing = false; input = "" } }
                }
                .padding(.top, 10)
            }
        }
    }
}

/// 「這支 iPhone 會用」一列：用途 ＋ 引擎。
private struct UseRow: View {
    let task: String
    let engine: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(task).font(.labelMedium).foregroundStyle(Brand.textMuted).frame(width: 56, alignment: .leading)
            Text(engine).font(.titleSmall).foregroundStyle(Brand.text)
        }
    }
}

/// AI 能力一列：名稱 ＋ 狀態（可用綠色、不可用灰色）。
private struct CapRow: View {
    let name: String
    let value: String
    let ok: Bool
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(name).font(.bodySmall).foregroundStyle(Brand.textMuted)
            Spacer(minLength: 8)
            Image(systemName: ok ? "checkmark.circle.fill" : "minus.circle")
                .font(.system(size: 12)).foregroundStyle(ok ? Brand.success : Brand.textFaint)
            Text(value).font(.labelMedium).foregroundStyle(ok ? Brand.text : Brand.textFaint)
                .multilineTextAlignment(.trailing)
        }
    }
}

private struct StatusRow: View {
    let label: String
    let value: String
    let detail: String
    let color: Color
    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(color).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 0) {
                Text(label).font(.labelMedium).foregroundStyle(Brand.textMuted)
                Text(value).font(.titleMedium).foregroundStyle(Brand.text)
                Text(detail).font(.bodySmall).foregroundStyle(Brand.textFaint)
            }
        }
    }
}
