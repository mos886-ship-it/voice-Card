import Foundation
import UIKit

/// 背景 AI 分析（使用者要求：App 暗了 / 在背景也必須能處理）。
///
/// 一般的網路請求在 App 進背景幾秒後就會被凍結，所以兩個「久」的步驟交給系統的背景傳輸服務
/// （background URLSession）：
///   1. 上傳錄音（大檔）
///   2. generateContent（Gemini 聽完錄音才回應，可能要幾十秒到幾分鐘；用非串流，回應整包交回）
/// 這兩步就算 App 被系統暫停甚至關掉，系統也會做完，再把 App 叫醒交結果（AppDelegate 的
/// handleEventsForBackgroundURLSession）。中間的短步驟（開始上傳、等檔案 ACTIVE、刪檔、存報告）
/// 用 beginBackgroundTask 爭取的 ~30 秒完成。
///
/// 每個系統任務的 taskDescription 存 Job（JSON），App 重新啟動後也知道是哪筆錄音、哪一步、第幾個模型。
/// 限制：使用者在 App 切換器把大D 滑掉（強制關閉）時，iOS 會取消背景傳輸，下次開 App 會重新分析。
final class GeminiJobs: NSObject, URLSessionDataDelegate {
    static let shared = GeminiJobs()
    static let sessionID = "com.example.dylan.gemini"

    struct Job: Codable {
        enum Stage: String, Codable { case upload, generate }
        var meetingId: String
        var stage: Stage
        var model: Int = 0          // GeminiClient.models 的索引
        var fileName: String?       // files/xxx（上傳完才有）
        var fileURI: String?
        var food: Bool? = nil       // 飲食記錄：用飲食提示詞
    }

    /// AppDelegate 交來的「背景事件處理完要呼叫」的 handler。
    var backgroundCompletion: (() -> Void)?

    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: Self.sessionID)
        c.sessionSendsLaunchEvents = true     // 做完把 App 叫醒
        c.isDiscretionary = false             // 馬上做，不要等充電 / Wi-Fi
        c.timeoutIntervalForRequest = 600     // Gemini 聽長錄音時，回應前可能很久沒資料
        c.timeoutIntervalForResource = 3 * 3600
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    private var buffers: [Int: Data] = [:]
    /// 正在處理系統交回結果的錄音（處理期間 allTasks 裡看不到，要另外記）。
    private var active: Set<String> = []
    private let lock = NSLock()

    /// App 啟動 / 被叫醒時呼叫：重建背景 session，系統才會把還沒交的結果交回來。
    func activate() { _ = session }

    /// 這筆錄音有沒有正在跑的系統任務（App 重開時判斷「分析中」是不是卡住了）。
    func hasTask(for meetingId: String) async -> Bool {
        let busy = lock.withLock { active.contains(meetingId) }
        if busy { return true }
        let tasks = await session.allTasks
        return tasks.contains { Self.job(of: $0)?.meetingId == meetingId }
    }

    // MARK: - 步驟

    /// ① 開始：resumable 上傳的第一個小請求，然後把大檔交給系統上傳。
    func start(meetingId: String, key: String) async throws {
        let audio = MeetingStore.audioURL(meetingId)
        let size = (try? audio.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let uploadURL = try await GeminiClient(apiKey: key).uploadStart(size: size)
        var r = URLRequest(url: uploadURL)
        r.httpMethod = "POST"
        r.setValue("0", forHTTPHeaderField: "X-Goog-Upload-Offset")
        r.setValue("upload, finalize", forHTTPHeaderField: "X-Goog-Upload-Command")
        let task = session.uploadTask(with: r, fromFile: audio)
        task.taskDescription = Self.encode(Job(meetingId: meetingId, stage: .upload, food: meetingId.hasPrefix(Meeting.foodPrefix)))
        task.resume()
        NSLog("[GeminiJobs] \(meetingId) upload handed to system")
    }

    /// ② 上傳好了：等檔案 ACTIVE，再把 generateContent 交給系統（回應可能很久）。
    private func generate(_ job: Job, key: String) async throws {
        guard let name = job.fileName, let uri = job.fileURI, let m = await MeetingStore.shared.load(job.meetingId) else { return }
        let client = GeminiClient(apiKey: key)
        try await client.waitUntilActive(name)
        let model = GeminiClient.models[job.model]
        let prompt = GeminiClient.prompt(durationSec: m.durationSec, recordedAt: Fmt.stamp(m.createdAt, "yyyy/MM/dd HH:mm"))
        // 背景上傳任務只能從檔案上傳 → 請求內容先寫成暫存檔
        let body = FileManager.default.temporaryDirectory.appendingPathComponent("gemini-\(job.meetingId)-\(job.model).json")
        try GeminiClient.generateBody(fileURI: uri, prompt: prompt,
                                      system: job.food == true ? GeminiClient.foodPrompt : GeminiClient.systemPrompt).write(to: body)
        var r = client.request("\(GeminiClient.base)/v1beta/models/\(model):generateContent", "POST", timeout: 600)
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let task = session.uploadTask(with: r, fromFile: body)
        task.taskDescription = Self.encode(job)
        task.resume()
        await MeetingStore.shared.setAiStep(job.meetingId, "AI 聆聽整理中")
        NSLog("[GeminiJobs] \(job.meetingId) generate (\(model)) handed to system")
    }

    // MARK: - URLSession delegate（可能在 App 被叫醒的背景中執行）

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock(); buffers[dataTask.taskIdentifier, default: Data()].append(data); lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock(); let data = buffers.removeValue(forKey: task.taskIdentifier) ?? Data(); lock.unlock()
        guard let initialJob = Self.job(of: task) else { return }
        lock.lock(); active.insert(initialJob.meetingId); lock.unlock()
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? 0
        let response = task.response
        try? FileManager.default.removeItem(at: FileManager.default.temporaryDirectory
            .appendingPathComponent("gemini-\(initialJob.meetingId)-\(initialJob.model).json"))

        Self.withBackgroundTime("gemini-\(initialJob.meetingId)") {
            var job = initialJob
            defer { _ = self.lock.withLock { self.active.remove(initialJob.meetingId) } }
            let key = Keychain.get(Keychain.geminiKey) ?? ""
            // 使用者在 App 切換器把大D 滑掉 → iOS 取消了背景傳輸：下次開 App 時直接重新分析
            if let error = error as NSError?,
               error.userInfo[NSURLErrorBackgroundTaskCancelledReasonKey] as? Int == NSURLErrorCancelledReasonUserForceQuitApplication {
                NSLog("[GeminiJobs] \(job.meetingId) cancelled by force quit, restarting")
                await self.cleanup(job, key: key)
                await MeetingStore.shared.restartAI(job.meetingId)
                return
            }
            do {
                if let error {
                    // 斷線 / 逾時：當成太忙，換下一個模型（上傳步驟就直接失敗）
                    throw GeminiClient.Failure(message: "Gemini 連線中斷或逾時（\(error.localizedDescription)），請稍後按「重試」", code: 504)
                }
                guard (200..<300).contains(status), response != nil else {
                    throw GeminiClient.failure(response ?? URLResponse(), data)
                }
                switch job.stage {
                case .upload:
                    guard let f = GeminiClient.parseUploaded(data) else { throw GeminiClient.Failure(message: "Gemini 上傳回應看不懂") }
                    job.stage = .generate
                    job.fileName = f.name
                    job.fileURI = f.uri
                    NSLog("[GeminiJobs] \(job.meetingId) uploaded \(f.name)")
                    try await self.generate(job, key: key)
                case .generate:
                    let (text, truncated) = try GeminiClient.parseGenerated(data)
                    NSLog("[GeminiJobs] \(job.meetingId) \(GeminiClient.models[job.model]) replied \(text.count) chars")
                    await self.cleanup(job, key: key)
                    await MeetingStore.shared.geminiReplied(job.meetingId, text: text,
                                                            model: GeminiClient.models[job.model], truncated: truncated)
                }
            } catch let e as GeminiClient.Failure {
                // generate 步驟：額度用完 / 下架 / 太忙 / 逾時 → 換下一個模型（照 Android）
                if job.stage == .generate, [429, 404, 500, 503, 504].contains(e.code), job.model + 1 < GeminiClient.models.count {
                    NSLog("[GeminiJobs] \(GeminiClient.models[job.model]) unavailable (\(e.code)), trying next model")
                    job.model += 1
                    do { try await self.generate(job, key: key); return } catch {
                        await self.fail(job, key: key, message: error.localizedDescription)
                        return
                    }
                }
                await self.fail(job, key: key, message: e.message)
            } catch {
                await self.fail(job, key: key, message: error.localizedDescription)
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }

    // MARK: - 收尾

    private func fail(_ job: Job, key: String, message: String) async {
        NSLog("[GeminiJobs] \(job.meetingId) failed: \(message)")
        await cleanup(job, key: key)
        await MeetingStore.shared.geminiFailed(job.meetingId, message: message)
    }

    /// 上傳給 Gemini 的錄音用完立刻刪除。
    private func cleanup(_ job: Job, key: String) async {
        guard let name = job.fileName else { return }
        try? await GeminiClient(apiKey: key).delete(name)
    }

    /// 在背景中爭取一點時間把短步驟做完（系統大約給 30 秒）。
    static func withBackgroundTime(_ name: String, _ work: @escaping @Sendable () async -> Void) {
        Task { @MainActor in
            var id = UIBackgroundTaskIdentifier.invalid
            id = UIApplication.shared.beginBackgroundTask(withName: name) {
                UIApplication.shared.endBackgroundTask(id)
                id = .invalid
            }
            await work()
            if id != .invalid { UIApplication.shared.endBackgroundTask(id) }
        }
    }

    private static func encode(_ job: Job) -> String {
        String(data: (try? JSONEncoder().encode(job)) ?? Data(), encoding: .utf8) ?? ""
    }

    private static func job(of task: URLSessionTask) -> Job? {
        guard let s = task.taskDescription, let d = s.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Job.self, from: d)
    }
}

/// 系統在背景把傳輸做完後，用這裡把 App 叫醒並交結果。
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == GeminiJobs.sessionID else { completionHandler(); return }
        GeminiJobs.shared.backgroundCompletion = completionHandler
        GeminiJobs.shared.activate()
    }
}
