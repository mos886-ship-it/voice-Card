import Foundation

/// 從連結匯入（照 Android `LinkImport.kt`）：直接的音檔／影片連結手機自己下載（最多 300 MB）；
/// 其他網頁（YouTube 等）請大D的主機抓（POST /v1/fetch_url，yt-dlp 只抓音訊）。請只匯入你有權使用的內容。
enum LinkImport {
    private static let maxBytes: Int64 = 300 * 1024 * 1024
    private static let mediaExt: Set<String> = ["mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3gp", "mp4", "m4v", "mov", "webm", "mkv"]

    /// 分享來的文字常夾標題：取出第一個 http(s) 連結。
    static func normalize(_ raw: String) -> String? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let link = t.range(of: "https?://\\S+", options: .regularExpression).map { String(t[$0]) } ?? t
        guard link.hasPrefix("http://") || link.hasPrefix("https://") else { return nil }
        return link.trimmingCharacters(in: CharacterSet(charactersIn: ".,）)"))
    }

    static func isDirectMedia(_ url: String) -> Bool {
        guard let u = URL(string: url) else { return false }
        return mediaExt.contains(u.pathExtension.lowercased())
    }

    /// 檔名當錄音標題（AudioImport 用檔名）：去掉不能用的字。
    private static func fileName(_ title: String?, ext: String) -> String {
        let t = (title ?? "").replacingOccurrences(of: "[\\\\/:*?\"<>|\\n\\r]", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
        return (t.isEmpty ? "連結匯入".tr : String(t.prefix(80))) + "." + ext
    }

    /// 匯入；回傳錯誤文字（nil = 成功）。
    @MainActor static func run(_ raw: String, status: @escaping (String) -> Void = { _ in }) async -> String? {
        guard let link = normalize(raw), let url = URL(string: link) else { return "請貼上 http 或 https 開頭的連結".tr }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("link-" + UUID().uuidString.prefix(8), isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        do {
            let file: URL
            if isDirectMedia(link) {
                status("下載中…".tr)
                let (tmp, resp) = try await URLSession.shared.download(from: url)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                guard (200..<300).contains(code) else { return "下載失敗（{0}）".trf(code) }
                let size = (try? tmp.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
                guard size <= maxBytes else { return "檔案太大（超過 300 MB）".tr }
                file = dir.appendingPathComponent(fileName(url.deletingPathExtension().lastPathComponent.removingPercentEncoding, ext: url.pathExtension))
                try FileManager.default.moveItem(at: tmp, to: file)
            } else {
                guard LocalAI.configured else {
                    return "這個連結要請大D的主機抓（設定 → 大D的主機）；直接的音檔連結（.mp3、.m4a…）手機可以自己下載".tr
                }
                status("大D的主機抓取中…（影片長的話要幾分鐘）".tr)
                let (tmp, title) = try await LocalAI.fetchURL(link)
                file = dir.appendingPathComponent(fileName(title, ext: tmp.pathExtension))
                try FileManager.default.moveItem(at: tmp, to: file)
            }
            status("匯入中…".tr)
            return await AudioImport.run(file)
        } catch {
            return error.localizedDescription
        }
    }
}
