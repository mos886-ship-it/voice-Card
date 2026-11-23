import SwiftUI

/// 報告 / 逐字稿 分段切換（照 Android DetailScreen 下半部 + `ui/ReportViews.kt`）。
/// 報告與逐字稿在換錄音 / 重新產生時才讀檔解析一次（播放中每 0.2 秒更新進度，不能每次都讀檔）。
struct ReportSection: View {
    let meeting: Meeting
    let player: PlayerState
    let onRegenerate: () -> Void
    @State private var tab = 0
    @State private var report: String?
    @State private var lines: [TranscriptView.Line] = []

    var body: some View {
        let names = meeting.speakerNames ?? [:]
        VStack(alignment: .leading, spacing: 14) {
            SegmentTabs(selection: $tab, labels: ["報告", "逐字稿"])
            if tab == 0 && report == nil && !lines.isEmpty && meeting.status != .done || tab == 0 && meeting.transcriptPreview == true {
                Text("報告還在整理中，可以先看逐字稿。").font(.bodySmall).foregroundStyle(Brand.textMuted)
            } else if tab == 0 {
                ReportFacts(meeting: meeting)
                if let report {
                    ReportView(markdown: Speakers.apply(report, names))
                } else if meeting.status == .done {
                    GlassCard {
                        Text("這筆只有逐字稿：這支 iPhone 沒有可用的 Apple 整理模型。到「設定」貼上 Gemini 金鑰後可以按「重新產生報告」。")
                            .font(.bodySmall).foregroundStyle(Brand.textMuted)
                    }
                }
                if meeting.status == .done {
                    GhostButton(text: "重新產生報告", systemImage: "arrow.clockwise", tint: Brand.cyan, action: onRegenerate)
                        .frame(maxWidth: .infinity)
                }
            } else {
                if meeting.transcriptPreview == true {
                    Label("預覽：Apple 在手機上產生的逐字稿，沒有分說話者；Gemini 報告完成後會換成完整版。", systemImage: "bolt.fill")
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)
                }
                TranscriptView(lines: lines, names: names, player: player)
            }
        }
        .task(id: "\(meeting.id)|\(meeting.processEndedAt?.timeIntervalSince1970 ?? 0)|\(meeting.transcriptPreview == true)") {
            report = try? String(contentsOf: MeetingStore.reportURL(meeting.id), encoding: .utf8)
            let text = (try? String(contentsOf: MeetingStore.transcriptURL(meeting.id), encoding: .utf8)) ?? ""
            lines = TranscriptView.parse(text)
        }
    }
}

/// 兩個選項的分段切換（膠囊，選中的用漸層）。
struct SegmentTabs: View {
    @Binding var selection: Int
    let labels: [String]
    var body: some View {
        HStack(spacing: 4) {
            ForEach(labels.indices, id: \.self) { i in
                Button { selection = i } label: {
                    Text(labels[i]).font(.labelLarge)
                        .foregroundStyle(selection == i ? Color.white : Brand.textMuted)
                        .frame(maxWidth: .infinity, minHeight: 36)
                        .background(Capsule().fill(selection == i ? AnyShapeStyle(Brand.accent) : AnyShapeStyle(Color.clear)))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == i ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Capsule().fill(Brand.surface))
        .overlay(Capsule().stroke(Brand.outline, lineWidth: 1))
    }
}

/// 報告最上面的「基本資料」卡（照 Android `ReportFacts`）：時間、長度、來源、AI、耗時。不顯示 GPS 來源。
struct ReportFacts: View {
    let meeting: Meeting
    @AppStorage(UIMode.key) private var mode: UIMode = .business
    var body: some View {
        GlassCard(padding: 14) {
            Text("基本資料").font(.labelSmall).tracking(1.2).foregroundStyle(Brand.textMuted)
            VStack(alignment: .leading, spacing: 6) {
                fact("時間", meeting.recordingRange)
                fact("來源", meeting.sourceLabel)
                if mode == .tech {
                    fact("AI", Meeting.aiModelLabel(meeting.aiModel))
                    if let s = meeting.processStartedAt, let e = meeting.processEndedAt {
                        fact("耗時", "\(Int(e.timeIntervalSince(s).rounded())) 秒")
                    }
                    fact("編號", meeting.id)
                }
            }
            .padding(.top, 8)
        }
    }

    private func fact(_ k: String, _ v: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(k).font(.bodySmall).foregroundStyle(Brand.textFaint).frame(minWidth: 36, alignment: .leading).fixedSize()
            Text(v).font(.bodySmall).foregroundStyle(Brand.text)
        }
    }
}

// MARK: - 報告排版

/// 把 Markdown 報告排成卡片（照 Android ReportViews：章節色條、條列、表格每列一張小卡；只有「無」的章節顯示一行淡色「無」）。
struct ReportView: View {
    let markdown: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(Self.sections(markdown).enumerated()), id: \.offset) { i, sec in
                GlassCard(padding: 14) {
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 2).fill(Self.colors[i % Self.colors.count]).frame(width: 4, height: 18)
                        Text(sec.title).font(.titleMedium).foregroundStyle(Brand.text)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        if sec.isEmpty {
                            Text("無").font(.bodyMedium).foregroundStyle(Brand.textFaint)
                        } else {
                            ForEach(Array(sec.blocks.enumerated()), id: \.offset) { _, b in block(b) }
                        }
                    }
                    .padding(.top, 10)
                }
            }
        }
    }

    @ViewBuilder private func block(_ b: Block) -> some View {
        switch b {
        case let .paragraph(t):
            Text(Self.inline(t)).font(.bodyMedium).foregroundStyle(Brand.text).fixedSize(horizontal: false, vertical: true)
        case let .bullet(t):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(Brand.cyan).frame(width: 5, height: 5).offset(y: -3)
                Text(Self.inline(t)).font(.bodyMedium).foregroundStyle(Brand.text).fixedSize(horizontal: false, vertical: true)
            }
        case let .row(header, cells):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(cells.enumerated()), id: \.offset) { j, c in
                    if j == 0 {
                        Text(Self.inline(c)).font(.titleSmall).foregroundStyle(Brand.text)
                    } else if !c.isEmpty && c != cells[0] {   // 說話者代號換成名字後，「名字」欄會跟第一欄重複
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(j < header.count ? header[j] : "").font(.labelMedium).foregroundStyle(Brand.textFaint)
                            Text(Self.inline(c)).font(.bodySmall).foregroundStyle(Brand.textMuted)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(Brand.surfaceHigh))
        }
    }

    // MARK: 解析

    enum Block { case paragraph(String), bullet(String), row(header: [String], cells: [String]) }
    struct Section {
        var title: String
        var blocks: [Block] = []
        var isEmpty: Bool {
            blocks.allSatisfy {
                switch $0 {
                case let .paragraph(t), let .bullet(t): return Self.emptyWords.contains(t)
                case let .row(_, cells): return cells.allSatisfy { Self.emptyWords.contains($0) || $0.isEmpty }
                }
            }
        }
        static let emptyWords: Set<String> = ["無", "无", "（無）", "-", "—", "未指定"]
    }

    static let colors: [Color] = [Brand.cyan, Brand.violet, Brand.pink, Brand.success, Brand.warning, Brand.blue]

    static func sections(_ md: String) -> [Section] {
        var out: [Section] = []
        var header: [String]?
        for raw in md.components(separatedBy: "\n") {
            let t = raw.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("# ") { continue }                       // 報告標題（上面已經顯示）
            if t.hasPrefix("#") {
                out.append(Section(title: t.trimmingCharacters(in: CharacterSet(charactersIn: "# "))))
                header = nil
                continue
            }
            guard !t.isEmpty else { continue }
            if out.isEmpty { out.append(Section(title: "報告")) }
            if t.hasPrefix("|") {
                let cells = Speakers.tableCells(t)
                if cells.allSatisfy({ c in !c.isEmpty && c.allSatisfy { $0 == "-" || $0 == ":" } }) { continue }
                if header == nil { header = cells; continue }
                out[out.count - 1].blocks.append(.row(header: header ?? [], cells: cells))
                continue
            }
            header = nil
            if t.hasPrefix("- ") || t.hasPrefix("* ") || t.hasPrefix("• ") {
                out[out.count - 1].blocks.append(.bullet(String(t.dropFirst(2))))
            } else if let r = t.range(of: #"^\d+[.、)]\s*"#, options: .regularExpression) {
                out[out.count - 1].blocks.append(.bullet(String(t[r.upperBound...])))
            } else {
                out[out.count - 1].blocks.append(.paragraph(t))
            }
        }
        return out
    }

    /// 行內 Markdown（粗體等）。
    static func inline(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(s)
    }
}

// MARK: - 逐字稿

/// 逐字稿依說話者分色；點一句就跳到那個時間播放，播放中的那句反白（照 Android）。
struct TranscriptView: View {
    let lines: [Line]
    let names: [String: String]
    @ObservedObject var player: PlayerState

    struct Line: Identifiable {
        let id: Int
        let time: Double?
        let stamp: String
        let speaker: String
        let content: String
    }

    var body: some View {
        let speakers = Self.order(lines)
        let current = lines.last { ($0.time ?? .infinity) <= player.time }?.id
        if lines.isEmpty {
            GlassCard { Text("沒有逐字稿").font(.bodyMedium).foregroundStyle(Brand.textFaint) }
        } else {
            LazyVStack(alignment: .leading, spacing: 8) {
                ForEach(lines) { l in
                    let color = Self.colors[(speakers.firstIndex(of: l.speaker) ?? 0) % Self.colors.count]
                    let playing = player.playing && current == l.id
                    Button { if let t = l.time { player.play(at: t) } } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                if !l.speaker.isEmpty {
                                    Text(names[l.speaker] ?? l.speaker).font(.labelLarge).foregroundStyle(color)
                                }
                                Text(l.stamp).font(.labelSmall).foregroundStyle(Brand.textFaint)
                            }
                            Text(Speakers.apply(l.content, names)).font(.bodyMedium).foregroundStyle(Brand.text)
                                .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 14).fill(playing ? color.opacity(0.16) : Brand.surface))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(playing ? color : Brand.outline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    static var colors: [Color] { [Brand.cyan, Brand.violet, Brand.pink, Brand.success, Brand.warning, Brand.blue] }

    /// 「[mm:ss] 說話者A：內容」或「[h:mm:ss] …」；格式不符的整行當內容。
    static func parse(_ text: String) -> [Line] {
        // 「[mm:ss] 說話者A：內容」（Gemini）或「[mm:ss] 內容」（Apple，沒有說話者）
        let re = try! NSRegularExpression(pattern: #"^\[(\d{1,2}(?::\d{2}){1,2})\]\s*(?:(說話者[A-Z?]{1,2}|[^：:\s]{1,8})[：:])?\s*(.*)$"#)
        var out: [Line] = []
        for raw in text.components(separatedBy: "\n") {
            let t = raw.trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty else { continue }
            let ns = t as NSString
            if let m = re.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)) {
                let stamp = ns.substring(with: m.range(at: 1))
                let sp = m.range(at: 2).location == NSNotFound ? "" : ns.substring(with: m.range(at: 2))
                out.append(Line(id: out.count, time: seconds(stamp), stamp: stamp,
                                speaker: sp.trimmingCharacters(in: .whitespaces),
                                content: ns.substring(with: m.range(at: 3))))
            } else {
                out.append(Line(id: out.count, time: nil, stamp: "", speaker: "", content: t))
            }
        }
        return out
    }

    static func seconds(_ stamp: String) -> Double? {
        let p = stamp.split(separator: ":").compactMap { Double($0) }
        switch p.count {
        case 2: return p[0] * 60 + p[1]
        case 3: return p[0] * 3600 + p[1] * 60 + p[2]
        default: return nil
        }
    }

    /// 說話者依第一次出現的順序排（決定顏色）。
    static func order(_ lines: [Line]) -> [String] {
        var seen: [String] = []
        for l in lines where !l.speaker.isEmpty && !seen.contains(l.speaker) { seen.append(l.speaker) }
        return seen
    }
}
