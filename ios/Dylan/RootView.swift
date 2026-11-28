import SwiftUI

/// 底部分頁（三大主軸各一個，照 Android）：快錄＝今天 / 錄音 /（中間錄音鍵）/ 飲食 / 翻譯（設定在「今天」右上齒輪）；
/// 總覽＝主頁 / 錄音 / 飲食 / 翻譯 / 設定。原本的「檔案」併進「錄音 → 管理」。從詳情返回回到原分頁。
enum Tab: String, CaseIterable {
    case home, records, food, translate, talk, settings

    static func tabs(_ layout: LayoutMode) -> [Tab] { layout == .overview ? allCases : [.home, .records, .food, .translate, .talk] }

    func label(_ layout: LayoutMode) -> String {
        switch self {
        case .home: return layout == .overview ? "主頁".tr : "今天".tr
        case .records: return "錄音".tr
        case .food: return "飲食".tr
        case .translate: return "翻譯".tr
        case .talk: return "對話".tr
        case .settings: return "設定".tr
        }
    }
    var label: String { label(.quick) }
}

/// 排布（設定 → 排布，照用途取名，之後還會加）：快錄＝今天＋中間錄音鍵；總覽＝主頁＋五個分頁（含檔案）。跟配色分開設定。
enum LayoutMode: String, CaseIterable, Identifiable {
    case quick, overview
    static let key = "layout_mode"
    var id: String { rawValue }
    var label: String { self == .overview ? "總覽".tr : "快錄".tr }

    /// 1.1.87 存的 new / classic → quick / overview（App 啟動時換一次）。
    static func migrate() {
        let d = UserDefaults.standard
        switch d.string(forKey: key) {
        case "new": d.set(Self.quick.rawValue, forKey: key)
        case "classic": d.set(Self.overview.rawValue, forKey: key)
        default: break
        }
    }
    var note: String {
        self == .overview ? "主頁有快捷方塊、今日統計與最新報告；底部五個分頁：主頁、錄音、飲食、翻譯、設定。".tr
            : "「今天」只放現在要做的事；底部中間的錄音鍵任何分頁一按就錄；設定在「今天」右上的齒輪。".tr
    }
}

struct RootView: View {
    // SceneStorage：換主題整個重畫時，仍停在原本的分頁（設定）
    @SceneStorage("tab") private var tab: Tab = .home
    @AppStorage(LayoutMode.key) private var layout: LayoutMode = .quick
    @State private var path: [String] = []
    @State private var toast: String?
    @ObservedObject private var photoCapture = FoodPhotoCapture.shared
    @ObservedObject private var translatePage = TranslatePage.shared
    @ObservedObject private var talkPage = TalkPage.shared

    var body: some View {
        GeometryReader { geo in
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                Group {
                    switch tab {
                    case .home:
                        if layout == .overview { OverviewDashboardView(open: open, select: select, toast: $toast) }
                        else { DashboardView(open: open, select: select, toast: $toast) }
                    case .records: RecordsHub(open: open, toast: $toast)
                    case .food: FoodHub(open: open, toast: $toast)
                    case .translate: TranslateHub(open: open, toast: $toast)
                    case .talk: TalkView(asTab: true)
                    case .settings: SettingsView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if layout == .overview { OverviewBottomNav(current: tab, onSelect: select) }
                else { BottomNav(current: tab, onSelect: select) }
            }
            .ignoresSafeArea(.keyboard, edges: .bottom)   // 鍵盤出來時分頁列不要跟著浮上來
            .background(AppBackdrop())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { id in
                if id == TalkPage.route {
                    TalkView().toolbar(.hidden, for: .navigationBar)
                } else {
                    DetailView(id: id) { if !path.isEmpty { path.removeLast() } }
                        .toolbar(.hidden, for: .navigationBar)
                }
            }
        }
        .environment(\.pageWidth, geo.size.width)
        }
        .toast($toast)
        // 來電：整頁（App 開著時任何畫面）
        .overlay { IncomingCallView().animation(.easeOut, value: RemoteTalk.shared.calls) }
        // 遠距對話：推一頁（用全螢幕蓋在翻譯頁上會跟翻譯頁自己的視窗打架，打不開）
        .onChange(of: talkPage.presented) { on in
            guard on else { return }
            talkPage.presented = false
            path = []
            tab = .talk   // 遠距對話是自己的分頁（邀請連結、時間軸、來電都到這裡）
        }
        // 舊的「即時翻譯」入口（主頁、錄音卡）：改到「翻譯」分頁
        .onChange(of: translatePage.presented) { on in
            guard on else { return }
            translatePage.presented = false
            path = []
            UserDefaults.standard.set(0, forKey: "hub_" + Pillar.translate.rawValue)
            tab = .translate
        }
        .sheet(isPresented: $photoCapture.presented) {
            FoodPhotoSheet { id in
                if let id { path = []; tab = .food; toast = "照片已送出，AI 估算中".tr }
                else { tab = .home }   // 拍＋說：錄音卡在「今天」
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: WidgetBridge.showHome)) { _ in path = []; tab = .home }
        .onReceive(NotificationCenter.default.publisher(for: WidgetBridge.openReport)) { note in
            guard let id = note.object as? String else { return }
            tab = .records
            path = [id]   // 小工具點最新報告
        }
        .onReceive(NotificationCenter.default.publisher(for: AudioImport.didImport)) { note in
            path = []
            tab = (note.userInfo?["tab"] as? String) == "settings" ? .settings : .records
            toast = note.object as? String
        }
    }

    private func open(_ id: String) { path.append(id) }
    private func select(_ t: Tab) { tab = t }
}

struct BottomNav: View {
    let current: Tab
    let onSelect: (Tab) -> Void
    @ObservedObject private var rec = PhoneRecorder.shared

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Brand.outline).frame(height: 1)
            HStack(spacing: 0) {
                NavItem(tab: .home, selected: current == .home) { onSelect(.home) }.frame(maxWidth: .infinity)
                NavItem(tab: .records, selected: current == .records) { onSelect(.records) }.frame(maxWidth: .infinity)
                RecordKey(recording: rec.isRecording) {
                    if rec.isRecording { rec.stop() } else { rec.start() }
                    onSelect(.home)   // 錄音卡在「今天」
                }
                .frame(maxWidth: .infinity)
                NavItem(tab: .food, selected: current == .food) { onSelect(.food) }.frame(maxWidth: .infinity)
                NavItem(tab: .translate, selected: current == .translate) { onSelect(.translate) }.frame(maxWidth: .infinity)
                NavItem(tab: .talk, selected: current == .talk) { onSelect(.talk) }.frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 4)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(Brand.surface.opacity(Brand.painting == nil ? 1 : 0.90).ignoresSafeArea(edges: .bottom))
        }
    }
}

/// 中間的錄音鍵：任何分頁一按就錄，錄音中變成停止鍵；長按可以選記錄飲食。
private struct RecordKey: View {
    let recording: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(recording ? Brand.recording : Brand.text)
                if recording {
                    RoundedRectangle(cornerRadius: 4).fill(Color.white).frame(width: 16, height: 16)
                } else {
                    Image(systemName: "mic.fill").font(.system(size: 20, weight: .semibold)).foregroundStyle(Brand.bg)
                }
            }
            .frame(width: 52, height: 52)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(recording ? "停止錄音".tr : "開始錄音".tr)
        .contextMenu {
            if !recording {
                Button { PhoneRecorder.shared.start(food: true) } label: { Label("說：記錄飲食".tr, systemImage: "fork.knife") }
                Button { FoodPhotoCapture.shared.open(withVoice: false) } label: { Label("拍：拍照記錄".tr, systemImage: "camera") }
                Button { FoodPhotoCapture.shared.open(withVoice: true) } label: { Label("拍＋說：拍照再補一句".tr, systemImage: "camera.badge.ellipsis") }
            }
        }
    }
}

private struct NavItem: View {
    let tab: Tab
    let selected: Bool
    let action: () -> Void

    var body: some View {
        // 選中的分頁：圖示與字用重點色（新設計不用漸層膠囊）
        let tint = selected ? Brand.cyan : Brand.textMuted
        Button(action: action) {
            VStack(spacing: 4) {
                icon(tint).frame(height: 26)
                Text(tab.label(.quick)).font(.system(size: 11, weight: selected ? .semibold : .regular))
                    .foregroundStyle(tint)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private func icon(_ tint: Color) -> some View {
        switch tab {
        case .home: Image(systemName: "sun.max").font(.system(size: 17)).foregroundStyle(tint)
        case .records: Image(systemName: "list.bullet.rectangle").font(.system(size: 17)).foregroundStyle(tint)
        case .food: BowlGlyph(color: tint).frame(width: 20, height: 20)
        case .translate: Image(systemName: "character.bubble").font(.system(size: 17)).foregroundStyle(tint)
        case .talk: TalkGlyph(tint: tint, filled: false, selected: selected)
        case .settings: Image(systemName: "gearshape").font(.system(size: 17)).foregroundStyle(tint)
        }
    }
}

/// 「總覽」排布的分頁列：主頁 / 錄音 / 飲食 / 檔案 / 設定，選中的是漸層膠囊。
struct OverviewBottomNav: View {
    let current: Tab
    let onSelect: (Tab) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Brand.outline).frame(height: 1)
            HStack {
                ForEach(Tab.allCases, id: \.self) { t in
                    OverviewNavItem(tab: t, selected: t == current) { onSelect(t) }
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 4)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(Brand.bg.opacity(Brand.painting == nil ? 1 : 0.90).ignoresSafeArea(edges: .bottom))
        }
    }
}

private struct OverviewNavItem: View {
    let tab: Tab
    let selected: Bool
    let action: () -> Void

    var body: some View {
        let tint = selected ? Color.white : Brand.textMuted
        Button(action: action) {
            VStack(spacing: 4) {
                ZStack {
                    // 選中的分頁：圖示後面一個漸層膠囊
                    if selected { Capsule().fill(Brand.accent) }
                    icon(tint)
                }
                .frame(maxWidth: 56).frame(height: 30)
                Text(tab.label(.overview)).font(.labelMedium).foregroundStyle(selected ? Brand.text : Brand.textMuted)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    @ViewBuilder private func icon(_ tint: Color) -> some View {
        switch tab {
        case .home: Image(systemName: "house.fill").font(.system(size: 17)).foregroundStyle(tint)
        case .records: Waveform(color: tint).frame(width: 20, height: 16)
        case .food: BowlGlyph(color: tint).frame(width: 20, height: 20)
        case .translate: Image(systemName: "character.bubble.fill").font(.system(size: 17)).foregroundStyle(tint)
        case .talk: TalkGlyph(tint: tint, filled: true, selected: selected)
        case .settings: Image(systemName: "gearshape.fill").font(.system(size: 17)).foregroundStyle(tint)
        }
    }
}

/// 「對話」分頁的圖示：兩個對話泡泡（不用電話，免得跟「通話」錄音搞混）；有來電、未接、好友邀請時右上紅點。
struct TalkGlyph: View {
    let tint: Color
    var filled = false
    var selected = false
    @ObservedObject private var talk = RemoteTalk.shared
    @ObservedObject private var friends = TalkFriends.shared
    var body: some View {
        Image(systemName: filled ? "bubble.left.and.bubble.right.fill" : "bubble.left.and.bubble.right")
            .font(.system(size: 16)).foregroundStyle(tint)
            .overlay(alignment: .topTrailing) {
                if talk.connected && !selected {   // 通話中、在別的分頁：綠點
                    Circle().fill(Brand.success).frame(width: 8, height: 8).offset(x: 4, y: -2)
                } else if !talk.calls.isEmpty || !friends.requests.isEmpty {
                    Circle().fill(Brand.error).frame(width: 8, height: 8).offset(x: 4, y: -2)
                }
            }
    }
}
