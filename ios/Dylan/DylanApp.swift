import SwiftUI

@main
struct DylanApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        GeminiJobs.shared.activate()   // 先接上背景 session，系統才會把背景做完的結果交回來
        PhoneLink.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ThemedRoot()
                .environmentObject(MeetingStore.shared)
                .environmentObject(PhoneLink.shared)
                .task { await AiStatus.shared.refresh() } // 開 App 就偵測這支 iPhone 能用哪種 AI
                #if DEBUG
                // 測試用：simctl launch <iPhone> com.example.dylan -processId 20261122_000000（背景分析測試）
                .task {
                    if let id = UserDefaults.standard.string(forKey: "processId") { await MeetingStore.shared.process(id) }
                }
                #endif
        }
    }
}
