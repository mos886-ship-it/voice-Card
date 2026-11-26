import AVFoundation

/// 把手錶傳來的分段 m4a 依序接成一個 audio.m4a（對應 Android `SegmentAssembler` + 不重編碼串接）。
/// AAC 開頭的補零由 m4a 的 edit list 處理，AVAsset 讀出來的長度已經扣掉。
enum SegmentAssembler {
    static func assemble(_ segments: [URL], to out: URL) async throws {
        let fm = FileManager.default
        let tmp = out.deletingLastPathComponent().appendingPathComponent("audio.tmp.m4a")
        try? fm.removeItem(at: tmp)

        if segments.count == 1 {
            try fm.copyItem(at: segments[0], to: tmp)
        } else {
            let comp = AVMutableComposition()
            guard let track = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw err("無法建立音軌".tr)
            }
            var cursor = CMTime.zero
            for url in segments {
                let asset = AVURLAsset(url: url)
                guard let src = try await asset.loadTracks(withMediaType: .audio).first else { throw err("分段沒有音訊：{0}".trf(url.lastPathComponent)) }
                let duration = try await asset.load(.duration)
                try track.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: src, at: cursor)
                cursor = cursor + duration
            }
            guard let export = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetAppleM4A) else {
                throw err("無法匯出".tr)
            }
            export.outputURL = tmp
            export.outputFileType = .m4a
            await export.export()
            guard export.status == .completed else { throw export.error ?? err("匯出失敗".tr) }
        }

        try? fm.removeItem(at: out)
        try fm.moveItem(at: tmp, to: out)
    }

    private static func err(_ msg: String) -> NSError {
        NSError(domain: "Dylan.SegmentAssembler", code: 1, userInfo: [NSLocalizedDescriptionKey: msg])
    }
}
