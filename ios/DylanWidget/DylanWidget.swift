import WidgetKit
import SwiftUI
#if canImport(ActivityKit)
import ActivityKit
#endif

/// 大D 的小工具（照 Android `HomeWidget`）：日期＋最新報告標題（錄音中改紅色計時）、錄音／停止鍵；
/// 今天幾段錄音、大卡／目標、幾項待辦；下排記錄飲食、翻譯、唸待辦。按鈕都打開 App 做（麥克風要 App 在前景）。
/// 另外兩個即時動態：錄音計時、即時翻譯最新一句。
@main
struct DylanWidgets: WidgetBundle {
    var body: some Widget {
        HomeWidget()
        RecordingActivityWidget()
        TranslateActivityWidget()
    }
}

struct SnapshotEntry: TimelineEntry {
    let date: Date
    let s: WidgetSnapshot
}

struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> SnapshotEntry { SnapshotEntry(date: Date(), s: WidgetSnapshot()) }
    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(SnapshotEntry(date: Date(), s: WidgetSnapshot.load()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        var s = WidgetSnapshot.load()
        if !Calendar.current.isDateInToday(s.day) { s.recordingsToday = 0; s.kcal = 0 }   // 過了午夜：今天的數字歸零
        // App 有變化會叫小工具重整；系統另外每 30 分鐘
        completion(Timeline(entries: [SnapshotEntry(date: Date(), s: s)], policy: .after(Date().addingTimeInterval(1800))))
    }
}

struct HomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "DylanHome", provider: SnapshotProvider()) { e in
            HomeWidgetView(s: e.s)
        }
        .configurationDisplayName("大D")
        .description("錄音、今天的數字與待辦")
        .supportedFamilies([.systemMedium, .systemSmall])
    }
}

private let red = Color(red: 0.88, green: 0.11, blue: 0.28)

struct HomeWidgetView: View {
    let s: WidgetSnapshot
    @Environment(\.widgetFamily) private var family

    var body: some View {
        let content = VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(Date(), format: .dateTime.month().day().weekday()).font(.caption).foregroundStyle(.secondary)
                    if let since = s.recordingSince {
                        HStack(spacing: 4) {
                            Circle().fill(red).frame(width: 8, height: 8)
                            Text(since, style: .timer).font(.headline.monospacedDigit()).foregroundStyle(red)
                        }
                    } else {
                        Link(destination: WidgetAction.open.url) {
                            Text(s.latestTitle ?? s.label("還沒有報告")).font(.subheadline.weight(.semibold)).lineLimit(2)
                        }
                    }
                }
                Spacer(minLength: 4)
                Link(destination: WidgetAction.toggle.url) {
                    ZStack {
                        Circle().fill(s.recordingSince != nil ? red : Color.primary)
                        if s.recordingSince != nil {
                            RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 13, height: 13)
                        } else {
                            Image(systemName: "mic.fill").foregroundStyle(Color(.systemBackground))
                        }
                    }
                    .frame(width: 40, height: 40)
                }
            }
            if family != .systemSmall {
                HStack(spacing: 14) {
                    stat("\(s.recordingsToday)", s.label("段錄音"))
                    stat("\(s.kcal)/\(s.kcalGoal)", s.label("大卡"))
                    stat("\(s.todos)", s.label("項待辦"))
                }
                HStack(spacing: 8) {
                    chip("fork.knife", s.label("記錄飲食"), .food)
                    chip("character.bubble", s.label("翻譯"), .translate)
                    chip("speaker.wave.2", s.label("唸待辦"), .todos)
                }
            } else {
                Spacer(minLength: 0)
                Text("\(s.kcal)/\(s.kcalGoal) " + s.label("大卡")).font(.caption).foregroundStyle(.secondary)
                Text("\(s.todos) " + s.label("項待辦")).font(.caption).foregroundStyle(.secondary)
            }
        }
        if #available(iOS 17.0, *) {
            content.containerBackground(.fill.tertiary, for: .widget)
        } else {
            content.padding()
        }
    }

    private func stat(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(v).font(.subheadline.weight(.bold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(l).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func chip(_ icon: String, _ text: String, _ a: WidgetAction) -> some View {
        Link(destination: a.url) {
            Label(text, systemImage: icon).font(.caption2.weight(.medium)).lineLimit(1)
                .padding(.horizontal, 8).padding(.vertical, 5)
                .background(Capsule().fill(Color.primary.opacity(0.08)))
        }
    }
}

// MARK: - 即時動態

struct RecordingActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RecordingAttributes.self) { ctx in
            HStack {
                Circle().fill(red).frame(width: 10, height: 10)
                Text(ctx.attributes.title).font(.headline)
                Spacer()
                Text(ctx.state.since, style: .timer).font(.title3.monospacedDigit()).foregroundStyle(red)
                Link(destination: WidgetAction.toggle.url) {
                    Image(systemName: "stop.circle.fill").font(.title).foregroundStyle(red)
                }
            }
            .padding()
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Image(systemName: ctx.state.food ? "fork.knife" : "mic.fill").foregroundStyle(red) }
                DynamicIslandExpandedRegion(.trailing) { Text(ctx.state.since, style: .timer).monospacedDigit().foregroundStyle(red) }
                DynamicIslandExpandedRegion(.bottom) { Text(ctx.attributes.title).font(.caption) }
            } compactLeading: {
                Image(systemName: "mic.fill").foregroundStyle(red)
            } compactTrailing: {
                Text(ctx.state.since, style: .timer).monospacedDigit().frame(maxWidth: 44).foregroundStyle(red)
            } minimal: {
                Image(systemName: "mic.fill").foregroundStyle(red)
            }
            .widgetURL(WidgetAction.open.url)
        }
    }
}

struct TranslateActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TranslateAttributes.self) { ctx in
            VStack(alignment: .leading, spacing: 4) {
                Text(ctx.attributes.pair).font(.caption2).foregroundStyle(.secondary)
                Text(ctx.state.translated.isEmpty ? "…" : ctx.state.translated).font(.headline).lineLimit(3)
                Text(ctx.state.original).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .widgetURL(WidgetAction.translate.url)
        } dynamicIsland: { ctx in
            DynamicIsland {
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ctx.state.translated).font(.subheadline).lineLimit(3)
                        Text(ctx.state.original).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            } compactLeading: {
                Image(systemName: "character.bubble")
            } compactTrailing: {
                Text(ctx.state.translated).font(.caption2).lineLimit(1).frame(maxWidth: 80)
            } minimal: {
                Image(systemName: "character.bubble")
            }
            .widgetURL(WidgetAction.translate.url)
        }
    }
}
