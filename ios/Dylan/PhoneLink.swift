import Foundation
import WatchConnectivity

/// 手機端 WatchConnectivity（對應 Android `AudioReceiverService` + `WatchNotifier`）。
final class PhoneLink: NSObject, ObservableObject, WCSessionDelegate {
    static let shared = PhoneLink()

    enum WatchState { case unsupported, notPaired, notInstalled, connected, reachable }
    @Published private(set) var watchState: WatchState = .unsupported

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// 進度文字推給手錶：連得到用 sendMessage（即時），否則 transferUserInfo（排隊）。
    func sendStatus(id: String, text: String) {
        let s = WCSession.default
        guard s.activationState == .activated, s.isWatchAppInstalled else { return }
        let info: [String: Any] = [DylanProtocol.keyPath: DylanProtocol.pathStatus,
                                   DylanProtocol.keyId: id, DylanProtocol.keyText: text]
        if s.isReachable {
            s.sendMessage(info, replyHandler: nil) { _ in s.transferUserInfo(info) }
        } else {
            s.transferUserInfo(info)
        }
    }

    /// 即時翻譯：最新一句譯文推給手錶（"" = 關掉）。只在連得到時送，不排隊（過時的字幕沒用）。
    /// original：有的話一起送（`原文\u{1F}譯文`），手錶沒在錄音時上下分割顯示（上半原文倒過來給對面看）。
    func sendCaption(_ text: String, original: String? = nil) {
        let s = WCSession.default
        guard s.activationState == .activated, s.isWatchAppInstalled, s.isReachable else { return }
        let payload = original.map { $0.isEmpty || text.isEmpty ? text : $0 + "\u{1F}" + text } ?? text
        s.sendMessage([DylanProtocol.keyPath: DylanProtocol.pathCaption, DylanProtocol.keyText: payload],
                      replyHandler: nil, errorHandler: nil)
    }

    /// 分段存好了 → 告訴手錶可以刪（連得到用 sendMessage，否則 transferUserInfo 排隊）。
    private func sendAck(_ fileName: String) {
        let s = WCSession.default
        let info: [String: Any] = [DylanProtocol.keyPath: DylanProtocol.pathSegmentAck, DylanProtocol.keyText: fileName]
        if s.isReachable {
            s.sendMessage(info, replyHandler: nil) { _ in s.transferUserInfo(info) }
        } else {
            s.transferUserInfo(info)
        }
    }

    /// 主題推給手錶（照 Android WatchNotifier.broadcastTheme）。用 applicationContext：只保留最新一筆，
    /// 手錶沒開也會在下次啟動時收到。
    func broadcastTheme(_ theme: String) {
        let s = WCSession.default
        guard s.activationState == .activated, s.isWatchAppInstalled else { return }
        try? s.updateApplicationContext([DylanProtocol.keyPath: DylanProtocol.pathTheme, DylanProtocol.keyText: theme])
    }

    private func refreshState(_ s: WCSession) {
        let state: WatchState
        if s.activationState != .activated { state = .unsupported }
        else if !s.isPaired { state = .notPaired }
        else if !s.isWatchAppInstalled { state = .notInstalled }
        else { state = s.isReachable ? .reachable : .connected }
        DispatchQueue.main.async { self.watchState = state }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        refreshState(session)
        Task { @MainActor in self.broadcastTheme(ThemeStore.shared.watchTheme) }
    }

    func sessionDidBecomeInactive(_ session: WCSession) { refreshState(session) }

    func sessionDidDeactivate(_ session: WCSession) {
        session.activate() // 換手錶時重新啟用
    }

    func sessionWatchStateDidChange(_ session: WCSession) { refreshState(session) }
    func sessionReachabilityDidChange(_ session: WCSession) { refreshState(session) }

    /// 手錶送來的小訊息（手錶電量）。
    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        if userInfo[DylanProtocol.keyPath] as? String == DylanProtocol.pathBattery, let t = userInfo[DylanProtocol.keyText] as? String {
            WatchBattery.save(t)
            DispatchQueue.main.async { self.objectWillChange.send() }
        }
    }

    func session(_ session: WCSession, didReceive file: WCSessionFile) {
        guard let path = file.metadata?[DylanProtocol.keyPath] as? String,
              let seg = Segment(path: path) else {
            NSLog("[PhoneLink] unknown file: \(file.metadata ?? [:])")
            return
        }
        received(file.fileURL, seg) // 一定要在這個 delegate 裡同步搬走（回傳後系統就刪暫存檔）
    }

    /// 一段錄音檔到了（真機的 transferFile，或模擬器拼好的檔）：存好 → 回確認 → 合併 → AI。
    private func received(_ fileURL: URL, _ seg: Segment) {
        do {
            try MeetingStore.stash(fileURL, seg)
        } catch {
            NSLog("[PhoneLink] stash failed: \(error)")
            return // 不回確認：手錶會保留這段並重送
        }
        sendAck(seg.fileName)
        // 手機可能鎖著、App 在背景被叫醒：爭取時間把分段接好並開始 AI（之後的長步驟交給系統背景傳輸）
        GeminiJobs.withBackgroundTime("segment-\(seg.recordingId)-\(seg.seq)") {
            if let done = await MeetingStore.shared.segmentArrived(seg) {
                let text = done.status == .received
                    ? "手機已收到（{0}）".trf(Fmt.duration(done.durationSec))
                    : "手機合併失敗".tr
                self.sendStatus(id: done.id, text: text)
            }
        }
    }

    #if targetEnvironment(simulator)
    // MARK: 模擬器專用：收小塊、拼回分段檔（見手錶 WatchLink.transport 的說明）

    private let chunkLock = NSLock()
    private var chunkDir: URL { FileManager.default.temporaryDirectory.appendingPathComponent("sim-chunks", isDirectory: true) }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        simChunk(message)
        replyHandler(["ok": true])
    }

    private func simChunk(_ userInfo: [String: Any]) {
        guard userInfo[DylanProtocol.keyPath] as? String == SimChunk.path,
              let name = userInfo[SimChunk.keyFile] as? String, let seg = Segment(fileName: name),
              let i = userInfo[SimChunk.keyIndex] as? Int, let n = userInfo[SimChunk.keyCount] as? Int,
              let data = userInfo[SimChunk.keyData] as? Data else { return }
        chunkLock.lock()
        defer { chunkLock.unlock() }
        let dir = chunkDir.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? data.write(to: dir.appendingPathComponent("\(i).part"))
        let parts = (0..<n).map { dir.appendingPathComponent("\($0).part") }
        guard parts.allSatisfy({ FileManager.default.fileExists(atPath: $0.path) }) else { return }
        var whole = Data()
        for p in parts { whole.append((try? Data(contentsOf: p)) ?? Data()) }
        let file = chunkDir.appendingPathComponent(name + ".whole")   // 不能跟放小塊的資料夾同名
        try? whole.write(to: file)
        try? FileManager.default.removeItem(at: dir)
        NSLog("[PhoneLink] simulator: reassembled \(name) from \(n) chunks (\(whole.count) bytes)")
        received(file, seg)
    }
    #endif
}
