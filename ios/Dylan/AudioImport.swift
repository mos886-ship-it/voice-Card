import AVFoundation
import PhotosUI
import SwiftUI
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
            return "讀不到「{0}」：{1}".trf(url.lastPathComponent, error.localizedDescription)
        }
        defer { try? FileManager.default.removeItem(at: tmp) }

        let store = MeetingStore.shared
        // 錄音時間：檔案建立時間（沒有就用現在）；同一秒已有錄音就往後推
        var when = (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? Date()
        var id = RecordingID.make(when, prefix: "imp_")
        while store.load(id) != nil {
            when = when.addingTimeInterval(1)
            id = RecordingID.make(when, prefix: "imp_")
        }
        var m = Meeting.new(id: id, source: "import")
        m.title = url.deletingPathExtension().lastPathComponent
        if let text = readText(tmp) {   // 文字檔：存起來直接寫報告（沒有錄音）
            let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !body.isEmpty else { return "「{0}」是空的".trf(url.lastPathComponent) }
            do {
                try FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
                try body.write(to: MeetingStore.textURL(id), atomically: true, encoding: .utf8)
                try body.write(to: MeetingStore.transcriptURL(id), atomically: true, encoding: .utf8)
            } catch {
                return "「{0}」存檔失敗：{1}".trf(url.lastPathComponent, error.localizedDescription)
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
            return "「{0}」不是可用的音檔（{1}）".trf(url.lastPathComponent, error.localizedDescription)
        }
        m.updatedAt = Date()
        store.save(m)
        if await MeetingStore.canReport() { Task { await store.process(id) } }
        return nil
    }
}

// MARK: - 從相簿選影片（只取聲音）

/// 相簿裡的影片：系統把檔案交給我們（複製到暫存），之後照一般檔案匯入（CallInbox.mix 只取音軌，影像不留）。
struct VideoFile: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            // 每段影片放自己的資料夾，檔名不變（匯入後的標題就是影片的檔名）
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("video-import/" + UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let dst = dir.appendingPathComponent(received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: dst)
            return VideoFile(url: dst)
        }
    }
}

/// 「匯入」按鈕的來源選擇：檔案（錄音、影片、文字）或相簿影片。主頁與錄音頁共用。
/// done(錯誤訊息, 總數)：匯入完成後顯示結果用。
struct ImportSources: ViewModifier {
    @Binding var choosing: Bool
    @Binding var pickingFiles: Bool
    var started: () -> Void = {}
    let done: (_ errors: [String], _ total: Int) -> Void
    @State private var pickingVideos = false
    @State private var items: [PhotosPickerItem] = []

    func body(content: Content) -> some View {
        content
            .confirmationDialog("匯入".tr, isPresented: $choosing, titleVisibility: .visible) {
                Button("從檔案選（錄音、影片、文字）".tr) { pickingFiles = true }
                Button("從相簿選影片".tr) { pickingVideos = true }
                Button("取消".tr, role: .cancel) {}
            } message: {
                Text("影片只取出聲音來產生報告，影像不會存。".tr)
            }
            .photosPicker(isPresented: $pickingVideos, selection: $items, maxSelectionCount: 5,
                          matching: .videos, preferredItemEncoding: .current)
            .onChange(of: items) { picked in
                guard !picked.isEmpty else { return }
                started()
                Task {
                    var errors: [String] = []
                    for item in picked {
                        guard let v = try? await item.loadTransferable(type: VideoFile.self) else {
                            errors.append("讀不到這段影片".tr)
                            continue
                        }
                        if let e = await AudioImport.run(v.url) { errors.append(e) }
                        try? FileManager.default.removeItem(at: v.url.deletingLastPathComponent())
                    }
                    items = []
                    done(errors, picked.count)
                }
            }
    }
}
