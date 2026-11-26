import SwiftUI

/// 底部分頁：今天 / 資料庫 /（中間錄音鍵）/ 飲食 / 設定。檔案管理在資料庫的「管理」。，從詳情返回回到原分頁。
enum Tab: String, CaseIterable {
    case home, records, food, files, settings

    /// 「總覽」排布才有「檔案」分頁（快錄在資料庫的「管理」）。
    static func tabs(_ layout: LayoutMode) -> [Tab] { layout == .overview ? allCases : [.home, .records, .food, .settings] }

    func label(_ layout: LayoutMode) -> String {
        switch self {
        case .home: return layout == .overview ? "主頁".tr : "今天".tr
        case .records: return layout == .overview ? "錄音".tr : "資料庫".tr
        case .food: return "飲食".tr
        case .files: return "檔案".tr
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
        self == .overview ? "主頁有快捷方塊、今日統計與最新報告；底部五個分頁：主頁、錄音、飲食、檔案、設定。".tr
            : "「今天」只放現在要做的事；底部中間的錄音鍵任何分頁一按就錄；檔案管理在資料庫的「管理」。".tr
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

    var body: some View {
        GeometryReader { geo in
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                Group {
                    switch tab {
                    case .home:
                        if layout == .overview { OverviewDashboardView(open: open, select: select, toast: $toast) }
                        else { DashboardView(open: open, select: select, toast: $toast) }
                    case .records: RecordsView(open: open, toast: $toast)
                    case .food: FoodView(toast: $toast)
                    case .files:
                        if layout == .overview { FilesView(open: open, toast: $toast) }
                        else { RecordsView(open: open, toast: $toast) }
                    case .settings: SettingsView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                if layout == .overview { OverviewBottomNav(current: tab, onSelect: select) }
                else { BottomNav(current: tab == .files ? .records : tab, onSelect: select) }
            }
            .ignoresSafeArea(.keyboard, edges: .bottom)   // 鍵盤出來時分頁列不要跟著浮上來
            .background(AppBackdrop())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { id in
                DetailView(id: id) { if !path.isEmpty { path.removeLast() } }
                    .toolbar(.hidden, for: .navigationBar)
            }
        }
        .environment(\.pageWidth, geo.size.width)
        }
        .toast($toast)
        .background(Color.clear.fullScreenCover(isPresented: $translatePage.presented) { LiveTranslateView() })
        .sheet(isPresented: $photoCapture.presented) {
            FoodPhotoSheet { id in
                if let id { path = []; tab = .food; toast = "照片已送出，AI 估算中".tr }
                else { tab = .home }   // 拍＋說：錄音卡在「今天」
            }
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
                NavItem(tab: .settings, selected: current == .settings) { onSelect(.settings) }.frame(maxWidth: .infinity)
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
                Text(tab.label).font(.system(size: 11, weight: selected ? .semibold : .regular))
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
        case .files: Image(systemName: "folder").font(.system(size: 17)).foregroundStyle(tint)
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
        case .files: Image(systemName: "folder.fill").font(.system(size: 17)).foregroundStyle(tint)
        case .settings: Image(systemName: "gearshape.fill").font(.system(size: 17)).foregroundStyle(tint)
        }
    }
}
