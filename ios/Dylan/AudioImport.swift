import AVFoundation
import UniformTypeIdentifiers

/// 匯入音檔 / 影片（檔案 App、iCloud 雲碟、語音備忘錄「儲存到檔案」…）：
/// 轉成 m4a、建立一筆 source = "import" 的錄音，接著照常產生報告。
@MainActor
enum AudioImport {
    /// 選檔畫面可以選的類型：聲音和影片（影片只取聲音）。
    /// 從別的 App 送來的檔匯入完成（object = 給使用者看的訊息）。
    static let didImport = Notification.Name("dylan.didImport")

    static let types: [UTType] = [.audio, .movie, .mpeg4Audio, .mp3, .wav, .aiff,
                                  .plainText, .utf8PlainText, .rtf, UTType("net.daringfireball.markdown")].compactMap { $0 }

    /// 文字檔（會議紀錄 / 逐字稿）：讀成字串。UTF-8 之外也試 UTF-16、Big5（台灣舊檔）、GB18030。
    static func readText(_ url: URL) -> String? {
        let type = UTType(filenameExtension: url.pathExtension)
        guard type?.conforms(to: .text) == true || type?.conforms(to: .rtf) == true else { return nil }
        if type?.conforms(to: .rtf) == true {
            return try? NSAttributedString(url: url, options: [.documentType: NSAttributedString.DocumentType.rtf],
                                           documentAttributes: nil).string
        }
        guard let data = try? Data(contentsOf: url) else { return nil }
        let big5 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.big5_HKSCS_1999.rawValue)))
        let gb = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        if let s = String(data: data, encoding: .utf8) { return s }
        // UTF-16 只在有 BOM 時用（任何偶數長度的資料都能「解」成 UTF-16，會變亂碼）
        if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]) { return String(data: data, encoding: .utf16) }
        for enc in [big5, gb] {
            if let s = String(data: data, encoding: enc) { return s }
        }
        return nil
    }

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
        if let text = readText(tmp) {   // 文字檔：存起來直接寫報告（沒有錄音）
            let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !body.isEmpty else { return "「\(url.lastPathComponent)」是空的" }
            do {
                try FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
                try body.write(to: MeetingStore.textURL(id), atomically: true, encoding: .utf8)
                try body.write(to: MeetingStore.transcriptURL(id), atomically: true, encoding: .utf8)
            } catch {
                return "「\(url.lastPathComponent)」存檔失敗：\(error.localizedDescription)"
            }
            m.status = .received
            m.updatedAt = Date()
            store.save(m)
            Task { await store.process(id) }
            return nil
        }
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
