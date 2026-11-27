import AVFoundation
import SwiftUI
import AppIntents

/// 車用（照 Android `CarMode`、交接/HANDOFF.md「車用」「錄音麥克風」）：
/// - 報告做好時朗讀：關／連著藍牙或 CarPlay 時（預設）／一律；可以連待辦一起唸、朗讀速度。
/// - 唸最新報告（標題＋摘要）、唸今天沒打勾的待辦。
/// - 錄音麥克風：手機（預設）／車上・藍牙（連著藍牙通話麥克風時用它，沒連就用手機，記成 phone:fallback）。
/// - Siri／捷徑（App Intents，不用特別權限）：開始錄音、停止錄音、記錄飲食、唸最新報告、唸今天待辦。
/// CarPlay 的「語音對話」類 App 要 Apple 個別核准 entitlement，這版沒有做。
@MainActor
enum CarMode {
    enum ReadWhen: String, CaseIterable, Identifiable {
        case off, bluetooth, always
        var id: String { rawValue }
        var label: String {
            switch self { case .off: "關".tr; case .bluetooth: "連著藍牙時".tr; case .always: "一律".tr }
        }
    }
    static let readKey = "car_read_when", todosKey = "car_read_todos", micKey = "mic_route"

    static var readWhen: ReadWhen { ReadWhen(rawValue: UserDefaults.standard.string(forKey: readKey) ?? "") ?? .bluetooth }
    static var withTodos: Bool { UserDefaults.standard.object(forKey: todosKey) as? Bool ?? true }

    // MARK: 麥克風

    enum Mic: String, CaseIterable, Identifiable {
        case phone, car
        var id: String { rawValue }
        var label: String { self == .phone ? "手機".tr : "車上・藍牙".tr }
    }
    static var mic: Mic { Mic(rawValue: UserDefaults.standard.string(forKey: micKey) ?? "") ?? .phone }

    /// 錄音開始前設好音訊：選車上而且連著藍牙通話麥克風 → 用它；回傳記錄用的字（phone / phone:fallback / car:名稱:取樣率）。
    static func configureRecordingSession() throws -> String {
        let s = AVAudioSession.sharedInstance()
        guard mic == .car else {
            try s.setCategory(.record, mode: .default)
            return "phone"
        }
        try s.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth])
        try s.setActive(true)
        if let bt = s.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
            try s.setPreferredInput(bt)
            return "car:\(bt.portName):\(Int(s.sampleRate))"
        }
        try s.setCategory(.record, mode: .default)
        return "phone:fallback"
    }

    static func saveMic(_ id: String, _ value: String) {
        try? FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
        try? value.write(to: MeetingStore.dir(id).appendingPathComponent("mic.txt"), atomically: true, encoding: .utf8)
    }

    /// 報告「基本資料」顯示的麥克風（8 kHz 標電話音質）。
    static func micLabel(_ id: String) -> String? {
        guard let v = try? String(contentsOf: MeetingStore.dir(id).appendingPathComponent("mic.txt"), encoding: .utf8) else { return nil }
        if v == "phone" { return "手機".tr }
        if v == "phone:fallback" { return "手機（沒連著藍牙麥克風）".tr }
        let parts = v.split(separator: ":").map(String.init)
        guard parts.first == "car", parts.count >= 2 else { return v }
        let rate = parts.count >= 3 ? Int(parts[2]) ?? 0 : 0
        return parts[1] + (rate > 0 && rate <= 8000 ? "（電話音質）".tr : "")
    }

    // MARK: 朗讀

    static var onBluetoothOrCar: Bool {
        AVAudioSession.sharedInstance().currentRoute.outputs.contains {
            [.bluetoothA2DP, .bluetoothHFP, .bluetoothLE, .carAudio].contains($0.portType)
        }
    }

    private static var lang: String { ReportLang.current == .zh ? "zh-TW" : (ReportLang.current == .ko ? "ko-KR" : "en-US") }

    /// 報告做好了（MeetingStore.finish 呼叫）：依設定唸出來。
    static func reportReady(_ m: Meeting) {
        guard !m.isFood, PhoneRecorder.shared.isRecording == false else { return }
        switch readWhen {
        case .off: return
        case .bluetooth: guard onBluetoothOrCar else { return }
        case .always: break
        }
        read(m, todos: withTodos)
    }

    static func read(_ m: Meeting, todos: Bool) {
        var text = m.title + "。" + (m.summary ?? "")
        if todos, let md = MeetingStore.shared.report(m.id) {
            let items = ActionItems.parse(m.id, md).filter { !ActionItems.done.contains($0.key) }
            if !items.isEmpty { text += "。" + "待辦：".tr + items.map(\.task).joined(separator: "；") }
        }
        Speaker.shared.speak(text, lang: lang, queue: false)
    }

    /// 唸最新報告（標題＋摘要＋待辦）。
    static func readLatest() {
        guard let m = MeetingStore.shared.meetings.first(where: { !$0.isFood && $0.status == .done }) else {
            Speaker.shared.speak("還沒有報告".tr, lang: lang, queue: false)
            return
        }
        read(m, todos: withTodos)
    }

    /// 唸今天沒打勾的待辦。
    static func readTodos() {
        let todayItems = ActionItems.all(MeetingStore.shared)
            .filter { Calendar.current.isDateInToday($0.0.createdAt) }
            .flatMap { $0.1 }.filter { !ActionItems.done.contains($0.key) }
        let text = todayItems.isEmpty ? "今天沒有待辦".tr
            : "今天有 {0} 項待辦：".trf(todayItems.count) + todayItems.map(\.task).joined(separator: "；")
        Speaker.shared.speak(text, lang: lang, queue: false)
    }
}

/// 設定 → 車用。
struct CarSettingsView: View {
    @AppStorage(CarMode.readKey) private var readWhen = CarMode.ReadWhen.bluetooth.rawValue
    @AppStorage(CarMode.todosKey) private var withTodos = true
    @AppStorage(Speaker.rateKey) private var rate = 1.0
    @AppStorage(CarMode.micKey) private var mic = CarMode.Mic.phone.rawValue
    @ObservedObject private var speaker = Speaker.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            GlassCard {
                Text("報告做好時朗讀".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                Picker("報告做好時朗讀".tr, selection: $readWhen) {
                    ForEach(CarMode.ReadWhen.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.segmented)
                Toggle(isOn: $withTodos) { Text("連待辦一起唸".tr).font(.bodyMedium).foregroundStyle(Brand.text) }
                    .tint(Brand.cyan).padding(.top, 8)
                HStack {
                    Text("速度".tr).font(.bodyMedium).foregroundStyle(Brand.text)
                    Slider(value: $rate, in: 0.5...1.5, step: 0.1)
                    Text(String(format: "%.1f×", rate)).font(.labelMedium).foregroundStyle(Brand.textMuted)
                }
                HStack(spacing: 8) {
                    GhostButton(text: "唸最新報告".tr, systemImage: "doc.text", tint: Brand.cyan) { CarMode.readLatest() }
                    GhostButton(text: "唸今天待辦".tr, systemImage: "checklist", tint: Brand.cyan) { CarMode.readTodos() }
                }
                .padding(.top, 8)
                if speaker.speaking {
                    GhostButton(text: "停止朗讀".tr, systemImage: "stop.fill", tint: Brand.warning) { speaker.stop() }.padding(.top, 6)
                }
            }
            GlassCard {
                Text("錄音麥克風".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 8)
                Picker("錄音麥克風".tr, selection: $mic) {
                    ForEach(CarMode.Mic.allCases) { Text($0.label).tag($0.rawValue) }
                }
                .pickerStyle(.segmented)
                Text("車上・藍牙：連著車子或耳機的藍牙通話麥克風時用它錄（通話音質，約 8～16 kHz）；沒連著就用手機。報告的基本資料會寫用了哪個麥克風。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textMuted).padding(.top, 6)
            }
            GlassCard {
                Text("Siri 與捷徑".tr).font(.titleSmall).foregroundStyle(Brand.text).padding(.bottom, 6)
                Text("可以說「嘿 Siri，大D 開始錄音」「大D 停止錄音」「大D 記錄飲食」「大D 唸最新報告」「大D 唸今天待辦」；也能在「捷徑」App 設成連上車子藍牙就開始錄音（自動化 → 藍牙）。".tr)
                    .font(.bodySmall).foregroundStyle(Brand.textMuted)
                Text("CarPlay 畫面要 Apple 個別核准，目前沒有。".tr).font(.bodySmall).foregroundStyle(Brand.textFaint).padding(.top, 6)
            }
        }
    }
}

// MARK: - Siri／捷徑（App Intents）

struct StartRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "開始錄音"
    static var description = IntentDescription("用大D 開始錄音（錄完自動產生報告）。")
    static var openAppWhenRun = true   // 麥克風要 App 在前景才能開
    @MainActor func perform() async throws -> some IntentResult {
        if !PhoneRecorder.shared.isRecording { PhoneRecorder.shared.start() }
        return .result()
    }
}

struct StopRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "停止錄音"
    static var description = IntentDescription("停止大D 的錄音並開始整理報告。")
    @MainActor func perform() async throws -> some IntentResult {
        if PhoneRecorder.shared.isRecording { PhoneRecorder.shared.stop() }
        return .result()
    }
}

struct LogFoodIntent: AppIntent {
    static var title: LocalizedStringResource = "記錄飲食"
    static var description = IntentDescription("開始錄一段飲食口述，AI 估算熱量與營養素。")
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        if !PhoneRecorder.shared.isRecording { PhoneRecorder.shared.start(food: true) }
        return .result()
    }
}

struct ReadLatestReportIntent: AppIntent {
    static var title: LocalizedStringResource = "唸最新報告"
    static var description = IntentDescription("朗讀最新一份報告的標題、摘要與待辦。")
    @MainActor func perform() async throws -> some IntentResult {
        CarMode.readLatest()
        return .result()
    }
}

struct ReadTodosIntent: AppIntent {
    static var title: LocalizedStringResource = "唸今天待辦"
    static var description = IntentDescription("朗讀今天報告裡還沒打勾的待辦事項。")
    @MainActor func perform() async throws -> some IntentResult {
        CarMode.readTodos()
        return .result()
    }
}

struct DylanShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartRecordingIntent(), phrases: ["\(.applicationName) 開始錄音", "Start recording in \(.applicationName)"],
                    shortTitle: "開始錄音", systemImageName: "mic.fill")
        AppShortcut(intent: StopRecordingIntent(), phrases: ["\(.applicationName) 停止錄音", "Stop recording in \(.applicationName)"],
                    shortTitle: "停止錄音", systemImageName: "stop.fill")
        AppShortcut(intent: LogFoodIntent(), phrases: ["\(.applicationName) 記錄飲食", "Log food in \(.applicationName)"],
                    shortTitle: "記錄飲食", systemImageName: "fork.knife")
        AppShortcut(intent: ReadLatestReportIntent(), phrases: ["\(.applicationName) 唸最新報告", "Read the latest report in \(.applicationName)"],
                    shortTitle: "唸最新報告", systemImageName: "doc.text")
        AppShortcut(intent: ReadTodosIntent(), phrases: ["\(.applicationName) 唸今天待辦", "Read today's to-dos in \(.applicationName)"],
                    shortTitle: "唸今天待辦", systemImageName: "checklist")
    }
}
