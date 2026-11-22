import AVFoundation

/// 把麥克風的 PCM 轉成 16kHz 單聲道，寫成 AAC m4a 分段（對應 Android 的 AacWriter + 分段）。
/// 只在錄音的音訊執行緒上呼叫 write；finish 在同一條 queue 上呼叫。
final class SegmentWriter: @unchecked Sendable { // 只在 Recorder 的 serial queue 上使用
    private let dir: URL
    private let recordingId: String
    private let limit: Int64
    private let onClosed: (URL, Segment) -> Void

    private let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                          sampleRate: AudioSpec.sampleRate, channels: 1, interleaved: false)!
    private var converter: AVAudioConverter?
    private var file: AVAudioFile?
    private var partURL: URL?
    private var seq = 0
    private var samples: Int64 = 0
    private var rotatePending = false
    private(set) var totalSamples: Int64 = 0
    /// 最後一次收到麥克風資料的時間（Recorder 的看門狗用來發現收音停了）。
    private(set) var lastWriteAt = Date()

    init(dir: URL, recordingId: String, segmentSeconds: Double, onClosed: @escaping (URL, Segment) -> Void) {
        self.dir = dir
        self.recordingId = recordingId
        self.limit = Int64(segmentSeconds * AudioSpec.sampleRate)
        self.onClosed = onClosed
    }

    func write(_ input: AVAudioPCMBuffer) {
        guard input.frameLength > 0 else { return }
        lastWriteAt = Date()
        if converter == nil || converter?.inputFormat != input.format {
            converter = AVAudioConverter(from: input.format, to: outFormat)
        }
        guard let converter else { return }

        let ratio = outFormat.sampleRate / input.format.sampleRate
        let capacity = AVAudioFrameCount(Double(input.frameLength) * ratio) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return }
        var fed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return input
        }
        if error != nil || out.frameLength == 0 { return }

        // 前一段已滿：等到有下一批資料才關檔，這樣「最後一段」一定有內容。
        if rotatePending { close(last: false) }
        do {
            if file == nil { try open() }
            try file?.write(from: out)
            samples += Int64(out.frameLength)
            totalSamples += Int64(out.frameLength)
            if samples >= limit { rotatePending = true }
        } catch {
            NSLog("[SegmentWriter] write failed: \(error)")
        }
    }

    /// 停止錄音：目前這段標成最後一段。
    func finish() {
        if file != nil { close(last: true) }
    }

    private func open() throws {
        let url = dir.appendingPathComponent("\(recordingId)__\(seq).part.m4a")
        try? FileManager.default.removeItem(at: url)
        var settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: AudioSpec.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: AudioSpec.bitRate,
        ]
        do {
            file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        } catch {
            // 編碼器不接受這個碼率時，讓系統自己選。
            settings.removeValue(forKey: AVEncoderBitRateKey)
            file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        }
        partURL = url
        samples = 0
        rotatePending = false
    }

    private func close(last: Bool) {
        guard let partURL else { return }
        file = nil // AVAudioFile 在釋放時寫好 moov 並關檔（watchOS 10 沒有 close()）
        let seg = Segment(recordingId: recordingId, seq: seq, samples: samples, last: last)
        let final = dir.appendingPathComponent(seg.fileName)
        try? FileManager.default.removeItem(at: final)
        do {
            try FileManager.default.moveItem(at: partURL, to: final)
            onClosed(final, seg)
        } catch {
            NSLog("[SegmentWriter] rename failed: \(error)")
        }
        self.partURL = nil
        seq += 1
        samples = 0
        rotatePending = false
    }
}
