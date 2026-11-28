import PhotosUI
import SwiftUI
#if canImport(Translation)
import Translation
#endif

/// 翻譯情境（照 Android `TranslateScenes.kt`）：一般、照護居家、商務、旅遊。選了就套一組字幕設定，
/// 並在即時翻譯頁顯示常用片語（點一下翻成對方的語言、放大＋念出來）。旅遊另有「拍菜單／選照片」翻譯（Gemini）。
enum TranslateScene {
    static let general = "general", care = "care", business = "business", travel = "travel"
    static let all = [general, care, business, travel]
    static let key = "translate_scene", customKey = "translate_phrases_custom"

    static var mode: String { UserDefaults.standard.string(forKey: key).flatMap { all.contains($0) ? $0 : nil } ?? general }

    struct Preset { let size: Int, original: Bool, speak: Bool, rate: Double }

    /// 一般不改使用者自己的設定。
    static func preset(_ m: String) -> Preset? {
        switch m {
        case care: Preset(size: 3, original: false, speak: true, rate: 0.8)       // 大字、慢慢念
        case business: Preset(size: 1, original: true, speak: false, rate: 1.0)   // 原文對照、安靜
        case travel: Preset(size: 2, original: false, speak: true, rate: 1.0)     // 念給對方聽
        default: nil
        }
    }

    /// 換情境：存起來並套用字幕設定。
    static func setMode(_ m: String) {
        let d = UserDefaults.standard
        d.set(m, forKey: key)
        guard let p = preset(m) else { return }
        d.set(p.size, forKey: LiveCaptions.textSizeKey)
        d.set(p.original, forKey: LiveCaptions.showOriginalKey)
        d.set(p.speak, forKey: LiveCaptions.readAloudKey)
        Speaker.rate = p.rate
    }

    static func label(_ m: String) -> String {
        switch m { case care: "照護居家".tr; case business: "商務".tr; case travel: "旅遊".tr; default: "一般".tr }
    }

    static func note(_ m: String) -> String {
        switch m {
        case care: "大字、慢速朗讀，常用照護片語一點就念（適合外籍看護、長輩）。".tr
        case business: "原文與譯文對照、不朗讀，適合會議與商談。".tr
        case travel: "譯文念給對方聽，常用旅遊片語；可以拍菜單翻譯。".tr
        default: "用你自己的字幕設定。".tr
        }
    }

    static func phrases(_ m: String) -> [String] {
        switch m {
        case care: ["該吃藥了", "要不要喝水？", "哪裡不舒服？", "要去廁所嗎？", "慢慢來，不急", "我扶你起來",
                    "會痛嗎？", "該吃飯了", "要洗澡了", "要量血壓了", "有沒有頭暈？", "晚安，好好休息"].map(\.tr)
        case business: ["很高興認識你", "請再說一次", "可以說慢一點嗎？", "我們再確認一下細節", "我會再寄郵件給你",
                        "報價什麼時候可以提供？", "交期是什麼時候？", "我需要跟團隊討論", "謝謝你的時間"].map(\.tr)
        case travel: ["多少錢？", "廁所在哪裡？", "可以刷卡嗎？", "請給我菜單", "我對花生過敏", "不要辣",
                      "請帶我去這個地址", "可以幫我拍照嗎？", "請問車站怎麼走？", "結帳，謝謝"].map(\.tr)
        default: []
        }
    }

    static var customPhrases: [String] {
        (UserDefaults.standard.string(forKey: customKey) ?? "").components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    static func addCustom(_ text: String) {
        let t = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
        guard !t.isEmpty else { return }
        let list = Array((customPhrases.filter { $0 != t } + [t]).suffix(30))
        UserDefaults.standard.set(list.joined(separator: "\n"), forKey: customKey)
    }

    static func removeCustom(_ text: String) {
        UserDefaults.standard.set(customPhrases.filter { $0 != text }.joined(separator: "\n"), forKey: customKey)
    }

    /// 使用者自己的語言（翻譯代碼）：畫面語言。
    static var myLang: String { switch I18n.lang { case "en": "en"; case "ko": "ko"; default: "zh-Hant" } }

    /// 對方的語言：「說的／翻成」兩邊裡不是我的那一個；都不是我的就翻成「說的」那邊。
    @MainActor static func otherLang(source: String, target: String) -> String {
        let src = TranslateLangs.translationId(source: source)
        return TranslateLangs.same(source: source, target: myLang) ? target : src
    }

    /// 拍菜單／告示翻譯的提示詞。
    static func menuPrompt(_ lang: String) -> String {
        """
        你是旅遊翻譯。使用者拍了一張菜單、告示或標示，請翻成「\(lang)」。
        規則：
        - 菜單：每道菜一行，格式「原文 → 譯名：一句說明（主要食材、口味；辣、含堅果、海鮮、豬肉、酒精等要標出）· 價格」。
        - 告示或標示：逐句翻譯，最後一行用一句話說重點。
        - 看不清楚的寫「（看不清楚）」，不要猜。
        - 只輸出翻譯內容，不要前言。
        """
    }

    /// 照片 → Gemini 翻譯（需要 Gemini 金鑰）。
    static func translateMenu(_ image: UIImage, langName: String) async throws -> String {
        guard let key = Keychain.get(Keychain.geminiKey), !key.isEmpty else {
            throw GeminiClient.Failure(message: "拍照翻譯需要 Gemini（免費）：請到設定填 Gemini 金鑰".tr)
        }
        guard let jpg = FoodPhotos.shrink(image) else { throw GeminiClient.Failure(message: "照片讀不到".tr) }
        let body: [String: Any] = [
            "systemInstruction": ["parts": [["text": menuPrompt(langName)]]],
            "contents": [["role": "user", "parts": [
                ["inline_data": ["mime_type": "image/jpeg", "data": jpg.base64EncodedString()]],
                ["text": "請翻譯這張照片。"],
            ]]],
            "generationConfig": ["temperature": 0.2],
        ]
        let client = GeminiClient(apiKey: key)
        var last: Error = GeminiClient.Failure(message: "Gemini 回應看不懂")
        for model in GeminiClient.models {
            var r = client.request("\(GeminiClient.base)/v1beta/models/\(model):generateContent", "POST", timeout: 90)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
            do {
                let (data, resp) = try await URLSession.shared.data(for: r)
                guard ((resp as? HTTPURLResponse)?.statusCode ?? 0) < 300 else { last = GeminiClient.failure(resp, data); continue }
                return try GeminiClient.parseGenerated(data).text.trimmingCharacters(in: .whitespacesAndNewlines)
            } catch { last = error }
        }
        throw last
    }
}

/// 即時翻譯頁的情境列＋片語。
struct SceneBar: View {
    let source: String
    let target: String
    @AppStorage(TranslateScene.key) private var scene = TranslateScene.general
    @State private var custom = TranslateScene.customPhrases
    @State private var adding = false
    @State private var newPhrase = ""
    @State private var shown: PhraseCard.Item?
    @State private var menuPick: PhotosPickerItem?
    @State private var camera = false
    @State private var menuResult: String?
    @State private var menuBusy = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Picker("情境".tr, selection: Binding(get: { scene }, set: { TranslateScene.setMode($0); scene = $0 })) {
                ForEach(TranslateScene.all, id: \.self) { Text(TranslateScene.label($0)).tag($0) }
            }
            .pickerStyle(.segmented)
            Text(TranslateScene.note(scene)).font(.labelMedium).foregroundStyle(Brand.textMuted)
            let list = TranslateScene.phrases(scene) + (scene == TranslateScene.general ? [] : custom)
            if scene != TranslateScene.general {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        if scene == TranslateScene.travel {
                            chip("拍菜單".tr, icon: "camera") { camera = true }
                            PhotosPicker(selection: $menuPick, matching: .images) {
                                Label("選照片".tr, systemImage: "photo").font(.labelLarge).foregroundStyle(Brand.cyan)
                                    .padding(.horizontal, 10).padding(.vertical, 7)
                                    .background(Capsule().fill(Brand.accentSoft))
                            }
                        }
                        ForEach(list, id: \.self) { p in
                            chip(p) { shown = .init(text: p) }
                                .contextMenu {
                                    if custom.contains(p) {
                                        Button(role: .destructive) { TranslateScene.removeCustom(p); custom = TranslateScene.customPhrases } label: {
                                            Label("刪除".tr, systemImage: "trash")
                                        }
                                    }
                                }
                        }
                        chip("＋ " + "自訂片語".tr) { adding = true }
                    }
                }
            }
        }
        .alert("自訂片語".tr, isPresented: $adding) {
            TextField("例如：請幫我叫計程車".tr, text: $newPhrase)
            Button("加入".tr) { TranslateScene.addCustom(newPhrase); newPhrase = ""; custom = TranslateScene.customPhrases }
            Button("取消".tr, role: .cancel) { newPhrase = "" }
        } message: { Text("用你的語言寫，點一下會翻成對方的語言。長按可以刪除。".tr) }
        .sheet(item: $shown) { item in PhraseCard(item: item, from: TranslateScene.myLang, to: TranslateScene.otherLang(source: source, target: target)) }
        .sheet(isPresented: $camera) { MenuCamera { img in if let img { translate(img) } } .ignoresSafeArea() }
        .onChange(of: menuPick) { item in
            guard let item else { return }
            Task {
                if let d = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: d) { translate(img) }
                menuPick = nil
            }
        }
        .sheet(isPresented: Binding(get: { menuResult != nil || menuBusy }, set: { if !$0 { menuResult = nil } })) {
            NavigationStack {
                ScrollView {
                    if menuBusy { ProgressView("翻譯中…".tr).padding(40) }
                    else { Text(menuResult ?? "").font(.bodyLarge).textSelection(.enabled).padding(20).frame(maxWidth: .infinity, alignment: .leading) }
                }
                .navigationTitle("菜單翻譯".tr).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成".tr) { menuResult = nil; menuBusy = false } } }
            }
        }
    }

    private func chip(_ text: String, icon: String? = nil, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let icon { Image(systemName: icon) }
                Text(text).lineLimit(1)
            }
            .font(.labelLarge).foregroundStyle(icon != nil ? Brand.cyan : Brand.text)
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(Capsule().fill(icon != nil ? AnyShapeStyle(Brand.accentSoft) : AnyShapeStyle(Brand.surfaceHigh)))
        }
        .buttonStyle(.plain)
    }

    private func translate(_ img: UIImage) {
        menuBusy = true
        Task {
            do { menuResult = try await TranslateScene.translateMenu(img, langName: TranslateLangs.name(TranslateScene.myLang)) }
            catch { menuResult = error.localizedDescription }
            menuBusy = false
        }
    }
}

/// 片語：翻成對方的語言、放大、念出來、可以翻轉 180° 給對面看。
struct PhraseCard: View {
    struct Item: Identifiable { let text: String; var id: String { text } }
    let item: Item
    let from: String
    let to: String
    @Environment(\.dismiss) private var dismiss
    @State private var result: String?
    @State private var flipped = false
    @State private var failed: String?

    var body: some View {
        VStack(spacing: 20) {
            HStack(spacing: 22) {
                Button { dismiss() } label: { Image(systemName: "xmark").font(.title2) }.accessibilityLabel("關閉".tr)
                Spacer()
                Button { if let r = result { Speaker.shared.speak(r, lang: to, queue: false) } } label: {
                    Image(systemName: "speaker.wave.2").font(.title2)
                }
                .accessibilityLabel("念這句".tr)
                Button { withAnimation { flipped.toggle() } } label: { Image(systemName: "arrow.triangle.2.circlepath").font(.title2) }
                    .accessibilityLabel("翻轉給對面的人看".tr)
            }
            .foregroundStyle(Brand.text)
            Spacer()
            VStack(spacing: 16) {
                Text(result ?? failed ?? "…").font(.system(size: 44, weight: .semibold)).foregroundStyle(Brand.text)
                    .multilineTextAlignment(.center).minimumScaleFactor(0.4)
                Text(item.text).font(.system(size: 20)).foregroundStyle(Brand.textMuted).multilineTextAlignment(.center)
            }
            .rotationEffect(.degrees(flipped ? 180 : 0))
            Spacer()
        }
        .padding(24)
        .background(AppBackdrop())
        .background(translator)
    }

    @ViewBuilder private var translator: some View {
        if TranslateLangs.same(source: from, target: to) {
            Color.clear.onAppear { done(item.text) }
        } else if #available(iOS 18.0, *) {
            Color.clear.translationTask(TranslationSession.Configuration(source: Locale.Language(identifier: from),
                                                                         target: Locale.Language(identifier: to))) { s in
                do { done(try await s.translate(item.text).targetText) }
                catch { failed = "翻譯失敗：{0}".trf(error.localizedDescription) }
            }
        } else {
            Color.clear.onAppear { failed = "翻譯需要 iOS 18 以上；這支 iPhone 只顯示原文".tr }
        }
    }

    private func done(_ t: String) {
        let r = TranslateFixes.apply(t, lang: to)
        result = r
        if UserDefaults.standard.bool(forKey: LiveCaptions.readAloudKey) || TranslateScene.mode != TranslateScene.business {
            Speaker.shared.speak(r, lang: to, queue: false)
        }
    }
}

/// 相機拍一張（菜單）。
struct MenuCamera: UIViewControllerRepresentable {
    let done: (UIImage?) -> Void
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(done: done) }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (UIImage?) -> Void
        init(done: @escaping (UIImage?) -> Void) { self.done = done }
        func imagePickerController(_ p: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            p.dismiss(animated: true); done(info[.originalImage] as? UIImage)
        }
        func imagePickerControllerDidCancel(_ p: UIImagePickerController) { p.dismiss(animated: true); done(nil) }
    }
}
