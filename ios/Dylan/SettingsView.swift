import SwiftUI

/// 「設定」分頁（照 Android `ui/SettingsScreen.kt` 的結構）：最上面系統狀態卡，沒有返回鍵。
/// AI 引擎、錄音地點、外觀、飲食目標等設定在之後的里程碑加入。
struct SettingsView: View {
    @Environment(\.pageWidth) private var width
    @AppStorage(UIMode.key) private var mode: UIMode = .business
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

                // ---------- 介面模式 ----------
                GlassCard {
                    Text("介面模式").font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
                    HStack(spacing: 8) {
                        ForEach(UIMode.allCases) { m in
                            ModeChip(mode: m, selected: mode == m) { mode = m }
                        }
                    }
                    .padding(.top, 10)
                    Text(mode.note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 10)
                }

                TwoColumns(wide: Wide.twoColumns(width)) {
                // ---------- 系統狀態 ----------
                GlassCard(highlight: true) {
                    Text("系統狀態").font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
                    StatusRow(label: "AI 報告引擎",
                              value: LocalAI.configured ? LocalAI.modelLabel : (ai.engine?.title ?? "偵測中…"),
                              detail: LocalAI.configured ? "大D的主機：分說話者、免費、不出你的設備；連不到時改用 " + (MeetingStore.hasGeminiKey ? "Gemini" : "Apple")
                                                         : (ai.engine?.detail ?? ""),
                              color: LocalAI.configured || (ai.engine?.ready ?? false) ? Brand.success : Brand.warning)
                        .padding(.top, 12)
                    if mode == .tech || hasWatch {
                        DividerLine().padding(.vertical, 12)
                        StatusRow(label: "智慧手錶", value: watchValue, detail: watchDetail, color: watchColor)
                    }
                }

                if mode == .tech {
                SectionLabel(text: "AI 引擎")
                GlassCard {
                    Text("自動（依這支 iPhone）").font(.titleMedium).foregroundStyle(Brand.text)
                    Text("錄音報告用大D的主機（有設定時）或 Google Gemini，兩者都能分出不同的說話者。Apple 內建的 AI 不分說話者，用來當輔助，以及整理飲食記錄（免費、不上傳）。")
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
                        Text("輔助：大D的主機和 Gemini 都不能用時，改用 Apple 在手機上產生逐字稿與重點（不分說話者）。")
                            .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                    } else {
                        ProgressView().padding(.top, 12)
                    }
                }
                }
                } right: {
                SectionLabel(text: "大D的主機")
                LocalMacCard { Task { await ai.refresh() } }
                SectionLabel(text: "隱私模式")
                PrivacyCard()
                SectionLabel(text: "Gemini API Key")
                GeminiKeyCard { Task { await ai.refresh() } }
                SectionLabel(text: "這台裝置")
                DeviceNameCard()
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
                placeholder("錄音存在這支 iPhone 與你的 Apple Watch。用大D的主機 處理時，錄音只傳到大D的主機，處理完不保留。使用 Gemini 時，錄音會上傳到 Google 分析，分析完立刻刪除上傳的檔案；免費方案的內容可能被 Google 用來改進產品。金鑰與存取碼只存在這支 iPhone 的鑰匙圈。")

                }

                Text("大D \(version)").font(.bodySmall).foregroundStyle(Brand.textFaint)
                    .frame(maxWidth: .infinity).padding(.top, 8)
            }
            .pageColumn()
            .padding(.vertical, 12)
        }
        .task { await ai.refresh() }
    }

    private var hasWatch: Bool {
        switch link.watchState {
        case .reachable, .connected, .notInstalled: return true
        default: return false
        }
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

/// 自架引擎：你自己的 Mac 跑語音辨識（分說話者）＋ 語言模型（寫報告），模型可以在這裡換。位址存 UserDefaults，存取碼存 Keychain。
private struct LocalMacCard: View {
    let onChange: () -> Void
    @AppStorage(LocalAI.urlKey) private var url = ""
    @State private var token = ""
    @State private var hasToken = !(Keychain.get(LocalAI.tokenAccount) ?? "").isEmpty
    @State private var result: (ok: Bool, text: String)?
    @State private var testing = false
    @State private var models: LocalAI.Models?
    @State private var asr = ""
    @State private var llm = ""
    @State private var switching: String?   // 換模型中的提示
    @State private var password = ""
    @State private var pairing = false
    @State private var scanning = false
    @State private var confirmUnpair = false
    @State private var passwordSet: Bool?
    @State private var editingPassword = false
    @State private var newPassword = ""
    @State private var newPassword2 = ""
    @State private var devices: [LocalAI.Device] = []
    @State private var admin = LocalAI.isAdmin
    @AppStorage(UIMode.key) private var mode: UIMode = .business

    var body: some View {
        GlassCard {
            Text("用大D的主機 跑 AI：會分說話者、免費、沒有額度，錄音不出你的設備。主機要開著，手機要連得到（同一個 Wi-Fi，或出門用固定 IP）。")
                .font(.bodySmall).foregroundStyle(Brand.textMuted)
            if hasToken {
                // ---------- 已配對 ----------
                HStack(spacing: 10) {
                    Image(systemName: "checkmark.seal.fill").foregroundStyle(Brand.success)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("已連上大D的主機").font(.titleSmall).foregroundStyle(Brand.text)
                        Text(LocalAI.url).font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1)
                    }
                    Spacer()
                    Button("取消配對") { confirmUnpair = true }.font(.labelLarge).foregroundStyle(Brand.error)
                }
                .padding(.top, 10)
                GradientButton(text: testing ? "測試中…" : "測試連線", systemImage: "antenna.radiowaves.left.and.right",
                               enabled: !testing) { test() }
                    .padding(.top, 10)
                if let result {
                    Label(result.text, systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.bodySmall).foregroundStyle(result.ok ? Brand.success : Brand.warning).padding(.top, 8)
                }
                if !admin {
                    Text("這台是一般使用者：換模型、配對密碼、管理別台裝置要用管理者的裝置。")
                        .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                }
                // 配對密碼：其他裝置配對用（只有管理者）
                if admin {
                DividerLine().padding(.vertical, 12)
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("配對密碼").font(.titleSmall).foregroundStyle(Brand.text)
                        Text(passwordSet == true ? "已設定：新裝置輸入這個密碼就能連上" : "還沒設定：設了之後新裝置輸入密碼就能連上")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer()
                    Button(passwordSet == true ? "更換" : "設定") { editingPassword.toggle() }
                        .font(.labelLarge).foregroundStyle(Brand.cyan)
                }
                if editingPassword {
                    secure("新的配對密碼（至少 8 個字）", text: $newPassword).padding(.top, 8)
                    secure("再輸入一次", text: $newPassword2).padding(.top, 6)
                    GradientButton(text: "儲存密碼", enabled: newPassword.count >= 8 && newPassword == newPassword2) {
                        Task {
                            do {
                                try await LocalAI.setPassword(newPassword)
                                result = (true, "配對密碼已設定")
                                passwordSet = true
                                editingPassword = false
                                newPassword = ""; newPassword2 = ""
                            } catch { result = (false, error.localizedDescription) }
                        }
                    }
                    .padding(.top, 8)
                }
                }
                if mode == .tech, admin, !devices.isEmpty {
                    DividerLine().padding(.vertical, 12)
                    Text("已配對的裝置").font(.labelMedium).foregroundStyle(Brand.textMuted)
                    ForEach(devices) { d in
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(d.name + (d.current ? "（這台）" : "") + (d.admin == true ? " · 管理者" : ""))
                                    .font(.bodyMedium).foregroundStyle(Brand.text)
                                Text("配對 " + Fmt.stamp(Date(timeIntervalSince1970: d.created), "M/d HH:mm")
                                     + (d.last_seen.map { " · 最後使用 " + Fmt.stamp(Date(timeIntervalSince1970: $0), "M/d HH:mm") } ?? "")
                                     + (d.via == "qr" ? " · QR" : " · 密碼"))
                                    .font(.labelMedium).foregroundStyle(Brand.textFaint)
                            }
                            Spacer()
                            if !d.current {
                                Button(d.admin == true ? "改一般" : "設管理者") {
                                    Task {
                                        do { try await LocalAI.setAdmin(d.id, d.admin != true) } catch {
                                            result = (false, error.localizedDescription)
                                        }
                                        await loadDevices()
                                    }
                                }
                                .font(.labelLarge).foregroundStyle(Brand.cyan)
                                Button("取消") {
                                    Task { try? await LocalAI.revoke(d.id); await loadDevices() }
                                }
                                .font(.labelLarge).foregroundStyle(Brand.error)
                                    .padding(.leading, 10)
                            }
                        }
                        .padding(.top, 6)
                    }
                }
            } else {
                // ---------- 還沒配對：密碼或 QR 碼 ----------
                field("位址", text: $url, prompt: LocalAI.defaultURL.isEmpty ? "http://Dylan-Mac-mini.local:8765" : LocalAI.defaultURL)
                    .padding(.top, 10)
                HStack(spacing: 8) {
                    secure("配對密碼", text: $password)
                    GhostButton(text: pairing ? "配對中…" : "配對", tint: Brand.cyan) {
                        guard !pairing, !password.isEmpty else { return }
                        pairing = true
                        Task {
                            do {
                                try await LocalAI.pair(url: url.isEmpty ? LocalAI.defaultURL : url, password: password)
                                password = ""
                                paired("配對完成")
                            } catch { result = (false, error.localizedDescription) }
                            pairing = false
                        }
                    }
                }
                .padding(.top, 8)
                GhostButton(text: "掃描主機上的 QR 碼", systemImage: "qrcode.viewfinder", tint: Brand.cyan) { scanning = true }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)
                Text("QR 碼：在家裡的 Wi-Fi 用瀏覽器打開 http://Dylan-Mac-mini.local:8765/pair（或在主機上打開 http://localhost:8765/pair），用這台的相機掃。第一次還沒設密碼，就先用 QR 碼配對，再到這裡設定密碼。")
                    .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                if let result {
                    Label(result.text, systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.bodySmall).foregroundStyle(result.ok ? Brand.success : Brand.warning).padding(.top, 8)
                }
                if mode == .tech {
                    // 進階：直接貼主機的主存取碼（舊方法）
                    HStack(spacing: 8) {
                        secure("或貼上存取碼（主機 config.json 的 token）", text: $token)
                        GhostButton(text: "儲存", tint: Brand.cyan) {
                            let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !t.isEmpty else { return }
                            if !url.isEmpty { url = LocalAI.normalize(url) }
                            Keychain.set(t, for: LocalAI.tokenAccount); token = ""
                            paired("存取碼已儲存")
                        }
                    }
                    .padding(.top, 10)
                }
            }
            if let models, mode == .tech, admin {
                // 實測時切換用：Mac 上有哪些模型就列哪些（Mac 的 config.json 可以再加）
                picker("語音辨識", selection: $asr, options: models.asr_options)
                    .padding(.top, 12)
                picker("報告模型", selection: $llm, options: models.llm_options)
                    .padding(.top, 8)
                if let switching {
                    HStack(spacing: 8) {
                        ProgressView().tint(Brand.cyan)
                        Text(switching).font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    .padding(.top, 8)
                }
            }
        }
        .task { if hasToken { await loadModels(); await loadDevices() } }
        .sheet(isPresented: $scanning) {
            ZStack(alignment: .bottom) {
                QRScanner { text in
                    scanning = false
                    guard let link = URL(string: text) else { result = (false, "這不是大D的主機的配對 QR 碼"); return }
                    Task {
                        do {
                            try await LocalAI.pair(link: link)
                            paired("配對完成")
                        } catch { result = (false, error.localizedDescription) }
                    }
                }
                .ignoresSafeArea()
                Text("對準主機配對頁上的 QR 碼").font(.titleSmall).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.6)))
                    .padding(.bottom, 40)
            }
        }
        .confirmationDialog("取消和大D的主機的配對？", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("取消配對", role: .destructive) {
                Task {
                    await LocalAI.unpair()
                    hasToken = false; result = nil; models = nil; devices = []; passwordSet = nil
                    onChange()
                }
            }
        } message: { Text("這台裝置之後要再用密碼或 QR 碼配對一次。") }
        .onReceive(NotificationCenter.default.publisher(for: LocalAI.didPair)) { _ in
            hasToken = !LocalAI.token.isEmpty
            url = UserDefaults.standard.string(forKey: LocalAI.urlKey) ?? url
            Task { await loadModels(); await loadDevices() }
        }
    }

    private func paired(_ text: String) {
        hasToken = !LocalAI.token.isEmpty
        result = (true, text)
        onChange()
        test()
    }

    private func test() {
        testing = true
        Task {
            do {
                let h = try await LocalAI.health(url: LocalAI.url, token: LocalAI.token)
                result = (true, "連線成功：\(h.asr ?? "")、\(h.llm ?? "")" + (h.busy == true ? "（正在處理別的錄音）" : ""))
                await loadModels()
                await loadDevices()
                onChange()
            } catch {
                result = (false, error.localizedDescription)
            }
            testing = false
        }
    }

    private func loadDevices() async {
        guard let d = try? await LocalAI.devices() else { return }
        devices = d.devices
        passwordSet = d.password_set
        admin = d.admin ?? true
    }

    private func secure(_ prompt: String, text: Binding<String>) -> some View {
        SecureField("", text: text, prompt: Text(prompt).foregroundColor(Brand.textFaint))
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .foregroundStyle(Brand.text)
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
    }

    private func picker(_ label: String, selection: Binding<String>, options: [LocalAI.Option]) -> some View {
        HStack(spacing: 10) {
            Text(label).font(.labelMedium).foregroundStyle(Brand.textMuted).frame(minWidth: 60, alignment: .leading).fixedSize()
            Menu {
                ForEach(options, id: \.id) { o in
                    Button { choose(label == "語音辨識" ? o.id : asr, label == "語音辨識" ? llm : o.id) } label: {
                        if o.id == selection.wrappedValue { Label(o.label, systemImage: "checkmark") } else { Text(o.label) }
                    }
                }
            } label: {
                HStack {
                    Text(options.first { $0.id == selection.wrappedValue }?.label ?? "—")
                        .font(.bodyMedium).foregroundStyle(Brand.text).lineLimit(2).multilineTextAlignment(.leading)
                    Spacer()
                    Image(systemName: "chevron.up.chevron.down").font(.caption).foregroundStyle(Brand.textMuted)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
            }
            .disabled(switching != nil)
        }
    }

    private func loadModels() async {
        guard let m = try? await LocalAI.models() else { return }
        models = m
        asr = m.asr_id ?? ""
        llm = m.llm_id ?? ""
        onChange()   // 上方「AI 報告引擎」的模型名稱跟著更新
        if m.state == "loading", switching == nil { await waitReady() }
    }

    private func choose(_ newAsr: String, _ newLlm: String) {
        guard newAsr != asr || newLlm != llm else { return }
        asr = newAsr; llm = newLlm
        Task {
            do {
                try await LocalAI.select(asr: newAsr, llm: newLlm)
                await waitReady()
            } catch {
                result = (false, error.localizedDescription)
                await loadModels()
            }
        }
    }

    /// Mac 換模型（第一次要下載好幾 GB）期間每 3 秒問一次，好了就更新顯示的模型名稱。
    private func waitReady() async {
        switching = "主機正在載入模型（第一次會先下載，可能要幾分鐘）…"
        defer { switching = nil }
        for _ in 0..<600 {
            try? await Task.sleep(for: .seconds(3))
            guard let h = try? await LocalAI.health(url: LocalAI.url, token: LocalAI.token) else { continue }
            if h.state != "loading" {
                if let e = h.error, !e.isEmpty { result = (false, e) } else { result = (true, "已換成：\(h.asr ?? "")、\(h.llm ?? "")") }
                if let m = try? await LocalAI.models() { asr = m.asr_id ?? asr; llm = m.llm_id ?? llm }
                onChange()
                return
            }
        }
    }

    private func field(_ label: String, text: Binding<String>, prompt: String) -> some View {
        HStack(spacing: 10) {
            Text(label).font(.labelMedium).foregroundStyle(Brand.textMuted).frame(minWidth: 36, alignment: .leading).fixedSize()
            TextField("", text: text, prompt: Text(prompt).foregroundColor(Brand.textFaint))
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
                    Text("不上傳到雲端").font(.titleMedium).foregroundStyle(Brand.text)
                    Text("開啟後錄音不送 Gemini：有設定大D的主機 就用大D的主機（分說話者），否則用 Apple 在手機上轉文字與整理（不分說話者，需要 iOS 26 以上）。適合醫療、法律、人事等敏感談話。")
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
                SecureField("", text: $input, prompt: Text("貼上 Gemini API Key").foregroundColor(Brand.textFaint))
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
            Text(task).font(.labelMedium).foregroundStyle(Brand.textMuted).frame(minWidth: 56, alignment: .leading).fixedSize()
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

/// 介面模式選項（同外觀選項的樣式）。
private struct ModeChip: View {
    let mode: UIMode
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: mode.icon).font(.system(size: 15, weight: .semibold))
                Text(mode.label).font(.labelLarge)
            }
            .foregroundStyle(selected ? Color.white : Brand.textMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.surfaceHigh)))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(selected ? Color.clear : Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// 這台 iPhone / iPad 的名字：顯示在錄音的「來源」。iOS 不讓 App 讀系統設定裡的名字，所以在這裡取。
private struct DeviceNameCard: View {
    @AppStorage(DeviceInfo.key) private var name = ""

    var body: some View {
        GlassCard {
            HStack(spacing: 10) {
                Text("名稱").font(.labelMedium).foregroundStyle(Brand.textMuted).frame(minWidth: 36, alignment: .leading).fixedSize()
                TextField("", text: $name, prompt: Text(Meeting.isPad ? "例如：Dylan 的 iPad" : "例如：Dylan 的 iPhone").foregroundColor(Brand.textFaint))
                    .autocorrectionDisabled()
                    .foregroundStyle(Brand.text)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
            }
            Text("會顯示在錄音的「來源」，例如「\(Meeting.isPad ? "iPad" : "手機") · \(name.isEmpty ? (Meeting.isPad ? "Dylan 的 iPad" : "Dylan 的 iPhone") : name)（\(DeviceInfo.modelShort)）」。iOS 不讓 App 讀系統設定裡的裝置名稱，所以請在這裡填一次。")
                .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
        }
    }
}
