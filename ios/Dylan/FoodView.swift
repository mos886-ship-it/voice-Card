import SwiftUI

/// 「飲食」分頁（照 Android `ui/FoodScreen.kt` 的版面）：「記錄這一餐」大按鈕、今日熱量圓環、歷史。
/// 實際的飲食記錄（Gemini 估算熱量）在里程碑 3。
struct FoodView: View {
    @Binding var toast: String?
    private let goal = 2000

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("飲食").font(.headlineSmall).foregroundStyle(Brand.text)
                    Text("說出吃了什麼，AI 估算熱量與營養素").font(.bodySmall).foregroundStyle(Brand.textMuted)
                }

                GlassCard {
                    GradientButton(text: "記錄這一餐", recordDot: true) { toast = "飲食記錄下一版加上" }
                    HStack(spacing: 20) {
                        ZStack {
                            Circle().stroke(Brand.surfaceHigher, lineWidth: 10)
                            Circle().trim(from: 0, to: 0)
                                .stroke(Brand.accent, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            VStack(spacing: 0) {
                                Text("0").font(.system(size: 26, weight: .bold)).foregroundStyle(Brand.text)
                                Text("/ \(String(goal)) 大卡").font(.labelMedium).foregroundStyle(Brand.textMuted)
                            }
                        }
                        .frame(width: 120, height: 120)
                        VStack(alignment: .leading, spacing: 8) {
                            nutrient("蛋白質", Brand.cyan)
                            nutrient("碳水", Brand.violet)
                            nutrient("脂肪", Brand.pink)
                        }
                    }
                    .padding(.top, 16)
                }

                SectionLabel(text: "今天")
                GlassCard {
                    Text("今天還沒有飲食記錄").font(.bodyMedium).foregroundStyle(Brand.textMuted)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
    }

    private func nutrient(_ name: String, _ color: Color) -> some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(name).font(.bodySmall).foregroundStyle(Brand.textMuted)
            Text("0 g").font(.titleSmall).foregroundStyle(Brand.text)
        }
    }
}
