import SwiftUI
import WatchKit

@main
struct DylanWatchApp: App {
    init() {
        WatchLink.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            WatchThemedRoot()
        }
    }
}

/// 手錶跟著手機的主題：手機選「水墨」→ 手錶水墨；其他 → 手錶深色（OLED 省電）。主題一換整個重畫。
/// 錄音狀態放在 Recorder（StateObject 在這一層），重畫不會中斷錄音。
private struct WatchThemedRoot: View {
    @ObservedObject private var link = WatchLink.shared
    @StateObject private var recorder = Recorder()

    var body: some View {
        let p: Palette = link.theme == "ink" ? .ink : .dark
        Brand.current = p
        return RecordView(recorder: recorder)
            .id(p.id)
            .onReceive(NotificationCenter.default.publisher(for: WKApplication.didBecomeActiveNotification)) { _ in link.sendBattery() }
            .environment(\.colorScheme, p.isDark ? .dark : .light)
    }
}
