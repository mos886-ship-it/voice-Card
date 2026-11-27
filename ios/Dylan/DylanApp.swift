import SwiftUI

@main
struct DylanApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var phase

    init() {
        Fmt.locale = I18n.locale       // 日期的星期、月份跟著介面語言
        GeminiJobs.shared.activate()   // 先接上背景 session，系統才會把背景做完的結果交回來
        PhoneLink.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ThemedRoot()
                .background(LiveTranslateHost())   // 即時翻譯的翻譯工作（不跟著換頁消失）
                .environmentObject(MeetingStore.shared)
                .environmentObject(PhoneLink.shared)
                .task { await AiStatus.shared.refresh() } // 開 App 就偵測這支 iPhone 能用哪種 AI
                .onChange(of: phase) { p in
                    if p == .active {
                        PhoneRecorder.shared.checkAlive()
                        WidgetBridge.refresh()   // 小工具的今天數字（過了午夜也會更新）
                        Task { await CallInbox.importFinished() }   // 通話錄音結束後回到大D：收進清單
                    }
                }
                .task { await CallInbox.importFinished() }
                // 從別的 App 分享 / 「打開方式」送來的音檔（語音備忘錄、檔案、LINE…）→ 匯入
                .onOpenURL { url in
                    if WidgetBridge.handle(url) { return }   // 小工具按鈕
                    if url.scheme == "dylan", url.host == "talk" {   // 遠距對話的邀請連結
                        RemoteTalk.shared.pendingInvite = url.absoluteString
                        TalkPage.shared.presented = true
                        return
                    }
                    if url.scheme == "dylan" {   // 相機掃到主機配對頁的 QR 碼
                        Task {
                            var msg = "已和大D的主機配對".tr
                            do { try await LocalAI.pair(link: url) } catch { msg = "配對失敗：{0}".trf(error.localizedDescription) }
                            NotificationCenter.default.post(name: AudioImport.didImport, object: msg, userInfo: ["tab": "settings"])
                        }
                        return
                    }
                    Task {
                        let err = await AudioImport.run(url)
                        if url.path.contains("/Inbox/") { try? FileManager.default.removeItem(at: url) }
                        NotificationCenter.default.post(name: AudioImport.didImport, object: err ?? "已匯入「{0}」，正在產生報告".trf(url.deletingPathExtension().lastPathComponent))
                    }
                }
                #if DEBUG
                // 測試即時翻譯：Documents/debug_caption.json = {"file": "en.m4a", "lang": "en", "target": "zh"}（讀完就刪）
                .task {
                    let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    let cfg = docs.appendingPathComponent("debug_caption.json")
                    guard let data = try? Data(contentsOf: cfg),
                          let d = try? JSONSerialization.jsonObject(with: data) as? [String: String], let name = d["file"] else { return }
                    try? FileManager.default.removeItem(at: cfg)
                    let url = docs.appendingPathComponent(name)
                    let src = SpeechLang(rawValue: d["lang"] ?? "en") ?? .en
                    let tgt = LiveCaptions.Target(rawValue: d["target"] ?? "zh") ?? .zh
                    LangSettings.shared.lang = src
                    TranslatePage.shared.open()
                    LiveCaptions.shared.start(source: src, target: tgt)
                    await LiveCaptions.shared.debugFeed(url)
                }
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
