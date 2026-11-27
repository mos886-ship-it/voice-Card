import Foundation
import WidgetKit
#if canImport(ActivityKit)
import ActivityKit
#endif

/// App 這邊的小工具與即時動態（照 Android `HomeWidget`＋懸浮翻譯；iOS 用 Live Activity）：
/// - `changed()`：錄音、報告、飲食、待辦變了 → 1 秒後更新 App Group 的 WidgetSnapshot、叫小工具重整。
/// - 錄音中顯示計時的即時動態；即時翻譯中顯示最新一句的即時動態。
/// - `handle(url)`：小工具按鈕（dylan://action/…）。
@MainActor
enum WidgetBridge {
    private static var pending: DispatchWorkItem?

    static func changed() {
        pending?.cancel()
        let w = DispatchWorkItem { refresh() }
        pending = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: w)
    }

    static func refresh() {
        let store = MeetingStore.shared
        let today = store.meetings.filter { Calendar.current.isDateInToday($0.createdAt) }
        let latest = store.meetings.first { !$0.isFood && $0.status == .done }
        let todos = ActionItems.all(store).flatMap { $0.1 }.filter { !ActionItems.done.contains($0.key) }.count
        var s = WidgetSnapshot()
        s.latestTitle = latest?.title
        s.latestId = latest?.id
        s.recordingsToday = today.filter { !$0.isFood }.count
        s.kcal = today.filter(\.isFood).reduce(0) { $0 + ($1.nutrition?.kcal ?? 0) }
        s.kcalGoal = FoodGoals.kcal
        s.todos = todos
        s.recordingSince = PhoneRecorder.shared.since
        s.recordingFood = PhoneRecorder.shared.isFood
        s.labels = Dictionary(uniqueKeysWithValues: ["還沒有報告", "段錄音", "大卡", "項待辦", "記錄飲食", "翻譯", "唸待辦"].map { ($0, $0.tr) })
        s.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    // MARK: 小工具按鈕

    static func handle(_ url: URL) -> Bool {
        guard url.scheme == "dylan", url.host == "action", let a = WidgetAction(rawValue: url.lastPathComponent) else { return false }
        let rec = PhoneRecorder.shared
        switch a {
        case .toggle:
            if rec.isRecording { rec.stop() } else { rec.start() }
            NotificationCenter.default.post(name: showHome, object: nil)   // 錄音卡在「今天」
        case .food:
            if !rec.isRecording { rec.start(food: true) }
            NotificationCenter.default.post(name: showHome, object: nil)
        case .translate: TranslatePage.shared.open()
        case .todos: CarMode.readTodos()
        case .open:
            if let id = WidgetSnapshot.load().latestId { NotificationCenter.default.post(name: openReport, object: id) }
        }
        return true
    }
    static let openReport = Notification.Name("dylan.openReport")
    static let showHome = Notification.Name("dylan.showHome")

    // MARK: 即時動態

    #if canImport(ActivityKit)
    nonisolated(unsafe) private static var recording: Any?
    nonisolated(unsafe) private static var translating: Any?
    #endif

    static func recordingStarted(since: Date, food: Bool) {
        changed()
        #if canImport(ActivityKit)
        guard #available(iOS 16.1, *), ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let a = RecordingAttributes(title: food ? "大D・記錄飲食".tr : "大D・錄音中".tr)
        recording = try? Activity.request(attributes: a, contentState: .init(since: since, food: food))
        #endif
    }

    static func recordingStopped() {
        changed()
        #if canImport(ActivityKit)
        guard #available(iOS 16.1, *) else { return }
        let a = recording as? Activity<RecordingAttributes>
        recording = nil
        Task { await a?.end(dismissalPolicy: .immediate) }
        #endif
    }

    static func translateStarted(pair: String) {
        #if canImport(ActivityKit)
        guard #available(iOS 16.1, *), ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        translateStopped()
        translating = try? Activity.request(attributes: TranslateAttributes(pair: pair),
                                            contentState: .init(translated: "", original: ""))
        #endif
    }

    static func translated(_ text: String, original: String) {
        #if canImport(ActivityKit)
        guard #available(iOS 16.1, *), let a = translating as? Activity<TranslateAttributes> else { return }
        Task { await a.update(using: .init(translated: text, original: original)) }
        #endif
    }

    static func translateStopped() {
        #if canImport(ActivityKit)
        guard #available(iOS 16.1, *) else { return }
        let a = translating as? Activity<TranslateAttributes>
        translating = nil
        Task { await a?.end(dismissalPolicy: .immediate) }
        #endif
    }
}
