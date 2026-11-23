import AVFoundation
import UniformTypeIdentifiers

/// 匯入音檔 / 影片（檔案 App、iCloud 雲碟、語音備忘錄「儲存到檔案」…）：
/// 轉成 m4a、建立一筆 source = "import" 的錄音，接著照常產生報告。
@MainActor
enum AudioImport {
    /// 選檔畫面可以選的類型：聲音和影片（影片只取聲音）。
    /// 從別的 App 送來的檔匯入完成（object = 給使用者看的訊息）。
    static let didImport = Notification.Name("dylan.didImport")

    static let types: [UTType] = [.audio, .movie, .mpeg4Audio, .mp3, .wav, .aiff]

    /// 匯入一個檔，回傳錯誤訊息（成功回傳 nil）。
    static func run(_ url: URL) async -> String? {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }

        // 先複製到暫存（檔案可能在雲端或別的 App 提供的位置，讀到一半就失去權限）
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension.isEmpty ? "m4a" : url.pathExtension)
        do {
            try FileManager.default.copyItem(at: url, to: tmp)
        } catch {
            return "讀不到「\(url.lastPathComponent)」：\(error.localizedDescription)"
        }
        defer { try? FileManager.default.removeItem(at: tmp) }

        let store = MeetingStore.shared
        // 錄音時間：檔案建立時間（沒有就用現在）；同一秒已有錄音就往後推
        var when = (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? Date()
        var id = RecordingID.make(when)
        while store.load(id) != nil {
            when = when.addingTimeInterval(1)
            id = RecordingID.make(when)
        }
        var m = Meeting.new(id: id, source: "import")
        m.title = url.deletingPathExtension().lastPathComponent
        do {
            try FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
            m.durationSec = try await CallInbox.mix([(tmp, 0)], to: MeetingStore.audioURL(id))
            m.status = .received
        } catch {
            try? FileManager.default.removeItem(at: MeetingStore.dir(id))
            return "「\(url.lastPathComponent)」不是可用的音檔（\(error.localizedDescription)）"
        }
        m.updatedAt = Date()
        store.save(m)
        if await MeetingStore.canReport() { Task { await store.process(id) } }
        return nil
    }
}
