import Foundation
import CoreLocation
import UIKit

/// 錄音 / 拍照的地點（照 Android shared `GeoFix`＋`MeetingStore.saveGeo`，交接/HANDOFF.md「錄音 GPS 戳記」）。
/// 存在記錄資料夾的 geo.json，欄位跟 Android 一樣：lat / lon / accuracy（公尺）/ at（毫秒）/ place / source。
struct GeoFix: Codable, Equatable {
    var lat: Double
    var lon: Double
    var accuracy: Double
    var at: Int64
    var place: String?
    var source: String?

    /// 地圖連結（Apple 地圖）
    var mapURL: URL? { URL(string: String(format: "https://maps.apple.com/?ll=%.6f,%.6f&q=%.6f,%.6f", lat, lon, lat, lon)) }

    /// 「台南市官田區（±13 m）」
    var label: String {
        let acc = accuracy > 0 ? "（±\(Int(accuracy.rounded())) m）" : ""
        return (place ?? String(format: "%.5f, %.5f", lat, lon)) + acc
    }
}

enum GeoStore {
    static func file(_ id: String) -> URL { MeetingStore.dir(id).appendingPathComponent("geo.json") }

    static func load(_ id: String) -> GeoFix? {
        guard let d = try? Data(contentsOf: file(id)) else { return nil }
        return try? JSONDecoder().decode(GeoFix.self, from: d)
    }

    /// 存地點，順便反查地名（要網路；查不到就只有座標）。
    @MainActor static func save(_ id: String, _ loc: CLLocation, source: String = "phone") async {
        var fix = GeoFix(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                         accuracy: max(0, loc.horizontalAccuracy),
                         at: Int64(loc.timestamp.timeIntervalSince1970 * 1000), place: nil, source: source)
        if let p = try? await CLGeocoder().reverseGeocodeLocation(loc, preferredLocale: Locale(identifier: "zh_Hant_TW")).first {
            let name = placeName([p.administrativeArea, p.subAdministrativeArea, p.locality, p.subLocality, p.thoroughfare]
                .compactMap { $0 })
            fix.place = name.isEmpty ? p.name : name
        }
        try? FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
        if let d = try? JSONEncoder().encode(fix) { try? d.write(to: file(id), options: .atomic) }
        MeetingStore.shared.reload()
    }

    /// 地址各層（大 → 小）組成地名：去掉重複（不分大小寫、忽略空白）；中文直接接起來，外文用「, 」隔開。
    /// （照 Android `MeetingStore.placeName`）
    static func placeName(_ parts: [String]) -> String {
        func norm(_ s: String) -> String { s.lowercased().filter { $0.isLetter || $0.isNumber } }
        var kept: [String] = []
        for p in parts.map({ $0.trimmingCharacters(in: .whitespaces) }) where !p.isEmpty {
            let n = norm(p)
            if n.isEmpty || kept.contains(where: { let k = norm($0); return k == n || k.contains(n) || n.contains(k) }) { continue }
            kept.append(p)
        }
        let cjk = kept.allSatisfy { s in s.unicodeScalars.contains { $0.properties.isIdeographic } }
        return kept.joined(separator: cjk ? "" : ", ")
    }

    /// 給 AI 的地點（接在錄音 / 拍照時間後面）：「，錄音地點：…」
    static func promptSuffix(_ id: String, photo: Bool = false) -> String {
        guard let place = load(id)?.place, !place.isEmpty else { return "" }
        return (photo ? "，拍攝地點：" : "，錄音地點：") + place
    }
}

/// 取得目前位置：先要一次新的定位（最多等 timeout），等不到就用 10 分鐘內的最後位置。
/// 定位權限只在第一次按錄音 / 拍照時問一次；拒絕也能錄，只是沒有地點。
@MainActor
final class GeoLocator: NSObject, CLLocationManagerDelegate {
    static let shared = GeoLocator()
    private static let askedKey = "asked_location"

    private let manager = CLLocationManager()
    private var waiting: [CheckedContinuation<CLLocation?, Never>] = []
    private var authWaiting: [CheckedContinuation<Void, Never>] = []
    private var round = 0

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    var hasPermission: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    /// 沒問過就問一次（等使用者回答）；問過了直接回。
    func askOnce() async {
        guard manager.authorizationStatus == .notDetermined,
              !UserDefaults.standard.bool(forKey: Self.askedKey) else { return }
        UserDefaults.standard.set(true, forKey: Self.askedKey)
        await withCheckedContinuation { c in
            authWaiting.append(c)
            manager.requestWhenInUseAuthorization()
        }
    }

    func current(timeout: TimeInterval = 20) async -> CLLocation? {
        guard hasPermission, CLLocationManager.locationServicesEnabled() else { return nil }
        let fresh: CLLocation? = await withCheckedContinuation { c in
            waiting.append(c)
            guard waiting.count == 1 else { return }   // 已經在定位：一起等同一次結果
            round += 1
            let r = round
            manager.requestLocation()
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                if self.round == r { self.finish(nil) }
            }
        }
        if let fresh { return fresh }
        if let last = manager.location, -last.timestamp.timeIntervalSinceNow < 600 { return last }
        return nil
    }

    private func finish(_ loc: CLLocation?) {
        round += 1
        let w = waiting
        waiting = []
        w.forEach { $0.resume(returning: loc) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let loc = locations.last
        Task { @MainActor in self.finish(loc) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.finish(nil) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            guard manager.authorizationStatus != .notDetermined else { return }
            let w = self.authWaiting
            self.authWaiting = []
            w.forEach { $0.resume() }
        }
    }

    // ---------- 拍照記錄：打開拍照時就開始定位（在哪裡拍的），送出後存進記錄 ----------
    private var photoTask: Task<CLLocation?, Never>?

    func startPhotoFix() {
        photoTask?.cancel()
        photoTask = Task { @MainActor in
            await self.askOnce()
            return await self.current()
        }
    }

    func cancelPhotoFix() { photoTask?.cancel(); photoTask = nil }

    /// 送出拍照記錄時拿走定位（沒有就現在開始）。
    func takePhotoFix() -> Task<CLLocation?, Never> {
        let t = photoTask ?? Task { @MainActor in await self.current() }
        photoTask = nil
        return t
    }
}
