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
final class GeminiJobs: NSObject, URLSessionDataDelegate, URLSessionDownloadDelegate {
    static let shared = GeminiJobs()
    static let sessionID = "com.example.dylan.gemini"
    static let localSessionID = "com.example.dylan.local"

    struct Job: Codable {
        // local = 傳給大D的主機（舊主機，連著等結果）；jobSubmit / jobPoll = 新主機的工作佇列（送出、查結果）
        enum Stage: String, Codable { case upload, generate, local, jobSubmit, jobPoll }
        var meetingId: String
        var stage: Stage
        var model: Int = 0          // GeminiClient.models 的索引
        var fileName: String?       // files/xxx（上傳完才有）
        var fileURI: String?
        var food: Bool? = nil       // 飲食記錄：用飲食提示詞
        var hostJob: String? = nil  // 主機佇列的工作編號
        var lastSeen: Double? = nil // 最後一次連到主機的時間（連不到超過 15 分鐘才放棄）
        var resent: Bool? = nil     // 主機找不到這筆、已經重送過一次
    }

    /// 主機連不到多久就放棄、改用別的引擎（短暫斷線、主機重開機都會等）。
    static let hostGoneSeconds: Double = 15 * 60

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

    /// 傳給自己的 Mac 用的背景 session：總時限 1 小時（Mac 不見了不會卡 3 小時）。
    private lazy var localSession: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: Self.localSessionID)
        c.sessionSendsLaunchEvents = true
        c.isDiscretionary = false
        c.timeoutIntervalForRequest = 1800      // Mac 處理長錄音時，回應前可能很久沒資料
        c.timeoutIntervalForResource = 3600
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    private var buffers: [Int: Data] = [:]
    /// 正在處理系統交回結果的錄音（處理期間 allTasks 裡看不到，要另外記）。
    private var active: Set<String> = []
    private let lock = NSLock()

    /// App 啟動 / 被叫醒時呼叫：重建背景 session，系統才會把還沒交的結果交回來。
    func activate() { _ = session; _ = localSession }

    /// 這筆錄音有沒有正在跑的系統任務（App 重開時判斷「分析中」是不是卡住了）。
    func hasTask(for meetingId: String) async -> Bool {
        let busy = lock.withLock { active.contains(meetingId) }
        if busy { return true }
        let tasks = await session.allTasks + localSession.allTasks
        return tasks.contains { Self.job(of: $0)?.meetingId == meetingId }
    }

    // MARK: - 步驟

    /// ① 開始：resumable 上傳的第一個小請求，然後把大檔交給系統上傳。
    func start(meetingId: String, key: String) async throws {
        let audio = MeetingStore.audioURL(meetingId)
        // 只有照片（拍照記錄飲食）：不用上傳，直接把照片夾在 generateContent 裡
        if !FileManager.default.fileExists(atPath: audio.path) && FoodPhotos.has(meetingId) {
            try await generate(Job(meetingId: meetingId, stage: .generate, food: true), key: key)
            return
        }
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

    /// 自架：錄音直接傳給這台 Mac 的服務，Mac 處理完回傳 Gemini 格式的結果（交給系統，App 暗了也會完成）。
    /// 新主機（health 有 jobs）：送進工作佇列，拿到工作編號後用 pollLocal 查結果；舊主機：/v1/process 連著等。
    func startLocal(meetingId: String, kind: String, recordedAt: String, durationSec: Double, lang: SpeechLang = .zh,
                    dialect: HakkaDialect = .sixian, resent: Bool = false) throws {
        let text = MeetingStore.isText(meetingId)
        let queue = LocalAI.hasJobs
        guard let r = LocalAI.processRequest(kind: kind, recordedAt: recordedAt, durationSec: durationSec, text: text,
                                             lang: lang, dialect: dialect, queue: queue) else {
            throw GeminiClient.Failure(message: "大D的主機 的位址格式不對")
        }
        let task = localSession.uploadTask(with: r, fromFile: text ? MeetingStore.textURL(meetingId) : MeetingStore.audioURL(meetingId))
        task.taskDescription = Self.encode(Job(meetingId: meetingId, stage: queue ? .jobSubmit : .local,
                                               lastSeen: Date().timeIntervalSince1970, resent: resent ? true : nil))
        task.resume()
        NSLog("[GeminiJobs] \(meetingId) handed to local Mac (\(queue ? "queue" : "process"))")
    }

    /// 查主機佇列裡的工作：主機做完或等到逾時才回（App 在背景時等久一點，系統才不用一直叫醒 App）。
    func pollLocal(meetingId: String, hostJob: String, lastSeen: Double = Date().timeIntervalSince1970,
                   resent: Bool = false, after delay: TimeInterval = 0) {
        Task { @MainActor in
            let active = UIApplication.shared.applicationState == .active
            guard let r = LocalAI.jobRequest(hostJob, wait: active ? 20 : 1500) else {
                await MeetingStore.shared.localFailed(meetingId, message: "大D的主機 的位址格式不對")
                return
            }
            let task = self.localSession.downloadTask(with: r)
            if delay > 0 { task.earliestBeginDate = Date().addingTimeInterval(delay) }
            task.taskDescription = Self.encode(Job(meetingId: meetingId, stage: .jobPoll, hostJob: hostJob,
                                                   lastSeen: lastSeen, resent: resent ? true : nil))
            task.resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let data = (try? Data(contentsOf: location)) ?? Data()
        lock.lock(); buffers[downloadTask.taskIdentifier, default: Data()].append(data); lock.unlock()
    }

    /// ② 上傳好了：等檔案 ACTIVE，再把 generateContent 交給系統（回應可能很久）。
    private func generate(_ job: Job, key: String) async throws {
        guard let m = await MeetingStore.shared.load(job.meetingId) else { return }
        let photos = job.food == true ? FoodPhotos.files(job.meetingId).compactMap { try? Data(contentsOf: $0) } : []
        guard job.fileURI != nil || !photos.isEmpty else { return }
        let client = GeminiClient(apiKey: key)
        if let name = job.fileName { try await client.waitUntilActive(name) }
        let model = GeminiClient.models[job.model]
        let photoOnly = job.fileURI == nil
        let recordedAt = Fmt.stamp(m.createdAt, "yyyy/MM/dd HH:mm") + GeoStore.promptSuffix(job.meetingId, photo: photoOnly)
        let photoNote = photos.isEmpty ? "" : "附上 \(photos.count) 張這一餐的照片。"
        let prompt = job.fileURI == nil
            ? "拍照時間：\(recordedAt)。\(photoNote)沒有錄音，請依照片與格式整理，逐字稿留空。"
            : GeminiClient.prompt(durationSec: m.durationSec, recordedAt: recordedAt, lang: m.speechLang, dialect: m.hakkaDialect,
                                  photoNote: photoNote)
        // 背景上傳任務只能從檔案上傳 → 請求內容先寫成暫存檔
        let body = FileManager.default.temporaryDirectory.appendingPathComponent("gemini-\(job.meetingId)-\(job.model).json")
        try GeminiClient.generateBody(fileURI: job.fileURI, prompt: prompt,
                                      system: job.food == true ? GeminiClient.foodPrompt : GeminiClient.systemPrompt,
                                      images: photos).write(to: body)
        var r = client.request("\(GeminiClient.base)/v1beta/models/\(model):generateContent", "POST", timeout: 600)
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let task = session.uploadTask(with: r, fromFile: body)
        task.taskDescription = Self.encode(job)
        task.resume()
        await MeetingStore.shared.setAiStep(job.meetingId, job.fileURI == nil ? "AI 辨識照片中" : "AI 聆聽整理中")
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
            if job.stage == .jobSubmit || job.stage == .jobPoll {
                await self.handleHostJob(job, data: data, status: status, error: error)
                return
            }
            if job.stage == .local {
                // 自架：成功就照 Gemini 的格式解析；失敗交給 MeetingStore 改用 Gemini / Apple
                if error == nil, status == 200, let (text, model) = LocalAI.parse(data) {
                    NSLog("[GeminiJobs] \(job.meetingId) local Mac replied \(text.count) chars")
                    await MeetingStore.shared.geminiReplied(job.meetingId, text: text, model: model, truncated: false)
                    // 舊主機處理完就刪錄音，報告隨回應傳回、主機不留
                    await MeetingStore.shared.setHostCleanup(job.meetingId, LocalAI.cleanupAll)
                } else {
                    let msg = error.map { "連不到大D的主機（\($0.localizedDescription)）" } ?? LocalAI.error(data, status: status)
                    await MeetingStore.shared.localFailed(job.meetingId, message: msg)
                }
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
                case .local, .jobSubmit, .jobPoll:
                    break   // 上面已經處理
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

    /// 主機佇列：送出完成 → 存工作編號、開始查；查到結果 → 存報告、刪主機上的報告；還在排 → 更新步驟、再查。
    @MainActor private func handleHostJob(_ job: Job, data: Data, status: Int, error: Error?) async {
        let id = job.meetingId
        let store = MeetingStore.shared
        guard store.load(id) != nil else {   // 處理中被刪掉了：主機上的也丟掉
            if let j = job.hostJob { await LocalAI.deleteJob(j) }
            return
        }
        let now = Date().timeIntervalSince1970
        // 連不到 / 主機重開中：等一下再查；超過 15 分鐘才放棄（工作編號留著，按重試會接著查）
        if error != nil || status == 0 || status >= 500 {
            if job.stage == .jobSubmit {
                let msg = error.map { "連不到大D的主機（\($0.localizedDescription)）" } ?? LocalAI.error(data, status: status)
                await store.localFailed(id, message: msg)
            } else if let j = job.hostJob, now - (job.lastSeen ?? now) < Self.hostGoneSeconds {
                pollLocal(meetingId: id, hostJob: j, lastSeen: job.lastSeen ?? now, resent: job.resent == true, after: 30)
            } else {
                await store.localFailed(id, message: "連不到大D的主機超過 15 分鐘")
            }
            return
        }
        if job.stage == .jobSubmit {
            guard (200..<300).contains(status), let v = LocalAI.parseJob(data), let j = v.id else {
                await store.localFailed(id, message: LocalAI.error(data, status: status))
                return
            }
            MeetingStore.setHostJob(id, j)
            store.setAiStep(id, (v.ahead ?? 0) > 0 ? LocalAI.queueText(ahead: v.ahead ?? 0, waitSec: v.wait_sec ?? 0)
                                                         : "傳給大D的主機 處理中")
            NSLog("[GeminiJobs] \(id) queued on host as \(j), ahead \(v.ahead ?? 0)")
            pollLocal(meetingId: id, hostJob: j, resent: job.resent == true)
            return
        }
        guard let j = job.hostJob else { return }
        if status == 404 {   // 主機找不到（過期 / 主機資料被清）：重送一次
            MeetingStore.setHostJob(id, nil)
            guard job.resent != true, let m = store.load(id) else {
                await store.localFailed(id, message: "大D的主機找不到這筆工作")
                return
            }
            do {
                try startLocal(meetingId: id, kind: m.isFood ? "food" : "meeting",
                               recordedAt: Fmt.stamp(m.createdAt, "yyyy/MM/dd HH:mm"), durationSec: m.durationSec,
                               lang: m.speechLang, dialect: m.hakkaDialect, resent: true)
            } catch {
                await store.localFailed(id, message: error.localizedDescription)
            }
            return
        }
        guard status == 200, let v = LocalAI.parseJob(data) else {
            MeetingStore.setHostJob(id, nil)
            await store.localFailed(id, message: LocalAI.error(data, status: status))
            return
        }
        switch v.state {
        case "done":
            NSLog("[GeminiJobs] \(id) host job done (\(v.text?.count ?? 0) chars)")
            store.geminiReplied(id, text: v.text ?? "", model: v.model ?? "local-funasr|qwen3-8b", truncated: false)
            let gone = await LocalAI.deleteJob(j)   // 拿到報告就刪主機上的
            MeetingStore.setHostJob(id, nil)
            store.setHostCleanup(id, LocalAI.cleanupText(purged: v.purged == true, reportGone: gone))
        case "error":
            await LocalAI.deleteJob(j)
            MeetingStore.setHostJob(id, nil)
            await store.localFailed(id, message: v.error ?? "大D的主機處理失敗")
        default:   // queued / running
            let ahead = v.ahead ?? 0
            store.setAiStep(id, v.state == "queued" && ahead > 0
                                  ? LocalAI.queueText(ahead: ahead, waitSec: v.wait_sec ?? 0) : "傳給大D的主機 處理中")
            pollLocal(meetingId: id, hostJob: j, lastSeen: now, resent: job.resent == true)
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
        guard identifier == GeminiJobs.sessionID || identifier == GeminiJobs.localSessionID else { completionHandler(); return }
        GeminiJobs.shared.backgroundCompletion = completionHandler
        GeminiJobs.shared.activate()
    }
}
