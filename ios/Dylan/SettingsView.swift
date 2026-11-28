import SwiftUI

/// 「設定」分頁（照 Android `ui/SettingsScreen.kt` 的結構）：最上面系統狀態卡，沒有返回鍵。
/// AI 引擎、錄音地點、外觀、飲食目標等設定在之後的里程碑加入。
struct SettingsView: View {
    @Environment(\.pageWidth) private var width
    @AppStorage(UIMode.key) private var mode: UIMode = .business
    @EnvironmentObject private var link: PhoneLink
    @EnvironmentObject private var theme: ThemeStore
    @AppStorage(LayoutMode.key) private var layout: LayoutMode = .quick
    @AppStorage(I18n.key) private var uiLang = I18n.auto
    @AppStorage(ReportLang.key) private var reportLang = ReportLang.auto
    @AppStorage(ReportDomain.key) private var reportDomain = ReportDomain.auto
    @AppStorage(FoodRegion.key) private var foodCountry = FoodRegion.auto
    /// 報告語言「跟介面」時看的是設定裡選的介面語言（還沒重開 App 也算）
    private var reportLangNow: ReportLang {
        reportLang == ReportLang.auto ? ReportLang.fromLanguage(I18n.langOf(uiLang)) : (ReportLang(rawValue: reportLang) ?? .zh)
    }
    @ObservedObject private var ai = AiStatus.shared

    private var themeNote: String {
        switch theme.mode {
        case .clear: return "淺灰紙底、白卡片、藍色重點：資訊清楚，適合工作。手錶維持深色。".tr
        case .taiwan: return "夜藍底、晶片青與梅花紅，以科技為主題，可選背景畫。深色，適合晚上與會議室。".tr
        case .ukiyoe: return "和紙底、普魯士藍與朱紅，木刻版畫的平塗色塊與墨線，可選背景畫。手錶維持深色。".tr
        case .ink: return "宣紙底、墨黑字、朱紅點綴，跟 logo 同一套。手錶也會換成水墨。".tr
        }
    }

    private func rowLabel(_ t: String) -> some View {
        Text(t).font(.titleSmall).foregroundStyle(Brand.text)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("設定".tr).font(.headlineSmall).foregroundStyle(Brand.text)

                TwoColumns(wide: Wide.twoColumns(width)) {
                // ---------- 系統狀態 ----------
                GlassCard(highlight: true) {
                    Text("系統狀態".tr).font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
                    StatusRow(label: "AI 報告引擎".tr,
                              value: LocalAI.active ? LocalAI.modelLabel : (ai.engine?.title ?? "偵測中…".tr),
                              detail: LocalAI.active ? "大D的主機：分說話者、免費、不出你的設備；連不到時改用 ".tr + (MeetingStore.hasGeminiKey ? "Gemini" : "Apple")
                                                         : (ai.engine?.detail ?? ""),
                              color: LocalAI.active || (ai.engine?.ready ?? false) ? Brand.success : Brand.warning)
                        .padding(.top, 12)
                    if mode == .tech || hasWatch {
                        DividerLine().padding(.vertical, 12)
                        StatusRow(label: "智慧手錶".tr, value: watchValue, detail: watchDetail, color: watchColor)
                    }
                }

                // ---------- 介面：排布、資訊量、配色（三個各自獨立） ----------
                SectionLabel(text: "介面".tr)
                GlassCard {
                    // 語言：介面（App 裡的字）與報告（AI 寫報告用的語言）分開選
                    rowLabel("介面語言".tr)
                    Picker("介面語言".tr, selection: $uiLang) {
                        ForEach(I18n.options, id: \.0) { Text($0.1.tr).tag($0.0) }
                    }
                    .pickerStyle(.segmented).padding(.top, 8)
                    if I18n.langOf(uiLang) != I18n.lang {
                        Text("從 App 切換器把大D 往上滑關掉，再重新開啟，就會換成新的語言。".tr)
                            .font(.bodySmall).foregroundStyle(Brand.warning).padding(.top, 8)
                    }

                    DividerLine().padding(.vertical, 14)
                    rowLabel("報告語言".tr)
                    Picker("報告語言".tr, selection: $reportLang) {
                        Text("跟介面".tr).tag(ReportLang.auto)
                        ForEach(ReportLang.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented).padding(.top, 8)
                    Text("AI 的標題、摘要與報告用這個語言寫；逐字稿照錄音原本的語言。現在：".tr + reportLangNow.label
                         + "。已經做好的報告可以在詳情頁「重新產生」。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)

                    DividerLine().padding(.vertical, 14)
                    HStack {
                        rowLabel("領域專家模式".tr)
                        Spacer()
                        Picker("領域專家模式".tr, selection: $reportDomain) {
                            ForEach(ReportDomain.options, id: \.id) { Text($0.name).tag($0.id) }
                        }
                        .pickerStyle(.menu).tint(Brand.blue)
                    }
                    Text("法律、醫療、財務等專業內容，報告多一節「專業重點」，術語與重點照該領域整理。自動判斷：一般對話不加。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)

                    DividerLine().padding(.vertical, 14)
                    rowLabel("排布".tr)
                    HStack(spacing: 8) {
                        ForEach(LayoutMode.allCases) { l in
                            LayoutChip(mode: l, selected: layout == l) { layout = l }
                        }
                    }
                    .padding(.top, 8)
                    Text(layout.note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)

                    DividerLine().padding(.vertical, 14)
                    rowLabel("資訊量".tr)
                    HStack(spacing: 8) {
                        ForEach(UIMode.allCases) { m in
                            ModeChip(mode: m, selected: mode == m) { mode = m }
                        }
                    }
                    .padding(.top, 8)
                    Text(mode.note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)

                    DividerLine().padding(.vertical, 14)
                    rowLabel("配色".tr)
                    // 兩行兩格：清晰、暖和日常 / 深色、水墨
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                        ForEach(ThemeMode.allCases) { m in
                            ThemeChip(mode: m, selected: theme.mode == m) { theme.mode = m }
                        }
                    }
                    .padding(.top, 8)
                    Text(themeNote).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
                    if theme.mode == .ink {
                        Text("背景畫".tr).font(.labelLarge).foregroundStyle(Brand.text).padding(.top, 14)
                        Picker("背景畫".tr, selection: $theme.inkBg) {
                            ForEach(InkBg.allCases) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.segmented).padding(.top, 8)
                        Text(theme.inkBg.note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
                    } else if theme.mode == .taiwan {
                        Text("背景畫".tr).font(.labelLarge).foregroundStyle(Brand.text).padding(.top, 14)
                        Picker("背景畫".tr, selection: $theme.taiwanBg) {
                            ForEach(TaiwanBg.allCases) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.segmented).padding(.top, 8)
                        Text(theme.taiwanBg.note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
                    } else if theme.mode == .ukiyoe {
                        Text("背景畫".tr).font(.labelLarge).foregroundStyle(Brand.text).padding(.top, 14)
                        Picker("背景畫".tr, selection: $theme.ukiyoeBg) {
                            ForEach(UkiyoeBg.allCases) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.segmented).padding(.top, 8)
                        Text(theme.ukiyoeBg.note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
                    }
                }

                SectionLabel(text: "飲食".tr)
                GlassCard {
                    HStack {
                        rowLabel("用餐的國家".tr)
                        Spacer()
                        Picker("用餐的國家".tr, selection: $foodCountry) {
                            Text("自動".tr).tag(FoodRegion.auto)
                            ForEach(FoodRegion.supported, id: \.self) { Text(FoodRegion.name($0)).tag($0) }
                        }
                        .pickerStyle(.menu).tint(Brand.blue)
                    }
                    Text("份量依當地習慣估算。自動：照這一餐的地點，沒有地點就照手機地區（現在：".tr
                         + FoodRegion.name(FoodRegion.of()) + "）。台灣、美國、日本、韓國的餐用當地官方營養資料計算，其他國家為 AI 估算。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                }

                SectionLabel(text: "健康同步".tr)
                HealthSyncCard()

                SectionLabel(text: "車用".tr)
                CarSettingsView()

                if mode == .tech {
                SectionLabel(text: "AI 引擎".tr)
                GlassCard {
                    Text("自動（依這支 iPhone）".tr).font(.titleMedium).foregroundStyle(Brand.text)
                    Text("錄音報告用大D的主機（有設定時）或 Google Gemini，兩者都能分出不同的說話者。Apple 內建的 AI 不分說話者，用來當輔助，以及整理飲食記錄（免費、不上傳）。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 4)
                    if let c = ai.capability {
                        VStack(spacing: 8) {
                            CapRow(name: "系統".tr, value: c.osVersion, ok: true)
                            CapRow(name: "Apple Intelligence", value: c.onDeviceModel.label, ok: c.onDeviceModel == .available)
                            CapRow(name: "繁中語音辨識（裝置端）".tr, value: c.speechZhTW ? "支援".tr : "不支援".tr, ok: c.speechZhTW)
                            CapRow(name: "繁中 Apple 模型".tr, value: c.modelZhTW ? "支援".tr : "不支援".tr, ok: c.modelZhTW)
                            CapRow(name: "Apple 私有雲運算".tr, value: c.pcc.label, ok: c.pcc == .available)
                        }
                        .padding(.top, 12)
                        DividerLine().padding(.vertical, 12)
                        Text("這支 iPhone 會用：".tr).font(.labelMedium).foregroundStyle(Brand.textMuted)
                        VStack(alignment: .leading, spacing: 8) {
                            UseRow(task: "錄音報告".tr, engine: LocalAI.active ? LocalAI.modelLabel + "（連不到時改用 Gemini）".tr : (ai.engine?.title ?? ""))
                            UseRow(task: "輔助".tr, engine: ai.helper?.title ?? "無（這支 iPhone 沒有 Apple 語音辨識）".tr)
                            UseRow(task: "飲食".tr, engine: ai.foodEngine?.title ?? "")
                        }
                        .padding(.top, 6)
                        Text("輔助：大D的主機和 Gemini 都不能用時，改用 Apple 在手機上產生逐字稿與重點（不分說話者）。".tr)
                            .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                    } else {
                        ProgressView().padding(.top, 12)
                    }
                }
                }
                } right: {
                SectionLabel(text: "AI 模式".tr)
                AiModeCard { Task { await ai.refresh() } }
                SectionLabel(text: "大D的主機".tr)
                LocalMacCard { Task { await ai.refresh() } }
                SectionLabel(text: "隱私模式".tr)
                PrivacyCard()
                SectionLabel(text: "Gemini API Key")
                GeminiKeyCard { Task { await ai.refresh() } }
                SectionLabel(text: "這台裝置".tr)
                DeviceNameCard()
                SectionLabel(text: "隱私".tr)
                placeholder("錄音存在這支 iPhone 與你的 Apple Watch。用大D的主機 處理時，錄音只傳到大D的主機，處理完不保留。使用 Gemini 時，錄音會上傳到 Google 分析，分析完立刻刪除上傳的檔案；免費方案的內容可能被 Google 用來改進產品。金鑰與存取碼只存在這支 iPhone 的鑰匙圈。".tr)
                SectionLabel(text: "內建資料".tr)
                GlassCard {
                    Text("飲食熱量計算使用衛生福利部食品藥物管理署「食品營養成分資料集」（2026/10/05 版，只取熱量、蛋白質、脂肪、碳水，每 100 克），依政府資料開放授權條款－第1版使用。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    VStack(alignment: .leading, spacing: 8) {
                        Link("食品營養成分資料集（政府資料開放平臺）".tr, destination: URL(string: "https://data.gov.tw/dataset/8543")!)
                        Link("政府資料開放授權條款－第1版".tr, destination: URL(string: "https://data.gov.tw/license")!)
                    }
                    .font(.labelLarge).tint(Brand.cyan).padding(.top, 10)
                }
                // 美國、日本、韓國的營養資料（這一餐在當地時用；見 FoodRegion）
                GlassCard {
                    Text("在美國的餐點使用美國農業部 U.S. Department of Agriculture, Agricultural Research Service「FoodData Central」（SR Legacy，公共領域 CC0），名稱截短、只取熱量與營養素。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    Link("FoodData Central（USDA）".tr, destination: URL(string: "https://fdc.nal.usda.gov/")!)
                        .font(.labelLarge).tint(Brand.cyan).padding(.vertical, 6)
                    Text("在日本的餐點使用「日本食品標準成分表（八訂）増補2023年」（文部科学省）を加工して作成（公共データ利用規約 第1.0版，相容 CC BY 4.0）。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    Link("日本食品標準成分表（文部科学省）".tr, destination: URL(string: "https://www.mext.go.jp/a_menu/syokuhinseibun/")!)
                        .font(.labelLarge).tint(Brand.cyan).padding(.vertical, 6)
                    Text("在韓國的餐點使用 식품의약품안전처「식품영양성분 데이터베이스」（전국통합식품영양성분정보 음식 표준데이터，公共資料入口網站），只取一般菜色。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    Link("식품영양성분 데이터베이스（공공데이터포털）".tr, destination: URL(string: "https://www.data.go.kr/data/15100070/standard.do")!)
                        .font(.labelLarge).tint(Brand.cyan).padding(.top, 6)
                }

                }

                Text("大D {0}".trf(version)).font(.bodySmall).foregroundStyle(Brand.textFaint)
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
        case .notInstalled: return "手錶 App 未安裝".tr
        case .notPaired: return "沒有配對手錶".tr
        case .unsupported: return "未連線".tr
        }
    }

    private var watchDetail: String {
        switch link.watchState {
        case .reachable, .connected: return "在手錶按下錄音即可開始".tr
        case .notInstalled: return "請在 iPhone 的「Watch」App 安裝大D".tr
        default: return "請確認手錶已在「Watch」App 配對".tr
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

/// 排布選項（快錄 / 總覽）：選中的用重點色底。
private struct LayoutChip: View {
    let mode: LayoutMode
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: mode == .overview ? "square.grid.2x2" : "mic").font(.system(size: 15, weight: .semibold))
                Text(mode.label.tr).font(.labelLarge)
            }
            .foregroundStyle(selected ? Color.white : Brand.textMuted)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Brand.surfaceHigh)))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(selected ? Color.clear : Brand.outline, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
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
                Text(mode.label.tr).font(.labelLarge).foregroundStyle(selected ? Color.white : Brand.textMuted)
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
        let p = mode.palette
        p.bg.overlay(Circle().fill(p.cyan).frame(width: 10, height: 10))
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
            Text("用大D的主機 跑 AI：會分說話者、免費、沒有額度，錄音不出你的設備。主機要開著，手機要連得到（同一個 Wi-Fi，或出門用固定 IP）。".tr)
                .font(.bodySmall).foregroundStyle(Brand.textMuted)
            if hasToken {
                // ---------- 已配對 ----------
                HStack(spacing: 10) {
                    Image(systemName: "checkmark.seal.fill").foregroundStyle(Brand.success)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("已連上大D的主機".tr).font(.titleSmall).foregroundStyle(Brand.text)
                        Text(LocalAI.url).font(.labelMedium).foregroundStyle(Brand.textFaint).lineLimit(1)
                    }
                    Spacer()
                    Button("取消配對".tr) { confirmUnpair = true }.font(.labelLarge).foregroundStyle(Brand.error)
                }
                .padding(.top, 10)
                GradientButton(text: testing ? "測試中…".tr : "測試連線".tr, systemImage: "antenna.radiowaves.left.and.right",
                               enabled: !testing) { test() }
                    .padding(.top, 10)
                if let result {
                    Label(result.text, systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.bodySmall).foregroundStyle(result.ok ? Brand.success : Brand.warning).padding(.top, 8)
                }
                if !admin {
                    Text("這台是一般使用者：換模型、配對密碼、管理別台裝置要用管理者的裝置。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                }
                // 配對密碼：其他裝置配對用（只有管理者）
                if admin {
                DividerLine().padding(.vertical, 12)
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text("配對密碼".tr).font(.titleSmall).foregroundStyle(Brand.text)
                        Text(passwordSet == true ? "已設定：新裝置輸入這個密碼就能連上".tr : "還沒設定：設了之後新裝置輸入密碼就能連上".tr)
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                    Spacer()
                    Button(passwordSet == true ? "更換".tr : "設定".tr) { editingPassword.toggle() }
                        .font(.labelLarge).foregroundStyle(Brand.cyan)
                }
                if editingPassword {
                    secure("新的配對密碼（至少 8 個字）".tr, text: $newPassword).padding(.top, 8)
                    secure("再輸入一次".tr, text: $newPassword2).padding(.top, 6)
                    GradientButton(text: "儲存密碼".tr, enabled: newPassword.count >= 8 && newPassword == newPassword2) {
                        Task {
                            do {
                                try await LocalAI.setPassword(newPassword)
                                result = (true, "配對密碼已設定".tr)
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
                    Text("已配對的裝置".tr).font(.labelMedium).foregroundStyle(Brand.textMuted)
                    ForEach(devices) { d in
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(d.name + (d.current ? "（這台）".tr : "") + (d.admin == true ? " · 管理者".tr : ""))
                                    .font(.bodyMedium).foregroundStyle(Brand.text)
                                Text("配對 ".tr + Fmt.stamp(Date(timeIntervalSince1970: d.created), "M/d HH:mm")
                                     + (d.last_seen.map { " · 最後使用 ".tr + Fmt.stamp(Date(timeIntervalSince1970: $0), "M/d HH:mm") } ?? "")
                                     + (d.via == "qr" ? " · QR" : " · 密碼".tr))
                                    .font(.labelMedium).foregroundStyle(Brand.textFaint)
                            }
                            Spacer()
                            if !d.current {
                                Button(d.admin == true ? "改一般".tr : "設管理者".tr) {
                                    Task {
                                        do { try await LocalAI.setAdmin(d.id, d.admin != true) } catch {
                                            result = (false, error.localizedDescription)
                                        }
                                        await loadDevices()
                                    }
                                }
                                .font(.labelLarge).foregroundStyle(Brand.cyan)
                                Button("取消".tr) {
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
                field("位址".tr, text: $url, prompt: LocalAI.defaultURL.isEmpty ? "http://Dylan-Mac-mini.local:8765" : LocalAI.defaultURL)
                    .padding(.top, 10)
                HStack(spacing: 8) {
                    secure("配對密碼".tr, text: $password)
                    GhostButton(text: pairing ? "配對中…".tr : "配對".tr, tint: Brand.cyan) {
                        guard !pairing, !password.isEmpty else { return }
                        pairing = true
                        Task {
                            do {
                                try await LocalAI.pair(url: url.isEmpty ? LocalAI.defaultURL : url, password: password)
                                password = ""
                                paired("配對完成".tr)
                            } catch { result = (false, error.localizedDescription) }
                            pairing = false
                        }
                    }
                }
                .padding(.top, 8)
                GhostButton(text: "掃描主機上的 QR 碼".tr, systemImage: "qrcode.viewfinder", tint: Brand.cyan) { scanning = true }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10)
                Text("QR 碼：在家裡的 Wi-Fi 用瀏覽器打開 http://Dylan-Mac-mini.local:8765/pair（或在主機上打開 http://localhost:8765/pair），用這台的相機掃。第一次還沒設密碼，就先用 QR 碼配對，再到這裡設定密碼。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 8)
                if let result {
                    Label(result.text, systemImage: result.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .font(.bodySmall).foregroundStyle(result.ok ? Brand.success : Brand.warning).padding(.top, 8)
                }
                if mode == .tech {
                    // 進階：直接貼主機的主存取碼（舊方法）
                    HStack(spacing: 8) {
                        secure("或貼上存取碼（主機 config.json 的 token）".tr, text: $token)
                        GhostButton(text: "儲存".tr, tint: Brand.cyan) {
                            let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
                            guard !t.isEmpty else { return }
                            if !url.isEmpty { url = LocalAI.normalize(url) }
                            Keychain.set(t, for: LocalAI.tokenAccount); token = ""
                            paired("存取碼已儲存".tr)
                        }
                    }
                    .padding(.top, 10)
                }
            }
            if let models, mode == .tech, admin {
                // 實測時切換用：Mac 上有哪些模型就列哪些（Mac 的 config.json 可以再加）
                picker("語音辨識".tr, selection: $asr, options: models.asr_options)
                    .padding(.top, 12)
                picker("報告模型".tr, selection: $llm, options: models.llm_options)
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
                    guard let link = URL(string: text) else { result = (false, "這不是大D的主機的配對 QR 碼".tr); return }
                    Task {
                        do {
                            try await LocalAI.pair(link: link)
                            paired("配對完成".tr)
                        } catch { result = (false, error.localizedDescription) }
                    }
                }
                .ignoresSafeArea()
                Text("對準主機配對頁上的 QR 碼".tr).font(.titleSmall).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Color.black.opacity(0.6)))
                    .padding(.bottom, 40)
            }
        }
        .confirmationDialog("取消和大D的主機的配對？".tr, isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("取消配對".tr, role: .destructive) {
                Task {
                    await LocalAI.unpair()
                    hasToken = false; result = nil; models = nil; devices = []; passwordSet = nil
                    onChange()
                }
            }
        } message: { Text("這台裝置之後要再用密碼或 QR 碼配對一次。".tr) }
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
                result = (true, "連線成功：{0}、{1}".trf(h.asr ?? "", h.llm ?? "") + (h.busy == true ? "（正在處理別的錄音）".tr : ""))
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
        switching = "主機正在載入模型（第一次會先下載，可能要幾分鐘）…".tr
        defer { switching = nil }
        for _ in 0..<600 {
            try? await Task.sleep(for: .seconds(3))
            guard let h = try? await LocalAI.health(url: LocalAI.url, token: LocalAI.token) else { continue }
            if h.state != "loading" {
                if let e = h.error, !e.isEmpty { result = (false, e) } else { result = (true, "已換成：{0}、{1}".trf(h.asr ?? "", h.llm ?? "")) }
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

/// AI 模式三選一（照 Android「AI 引擎」）：雲端（Gemini）／離線（Apple，手機上）／私有主機（大D的主機）。
/// 雲端、離線時主機暫停（報告不交給主機，遠距對話照常）；離線＝隱私模式。
private struct AiModeCard: View {
    let onChange: () -> Void
    @AppStorage(MeetingStore.privacyKey) private var privacy = false
    @AppStorage(LocalAI.pausedKey) private var paused = false

    private var mode: String { LocalAI.configured && !paused ? "host" : (privacy ? "offline" : "cloud") }

    var body: some View {
        GlassCard {
            Picker("AI 模式".tr, selection: Binding(get: { mode }, set: { set($0) })) {
                Text("雲端".tr).tag("cloud")
                Text("離線".tr).tag("offline")
                Text("私有主機".tr).tag("host")
            }
            .pickerStyle(.segmented)
            Text(note).font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
        }
    }

    private var note: String {
        switch mode {
        case "host": return "錄音交給你的大D的主機（分說話者、免費、不出你的設備）；連不到時改用 Gemini 或 Apple。".tr
        case "offline": return "不上傳：用 Apple 在手機上轉文字與整理（不分說話者，需要 iOS 26 以上）。".tr
        default: return "用 Gemini（免費金鑰）整理，分說話者、品質最好。進階可以在下面填大D的主機。".tr
        }
    }

    private func set(_ m: String) {
        switch m {
        case "host":
            paused = false; privacy = false
        case "offline":
            paused = true; privacy = true
        default:
            paused = true; privacy = false
        }
        onChange()
    }
}

/// 隱私模式：錄音只在手機上處理（Apple 語音辨識＋Apple 整理），不上傳 Gemini。
private struct PrivacyCard: View {
    @AppStorage(MeetingStore.privacyKey) private var on = false
    var body: some View {
        GlassCard {
            Toggle(isOn: $on) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("不上傳到雲端".tr).font(.titleMedium).foregroundStyle(Brand.text)
                    Text("開啟後錄音不送 Gemini：有設定大D的主機 就用大D的主機（分說話者），否則用 Apple 在手機上轉文字與整理（不分說話者，需要 iOS 26 以上）。適合醫療、法律、人事等敏感談話。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                }
            }
            .tint(Brand.success)
        }
    }
}

/// 飲食同步到健康（照 Android 設定的「健康同步」卡）：只寫入飲食，不讀健康資料。
struct HealthSyncCard: View {
    @EnvironmentObject private var store: MeetingStore
    @ObservedObject private var health = HealthSync.shared

    private var binding: Binding<Bool> {
        Binding(get: { health.enabled },
                set: { on in Task { await health.setEnabled(on, records: store.meetings) } })
    }

    var body: some View {
        GlassCard {
            Toggle(isOn: binding) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("飲食同步到健康".tr).font(.titleMedium).foregroundStyle(Brand.text)
                    Text(health.lastText ?? "每筆飲食的熱量、蛋白質、碳水、脂肪寫進 Apple 健康。".tr)
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                }
            }
            .tint(Brand.success)
            .disabled(health.busy || !HealthSync.available)

            DividerLine().padding(.vertical, 12)
            VStack(spacing: 8) {
                DestRow(name: "Apple 健康".tr, value: !HealthSync.available ? "這台裝置沒有".tr
                            : !health.enabled ? "未開啟".tr : (health.authorized ? "同步中".tr : "沒有寫入權限".tr),
                        ok: health.enabled && health.authorized)
                DestRow(name: "Google 健康".tr, value: "只在 Android 版（Health Connect）".tr, ok: false)
                DestRow(name: "Samsung Health", value: "只在 Android 版（Health Connect）".tr, ok: false)
            }

            if health.enabled {
                Button {
                    Task { await health.syncAll(store.meetings) }
                } label: {
                    HStack(spacing: 6) {
                        if health.busy { ProgressView().controlSize(.small) }
                        Text(health.busy ? "同步中…".tr : "同步過去的記錄".tr)
                    }
                }
                .font(.labelLarge).foregroundStyle(Brand.cyan)
                .disabled(health.busy)
                .padding(.top, 12)
            }
            if let msg = health.message {
                Text(msg).font(.bodySmall).foregroundStyle(Brand.warning).padding(.top, 8)
            }
            Text("只寫入飲食，不讀取任何健康資料。熱量為 AI 估算，僅供參考。".tr)
                .font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 10)
        }
    }
}

private struct DestRow: View {
    let name: String
    let value: String
    let ok: Bool
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(name).font(.bodyMedium).foregroundStyle(Brand.text)
            Spacer(minLength: 8)
            Text(value).font(.bodySmall).foregroundStyle(ok ? Brand.success : Brand.textMuted)
                .multilineTextAlignment(.trailing)
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
                    Text("已設定（••••{0}）".trf(String(saved.suffix(4)))).font(.titleSmall).foregroundStyle(Brand.text)
                    Spacer()
                    Button("更換".tr) { input = ""; editing = true }.font(.labelLarge).foregroundStyle(Brand.cyan)
                    Button("刪除".tr) {
                        Keychain.set("", for: Keychain.geminiKey)
                        saved = ""
                        onChange()
                    }
                    .font(.labelLarge).foregroundStyle(Brand.error).padding(.leading, 8)
                }
            } else {
                Text("到 Google AI Studio 免費申請金鑰（不用信用卡），複製後貼在這裡。".tr).font(.bodySmall).foregroundStyle(Brand.textMuted)
                Link("aistudio.google.com/apikey", destination: URL(string: "https://aistudio.google.com/apikey")!)
                    .font(.labelLarge).foregroundStyle(Brand.cyan).padding(.top, 4)
                SecureField("", text: $input, prompt: Text("貼上 Gemini API Key".tr).foregroundColor(Brand.textFaint))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .foregroundStyle(Brand.text)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
                    .padding(.top, 10)
                HStack(spacing: 10) {
                    GradientButton(text: "儲存".tr, enabled: !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                        let k = input.trimmingCharacters(in: .whitespacesAndNewlines)
                        Keychain.set(k, for: Keychain.geminiKey)
                        saved = k
                        input = ""
                        editing = false
                        onChange()
                    }
                    if editing { GhostButton(text: "取消".tr) { editing = false; input = "" } }
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
                Text(mode.label.tr).font(.labelLarge)
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
                Text("名稱".tr).font(.labelMedium).foregroundStyle(Brand.textMuted).frame(minWidth: 36, alignment: .leading).fixedSize()
                TextField("", text: $name, prompt: Text(Meeting.isPad ? "例如：Dylan 的 iPad".tr : "例如：Dylan 的 iPhone".tr).foregroundColor(Brand.textFaint))
                    .autocorrectionDisabled()
                    .foregroundStyle(Brand.text)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Brand.outline, lineWidth: 1))
            }
            Text("會顯示在錄音的「來源」，例如「{0} · {1}（{2}）」。iOS 不讓 App 讀系統設定裡的裝置名稱，所以請在這裡填一次。".trf(Meeting.isPad ? "iPad" : "手機", name.isEmpty ? (Meeting.isPad ? "Dylan 的 iPad" : "Dylan 的 iPhone") : name, DeviceInfo.modelShort))
                .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 8)
        }
    }
}
