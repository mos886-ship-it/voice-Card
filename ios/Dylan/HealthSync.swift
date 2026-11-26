import Foundation
import HealthKit

/// 飲食同步到 Apple 健康（照 Android `HealthSync.kt`，交接/HANDOFF.md「飲食同步到健康」）。
/// 只「寫入」四種營養（熱量、蛋白質、碳水、脂肪），不讀任何健康資料。
/// 每筆完成的飲食寫成一個 HKCorrelation(.food)：時間 = 錄音時間，食物名稱 = 標題「：」後面那段；
/// 同步識別碼 = 記錄 id、版本 = 現在的毫秒，所以重複同步是更新，不會多一筆。
@MainActor
final class HealthSync: ObservableObject {
    static let shared = HealthSync()

    static let enabledKey = "health_sync_enabled"
    private static let lastKey = "health_sync_last"
    private static let countKey = "health_sync_count"

    @Published private(set) var enabled = UserDefaults.standard.bool(forKey: enabledKey)
    @Published private(set) var lastSync: Date? = UserDefaults.standard.object(forKey: lastKey) as? Date
    @Published private(set) var lastCount = UserDefaults.standard.integer(forKey: countKey)
    @Published private(set) var busy = false
    @Published var message: String?

    nonisolated static var available: Bool { HKHealthStore.isHealthDataAvailable() }
    private let store = HKHealthStore()

    private static let types: [HKQuantityTypeIdentifier] =
        [.dietaryEnergyConsumed, .dietaryProtein, .dietaryCarbohydrates, .dietaryFatTotal]
    /// 纖維另外要（照 Android 1.1.124）；之前只給四種權限的人照常可以同步，只是沒寫纖維
    private static let fiberType = HKQuantityType(.dietaryFiber)
    private static var shareTypes: Set<HKSampleType> { Set(types.map { HKQuantityType($0) } + [fiberType]) }

    /// 有沒有寫入權限（使用者在「健康」App 可隨時關掉；四種都要有）。
    var authorized: Bool {
        Self.types.allSatisfy { store.authorizationStatus(for: HKQuantityType($0)) == .sharingAuthorized }
    }

    /// 「上次同步 M/d HH:mm · N 筆」
    var lastText: String? {
        guard let d = lastSync else { return nil }
        return "上次同步 ".tr + Fmt.stamp(d, "M/d HH:mm") + " · {0} 筆".trf(lastCount)
    }

    /// 打開開關：跳系統授權（只要求寫入），成功就補寫過去的記錄。
    func setEnabled(_ on: Bool, records: [Meeting]) async {
        message = nil
        guard on else {
            enabled = false
            UserDefaults.standard.set(false, forKey: Self.enabledKey)
            return
        }
        guard Self.available else { message = "這台裝置沒有 Apple 健康。".tr; return }
        do {
            try await store.requestAuthorization(toShare: Self.shareTypes, read: [])
        } catch {
            message = "無法取得授權：{0}".trf(error.localizedDescription)
            return
        }
        guard authorized else {
            message = "沒有寫入權限。請到「健康」App →「共享」→「App」→「大D」，把營養項目打開。".tr
            return
        }
        enabled = true
        UserDefaults.standard.set(true, forKey: Self.enabledKey)
        await syncAll(records)
    }

    /// 「同步過去的記錄」：所有已完成的飲食重寫一次（同一筆是更新）。
    func syncAll(_ records: [Meeting]) async {
        guard enabled, !busy else { return }
        busy = true
        defer { busy = false }
        var n = 0
        var failed = 0
        for m in records where m.isFood && m.status == .done {
            switch await write(m) {
            case .some(true): n += 1
            case .some(false): failed += 1
            case .none: break
            }
        }
        record(n)
        message = failed > 0 ? "有 {0} 筆沒寫進去，請確認「健康」App 裡大D的寫入權限。".trf(failed) : nil
    }

    /// 一筆飲食完成或營養改了（MeetingStore.save 呼叫）。
    func recordChanged(_ m: Meeting) {
        guard enabled, m.isFood, m.status == .done else { return }
        Task {
            if await write(m) == true { record(0, touchOnly: true) }
        }
    }

    /// 刪除飲食記錄：連健康裡的那筆一起刪。
    func recordDeleted(_ id: String) {
        guard enabled, id.hasPrefix(Meeting.foodPrefix) else { return }
        Task { await remove(id) }
    }

    private func record(_ n: Int, touchOnly: Bool = false) {
        lastSync = Date()
        UserDefaults.standard.set(lastSync, forKey: Self.lastKey)
        if !touchOnly {
            lastCount = n
            UserDefaults.standard.set(n, forKey: Self.countKey)
        }
    }

    /// true = 寫入成功，false = 失敗，nil = 不用寫（非飲食內容，熱量 0 → 若之前寫過就刪掉）。
    private func write(_ m: Meeting) async -> Bool? {
        guard let n = m.nutrition, n.kcal > 0 else {
            await remove(m.id)
            return nil
        }
        let version = Int(Date().timeIntervalSince1970 * 1000)
        let name = Self.foodName(m.title)
        let meta: [String: Any] = [
            HKMetadataKeySyncIdentifier: m.id,
            HKMetadataKeySyncVersion: version,
            HKMetadataKeyFoodType: name,
        ]
        let date = m.createdAt
        var values: [(HKQuantityTypeIdentifier, HKQuantity)] = [
            (.dietaryEnergyConsumed, HKQuantity(unit: .kilocalorie(), doubleValue: Double(n.kcal))),
            (.dietaryProtein, HKQuantity(unit: .gram(), doubleValue: Double(n.protein))),
            (.dietaryCarbohydrates, HKQuantity(unit: .gram(), doubleValue: Double(n.carbs))),
            (.dietaryFatTotal, HKQuantity(unit: .gram(), doubleValue: Double(n.fat))),
        ]
        if n.fiber > 0, store.authorizationStatus(for: Self.fiberType) == .sharingAuthorized {
            values.append((.dietaryFiber, HKQuantity(unit: .gram(), doubleValue: Double(n.fiber))))
        }
        // 每個營養樣本也帶同一個同步識別碼（各類型分開算唯一），更新時舊的會被換掉
        let samples: Set<HKSample> = Set(values.map { id, q in
            HKQuantitySample(type: HKQuantityType(id), quantity: q, start: date, end: date, metadata: meta)
        })
        let food = HKCorrelation(type: HKCorrelationType(.food), start: date, end: date, objects: samples, metadata: meta)
        do {
            try await store.save(food)
            return true
        } catch {
            return false
        }
    }

    private func remove(_ id: String) async {
        let p = HKQuery.predicateForObjects(withMetadataKey: HKMetadataKeySyncIdentifier, allowedValues: [id])
        for t in Self.types + [.dietaryFiber] {
            _ = try? await store.deleteObjects(of: HKQuantityType(t), predicate: p)
        }
        _ = try? await store.deleteObjects(of: HKCorrelationType(.food), predicate: p)
    }

    /// 「午餐：牛肉麵＋燙青菜」→「牛肉麵＋燙青菜」
    nonisolated static func foodName(_ title: String) -> String {
        for sep in ["：", ":"] {
            if let r = title.range(of: sep) {
                let s = title[r.upperBound...].trimmingCharacters(in: .whitespaces)
                if !s.isEmpty { return s }
            }
        }
        return title
    }
}

private extension HKHealthStore {
    func deleteObjects(of type: HKObjectType, predicate: NSPredicate) async throws -> Int {
        try await withCheckedThrowingContinuation { c in
            deleteObjects(of: type, predicate: predicate) { _, n, err in
                if let err { c.resume(throwing: err) } else { c.resume(returning: n) }
            }
        }
    }
}
