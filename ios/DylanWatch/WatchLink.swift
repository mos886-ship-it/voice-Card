import Foundation
import WatchConnectivity

/// 手錶端 WatchConnectivity（對應 Android `PhoneLink`）。
/// 分段用 transferFile（系統排隊、斷線自動補傳）。**手機存好後回傳確認（pathSegmentAck）手錶才刪檔**——
/// 系統的 didFinish 只代表送到對方裝置，不代表手機 App 已經存好（模擬器實測過：didFinish 成功但手機沒收到）。
/// 沒收到確認的分段：重新連上、App 重開、或按「重送」時再送一次（手機收到重複的會覆蓋，安全）。
final class WatchLink: NSObject, ObservableObject, WCSessionDelegate {
    static let shared = WatchLink()

    /// 還在手錶上、手機還沒確認的分段數（傳送中 + 等確認）。
    @Published private(set) var pendingCount = 0
    @Published private(set) var phoneStatus = ""
    /// 手機即時翻譯的最新一句（"" = 沒在翻）
    @Published private(set) var caption = ""
    /// 手機推來的主題："ink"（水墨）或 "dark"（照 Android Brand.apply，存在偏好設定，下次開 App 沿用）。
    @Published private(set) var theme = UserDefaults.standard.string(forKey: "theme") ?? "dark"

    let pendingDir: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pending", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    /// 剛送達、正在等手機確認的檔案（檔名 → 送達時間）。這段時間內不重送，避免重複。
    private var delivered: [String: Date] = [:]
    private let lock = NSLock()
    private static let ackWait: TimeInterval = 60

    func activate() {
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
        refreshCount()
    }

    func send(_ url: URL, _ seg: Segment) {
        refreshCount()
        guard WCSession.default.activationState == .activated else { return } // 啟用後 resendAll 會補
        transport(url, seg)
    }

    /// 真機：transferFile（Apple 標準做法）。
    /// 模擬器：Xcode 27.2 模擬器的手錶↔手機轉送服務會把附件（transferFile、transferUserInfo 都是）變成
    /// 沒有附件的一般訊息、手機直接丟掉（2026-11-22 查 wcd 紀錄確認），只有 sendMessage 會到。
    /// 所以模擬器改成把分段切成 32KB 小塊、一塊一塊 sendMessage，手機收齊再拼回原檔。
    /// 只在模擬器編譯，真機完全不受影響。
    private func transport(_ url: URL, _ seg: Segment) {
        #if targetEnvironment(simulator)
        guard let data = try? Data(contentsOf: url) else { return }
        let count = max(1, (data.count + SimChunk.size - 1) / SimChunk.size)
        lock.lock(); delivered[url.lastPathComponent] = Date(); lock.unlock()   // 送的期間 / 等確認期間不重送
        sendChunk(0, of: count, data: data, seg: seg)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.ackWait + 1) { self.resendAll() }
        #else
        WCSession.default.transferFile(url, metadata: [DylanProtocol.keyPath: seg.path])
        #endif
    }

    /// 把 pending/ 裡手機還沒確認、也沒在排隊的分段重新送出（依錄音、序號排序）。
    /// force = 使用者按「重送」：連剛送達、還在等確認的也一起重送。
    func resendAll(force: Bool = false) {
        guard WCSession.default.activationState == .activated else { return }
        let queued = Set(WCSession.default.outstandingFileTransfers.map { $0.file.fileURL.lastPathComponent })
        let now = Date()
        lock.lock()
        let recent = Set(delivered.filter { now.timeIntervalSince($0.value) < Self.ackWait }.keys)
        lock.unlock()
        for (url, seg) in pendingFiles() {
            let name = url.lastPathComponent
            if queued.contains(name) || (!force && recent.contains(name)) { continue }
            transport(url, seg)
        }
        refreshCount()
    }

    #if targetEnvironment(simulator)
    /// 模擬器：一塊一塊用 sendMessage 送（手機回覆才送下一塊）；失敗就放掉，之後 resendAll 整段重送。
    private func sendChunk(_ i: Int, of count: Int, data: Data, seg: Segment) {
        let chunk = data.subdata(in: (i * SimChunk.size)..<min(data.count, (i + 1) * SimChunk.size))
        let msg: [String: Any] = [DylanProtocol.keyPath: SimChunk.path, SimChunk.keyFile: seg.fileName,
                                  SimChunk.keyIndex: i, SimChunk.keyCount: count, SimChunk.keyData: chunk]
        WCSession.default.sendMessage(msg, replyHandler: { _ in
            if i + 1 < count { self.sendChunk(i + 1, of: count, data: data, seg: seg) }
        }, errorHandler: { error in
            NSLog("[WatchLink] simulator chunk \(i)/\(count) of \(seg.fileName) failed: \(error.localizedDescription)")
            self.lock.lock(); self.delivered.removeValue(forKey: seg.fileName); self.lock.unlock()
            // 手機 App 可能還在被叫醒：10 秒後再試
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { self.resendAll() }
        })
    }
    #endif

    private func pendingFiles() -> [(URL, Segment)] {
        let urls = (try? FileManager.default.contentsOfDirectory(at: pendingDir, includingPropertiesForKeys: nil)) ?? []
        return urls.compactMap { url in Segment(fileName: url.lastPathComponent).map { (url, $0) } }
            .sorted { ($0.1.recordingId, $0.1.seq) < ($1.1.recordingId, $1.1.seq) }
    }

    private func refreshCount() {
        let n = pendingFiles().count
        DispatchQueue.main.async { self.pendingCount = n }
    }

    func clearPhoneStatus() { phoneStatus = "" }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        if state == .activated {
            resendAll()
            handle(session.receivedApplicationContext)
        }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        // 手機重新連上 → 自動補傳（照 Android onCapabilityChanged）
        if session.isReachable { resendAll() }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        handle(applicationContext)
    }

    func session(_ session: WCSession, didFinish fileTransfer: WCSessionFileTransfer, error: Error?) {
        if let error {
            NSLog("[WatchLink] transfer failed: \(error)")
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { self.resendAll() }
        } else {
            // 送到手機了，但要等手機 App 存好回傳確認才刪；等太久就在下次 resendAll 重送
            lock.lock()
            delivered[fileTransfer.file.fileURL.lastPathComponent] = Date()
            lock.unlock()
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.ackWait + 1) { self.resendAll() }
        }
        refreshCount()
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        handle(userInfo)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        handle(message)
    }

    private func handle(_ info: [String: Any]) {
        guard let path = info[DylanProtocol.keyPath] as? String,
              let text = info[DylanProtocol.keyText] as? String else { return }
        switch path {
        case DylanProtocol.pathSegmentAck:
            acknowledge(text)
        case DylanProtocol.pathStatus:
            DispatchQueue.main.async { self.phoneStatus = text }
        case DylanProtocol.pathCaption:
            DispatchQueue.main.async { self.caption = text }
        case DylanProtocol.pathTheme:
            let t = text == "ink" ? "ink" : "dark"
            UserDefaults.standard.set(t, forKey: "theme")
            DispatchQueue.main.async { if self.theme != t { self.theme = t } }
        default:
            break
        }
    }

    /// 手機確認存好了 → 刪掉手錶上的分段。
    private func acknowledge(_ fileName: String) {
        guard Segment(fileName: fileName) != nil else { return } // 只刪 pending 裡格式正確的檔名
        try? FileManager.default.removeItem(at: pendingDir.appendingPathComponent(fileName))
        lock.lock()
        delivered.removeValue(forKey: fileName)
        lock.unlock()
        refreshCount()
    }
}
