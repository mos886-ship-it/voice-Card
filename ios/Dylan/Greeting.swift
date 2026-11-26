import Foundation

/// 首頁的問候（照 Android `ui/Greeting.kt` 的 greetingFor，文字一字不改）：一句大標（依時段）＋一句有溫度的小字
/// （依時段、星期、今天記了什麼）。同一天同一時段固定同一句（依一年中的第幾天挑），每分鐘更新。
struct Greeting: Equatable {
    let title: String
    let line: String

    /// todayCount = 今天的記錄數（不含飲食）；ateLunch / ateDinner = 今天有沒有記午餐 / 晚餐；todayKcal / goal = 今日熱量與目標。
    static func at(_ now: Date, todayCount: Int, ateLunch: Bool, ateDinner: Bool, todayKcal: Int, goal: Int, usesFood: Bool,
                   calendar: Calendar = .current) -> Greeting {
        let h = calendar.component(.hour, from: now)
        let wd = calendar.component(.weekday, from: now)   // 1 = 星期日 … 7 = 星期六
        let weekend = wd == 1 || wd == 7
        let friday = wd == 6
        let seed = calendar.ordinality(of: .day, in: .year, for: now) ?? 1
        func pick(_ s: String...) -> String { s[seed % s.count] }

        let title: String
        switch h {
        case 5...10: title = weekend ? "週末早安".tr : "早安".tr
        case 11...13: title = "午安".tr
        case 14...16: title = "下午好".tr
        case 17...18: title = "傍晚好".tr
        case 19...22: title = "晚上好".tr
        default: title = "夜深了".tr
        }
        let line: String
        switch h {
        case 5...10:
            if weekend { line = pick("難得週末，慢慢來就好。".tr, "睡飽了嗎？今天放鬆一點。".tr) }
            else if friday { line = pick("星期五了，再撐一下就週末。".tr, "最後一天，加油，有事交給我記。".tr) }
            else { line = pick("今天也慢慢來，要記的交給我。".tr, "先喝杯水，開始美好的一天。".tr, "新的一天，有想法隨時說給我聽。".tr) }
        case 11...13:
            if usesFood && !ateLunch { line = pick("午餐吃了嗎？吃完說一聲，我幫你算熱量。".tr, "記得好好吃午飯，別餓著了。".tr) }
            else { line = pick("吃飽了，休息一下再出發。".tr, "午後容易睏，起來走一走。".tr) }
        case 14...16:
            line = todayCount > 0 ? "今天已經記了 {0} 筆，休息一下、喝口水。".trf(todayCount) : pick("喝口水、伸個懶腰，休息一下。".tr, "下午也辛苦了，慢慢來。".tr)
        case 17...18:
            line = pick("今天辛苦了，準備收工吧。".tr, "外面天色變了，回家路上小心。".tr)
        case 19...22:
            if usesFood && !ateDinner && h <= 20 { line = "晚餐吃了嗎？吃完說一聲就好。".tr }
            else if usesFood && goal > 0 && todayKcal > goal { line = "今天吃得比較多，明天清淡一點就好。".tr }
            else if todayCount > 0 { line = "今天記了 {0} 筆，辛苦了，好好休息。".trf(todayCount) }
            else { line = pick("今天辛苦了，好好放鬆。".tr, "忙完了嗎？給自己一點時間。".tr) }
        default:
            line = pick("早點休息，事情明天再說。".tr, "還沒睡嗎？別太累了。".tr)
        }
        return Greeting(title: title, line: line)
    }

    /// 從記錄算出今天的問候（今天、總覽兩種首頁共用；照 Android 呼叫處的算法）。
    static func now(_ date: Date, meetings: [Meeting]) -> Greeting {
        let cal = Calendar.current
        let today = meetings.filter { cal.isDateInToday($0.createdAt) }
        let food = today.filter(\.isFood)
        let meals = food.compactMap { $0.nutrition?.meal }
        return at(date,
                  todayCount: today.filter { !$0.isFood && $0.status != .recording }.count,
                  ateLunch: meals.contains { $0.contains("午") }, ateDinner: meals.contains { $0.contains("晚") },
                  todayKcal: food.reduce(0) { $0 + ($1.nutrition?.kcal ?? 0) }, goal: FoodView.goal,
                  usesFood: meetings.contains(where: \.isFood))
    }
}
