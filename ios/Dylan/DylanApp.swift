import SwiftUI

@main
struct DylanApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var phase

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
                .onChange(of: phase) { p in
                    if p == .active {
                        PhoneRecorder.shared.checkAlive()
                        Task { await CallInbox.importFinished() }   // 通話錄音結束後回到大D：收進清單
                    }
                }
                .task { await CallInbox.importFinished() }
                // 從別的 App 分享 / 「打開方式」送來的音檔（語音備忘錄、檔案、LINE…）→ 匯入
                .onOpenURL { url in
                    Task {
                        let err = await AudioImport.run(url)
                        if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }
                        NotificationCenter.default.post(name: AudioImport.didImport, object: err ?? "已匯入「\(url.deletingPathExtension().lastPathComponent)」，正在產生報告")
                    }
                }
                #if DEBUG
                // 測試用：simctl launch <iPhone> tw.dylan.wristai -processId 20261122_000000（背景分析測試）
                .task {
                    // 測試用：-localToken "$(讀 ~/dylan-local/config.json)"（模擬器的剪貼簿不能用，存取碼從這裡帶進來）
                    if let t = UserDefaults.standard.string(forKey: "localToken"), !t.isEmpty {
                        Keychain.set(t, for: LocalAI.tokenAccount)
                    }
                    if let id = UserDefaults.standard.string(forKey: "processId") { await MeetingStore.shared.process(id) }
                    // 測試匯入：simctl launch <iPhone> tw.dylan.wristai -importFile /path/to/audio.m4a
                    if let f = UserDefaults.standard.string(forKey: "importFile") {
                        NSLog("[Import] \(await AudioImport.run(URL(fileURLWithPath: f)) ?? "ok")")
                    }
                }
                #endif
        }
    }
}
