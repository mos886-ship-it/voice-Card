import Foundation

/// 這一餐在哪個國家：當地常見份量（`portionRule`）與大D的主機的 X-Food-Country（照 Android `FoodRegion.kt`）。
///
/// 順序：設定指定的國家 → 這一餐的 GPS（反查的國家；沒有時用座標粗略判斷）→ 手機地區設定。
/// 設定 `food_country`：auto（預設）/ TW / US / JP / KR。
/// 台、美、日、韓有內建資料庫（FoodDB.files）；其他國家用 AI 估算＋當地份量。
enum FoodRegion {
    static let auto = "auto"
    static let key = "food_country"
    static let supported = ["TW", "US", "JP", "KR"]

    static var setting: String {
        get { UserDefaults.standard.string(forKey: key) ?? auto }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    /// 這一餐的國家（ISO 大寫）；meetingId = nil → 現在所在的國家。
    static func of(_ meetingId: String? = nil) -> String {
        if setting != auto { return setting }
        if let id = meetingId, let g = GeoStore.load(id) {
            if let c = g.country, !c.isEmpty { return c.uppercased() }
            if let c = countryAt(g.lat, g.lon) { return c }
        }
        return current
    }

    /// 沒有這一餐的位置時：手機地區設定。
    static var current: String { (Locale.current.region?.identifier ?? "TW").uppercased() }

    /// 座標粗略判斷國家（只認有資料庫的幾個；反查不到時用）。
    static func countryAt(_ lat: Double, _ lon: Double) -> String? {
        if (21.7...26.5).contains(lat) && (118.0...122.2).contains(lon) { return "TW" }
        if (33.0...38.7).contains(lat) && (124.5...130.95).contains(lon) { return "KR" }
        if (24.0...45.6).contains(lat) && (122.9...146.0).contains(lon) { return "JP" }
        if (24.4...49.5).contains(lat) && (-125.0 ... -66.9).contains(lon) { return "US" }
        if (51.0...71.5).contains(lat) && ((-180.0 ... -129.9).contains(lon) || (172.0...180.0).contains(lon)) { return "US" }
        if (18.8...22.4).contains(lat) && (-160.5 ... -154.7).contains(lon) { return "US" }
        return nil
    }

    static func name(_ cc: String) -> String {
        switch cc {
        case "TW": return "台灣".tr
        case "US": return "美國".tr
        case "JP": return "日本".tr
        case "KR": return "韓國".tr
        default: return Locale.current.localizedString(forRegionCode: cc) ?? cc
        }
    }

    /// 提示詞：依當地常見的份量與做法估算（跟 Android、大D的主機一字不差）。
    static func portionRule(_ cc: String) -> String {
        switch cc {
        case "TW": return "依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。"
        case "US": return "這一餐在美國：依美國常見的份量與做法估算（餐廳份量通常較大；漢堡、三明治、沙拉、披薩、咖啡店飲料的 tall/grande/venti、汽水杯型等），單位換算成克（1 cup 約 240 ml、1 oz 約 28 g）。"
        case "JP": return "這一餐在日本：依日本常見的份量與做法估算（定食、丼飯、拉麵、便利商店便當與飯糰、居酒屋小菜等），食物名稱可以用日文原名並附中文。"
        case "KR": return "這一餐在韓國：依韓國常見的份量與做法估算（白飯一碗約 210 克、湯鍋、拌飯、韓式烤肉、小菜（반찬）、便利商店餐點等），食物名稱可以用韓文原名並附中文。"
        default: return "這一餐在「\(name(cc))」：依當地常見的份量與做法估算。"
        }
    }
}
