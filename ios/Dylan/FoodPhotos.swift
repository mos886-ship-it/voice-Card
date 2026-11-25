import SwiftUI
import PhotosUI
import UIKit

/// 飲食照片（照 Android `FoodPhotos.kt`）：一餐最多 3 張，轉正、縮成長邊 768 px、JPEG 品質 0.8，
/// 重新編碼順便去掉 EXIF（含 GPS）。存成記錄資料夾的 photo_1.jpg…；照片只送 Gemini（免費 flash 模型）。
enum FoodPhotos {
    static let max = 3
    static let longEdge: CGFloat = 768

    /// 「拍＋說」：照片先放這裡，手機開始錄飲食時接進那筆記錄（PhoneRecorder.begin）。
    @MainActor static var pendingForVoice: [Data] = []

    static func url(_ id: String, _ n: Int) -> URL { MeetingStore.dir(id).appendingPathComponent("photo_\(n).jpg") }

    /// 這筆記錄的照片（依序）。
    static func files(_ id: String) -> [URL] {
        (1...max).map { url(id, $0) }.filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    static func has(_ id: String) -> Bool { !files(id).isEmpty }

    /// 原始照片 → 轉正、縮小、重新編碼的 JPEG（沒有 EXIF / GPS）。
    static func shrink(_ image: UIImage) -> Data? {
        let s = image.size
        guard s.width > 0, s.height > 0 else { return nil }
        let k = min(1, longEdge / Swift.max(s.width, s.height))
        let size = CGSize(width: (s.width * k).rounded(), height: (s.height * k).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        // draw(in:) 會依 imageOrientation 轉正，輸出的是全新的點陣圖（不帶原檔的中繼資料）
        let img = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return img.jpegData(compressionQuality: 0.8)
    }

    static func save(_ photos: [Data], to id: String) {
        try? FileManager.default.createDirectory(at: MeetingStore.dir(id), withIntermediateDirectories: true)
        for (i, d) in photos.prefix(max).enumerated() { try? d.write(to: url(id, i + 1), options: .atomic) }
    }

    /// 「只拍照」：建一筆 food_ 記錄（source = photo、長度 0），直接交給 AI。
    @MainActor static func createPhotoOnly(_ photos: [Data]) -> String {
        let id = RecordingID.make(prefix: Meeting.foodPrefix)
        save(photos, to: id)
        var m = Meeting.new(id: id, source: "photo")
        m.status = .received
        m.lang = nil   // 只有照片：沒有辨識語言
        m.dialect = nil
        MeetingStore.shared.save(m)
        Task { await MeetingStore.shared.process(id) }
        return id
    }
}

/// 拍照記錄的入口（中間錄音鍵長按、飲食分頁按鈕）：RootView 掛的 sheet 看這個。
@MainActor
final class FoodPhotoCapture: ObservableObject {
    static let shared = FoodPhotoCapture()
    @Published var presented = false
    @Published var withVoice = false
    func open(withVoice: Bool) { self.withVoice = withVoice; presented = true }
}

/// 拍照畫面：「只拍照 / 拍照＋口述」、相機或相簿、最多 3 張。
struct FoodPhotoSheet: View {
    @ObservedObject private var capture = FoodPhotoCapture.shared
    @Environment(\.dismiss) private var dismiss
    @State private var photos: [Data] = []
    @State private var picks: [PhotosPickerItem] = []
    @State private var camera = false
    let onDone: (String?) -> Void

    private var left: Int { FoodPhotos.max - photos.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Picker("方式", selection: $capture.withVoice) {
                        Text("只拍照").tag(false)
                        Text("拍照＋口述").tag(true)
                    }
                    .pickerStyle(.segmented)
                    Text(capture.withVoice ? "拍好後開始錄音，補一句吃了什麼、吃了多少（口述跟照片不一樣時以口述為準）。"
                                           : "只用照片估算熱量。看得到包裝的營養標示會照標示算。")
                        .font(.bodySmall).foregroundStyle(Brand.textMuted)

                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                        ForEach(photos.indices, id: \.self) { i in
                            if let img = UIImage(data: photos[i]) {
                                Image(uiImage: img).resizable().scaledToFill()
                                    .frame(minWidth: 0, maxWidth: .infinity).aspectRatio(1, contentMode: .fit).clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                    .overlay(alignment: .topTrailing) {
                                        Button { photos.remove(at: i) } label: {
                                            Image(systemName: "xmark.circle.fill").font(.system(size: 22))
                                                .foregroundStyle(.white, .black.opacity(0.55))
                                        }
                                        .padding(4).accessibilityLabel("移除第 \(i + 1) 張")
                                    }
                            }
                        }
                    }

                    HStack(spacing: 10) {
                        Button { camera = true } label: {
                            Label("拍照", systemImage: "camera").frame(maxWidth: .infinity).frame(height: 48)
                        }
                        .disabled(left == 0 || !UIImagePickerController.isSourceTypeAvailable(.camera))
                        PhotosPicker(selection: $picks, maxSelectionCount: Swift.max(left, 1), matching: .images) {
                            Label("相簿", systemImage: "photo.on.rectangle").frame(maxWidth: .infinity).frame(height: 48)
                        }
                        .disabled(left == 0)
                    }
                    .buttonStyle(.bordered).tint(Brand.cyan)
                    Text("已選 \(photos.count) / \(FoodPhotos.max) 張。照片會縮小並去掉位置資訊，只送給 Gemini 辨識。")
                        .font(.bodySmall).foregroundStyle(Brand.textFaint)

                    GradientButton(text: capture.withVoice ? "下一步：開始口述" : "估算這一餐", recordDot: capture.withVoice) {
                        finish()
                    }
                    .disabled(photos.isEmpty)
                    .opacity(photos.isEmpty ? 0.5 : 1)
                }
                .padding(20)
            }
            .background(Brand.backdrop.ignoresSafeArea())
            .navigationTitle("拍照記錄")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
        .onChange(of: picks) { items in
            guard !items.isEmpty else { return }
            Task {
                for item in items where photos.count < FoodPhotos.max {
                    if let d = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: d),
                       let jpg = FoodPhotos.shrink(img) { photos.append(jpg) }
                }
                picks = []
            }
        }
        .fullScreenCover(isPresented: $camera) {
            CameraPicker { img in
                if let img, let jpg = FoodPhotos.shrink(img), photos.count < FoodPhotos.max { photos.append(jpg) }
                camera = false
            }
            .ignoresSafeArea()
        }
    }

    private func finish() {
        guard !photos.isEmpty else { return }
        if capture.withVoice {
            FoodPhotos.pendingForVoice = photos
            dismiss()
            PhoneRecorder.shared.start(food: true)
            onDone(nil)
        } else {
            let id = FoodPhotos.createPhotoOnly(photos)
            dismiss()
            onDone(id)
        }
    }
}

/// 系統相機（需要 NSCameraUsageDescription）。
private struct CameraPicker: UIViewControllerRepresentable {
    let done: (UIImage?) -> Void
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = .camera
        p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(done) }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (UIImage?) -> Void
        init(_ done: @escaping (UIImage?) -> Void) { self.done = done }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            done(info[.originalImage] as? UIImage)
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { done(nil) }
    }
}

/// 詳情頁上方的照片列；點一下全螢幕。
struct FoodPhotoStrip: View {
    let id: String
    @State private var shown: URL?
    var body: some View {
        let files = FoodPhotos.files(id)
        if !files.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(files, id: \.self) { f in
                        if let img = UIImage(contentsOfFile: f.path) {
                            Button { shown = f } label: {
                                Image(uiImage: img).resizable().scaledToFill()
                                    .frame(width: 120, height: 120).clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("飲食照片，點一下放大")
                        }
                    }
                }
            }
            .fullScreenCover(item: $shown) { f in
                ZStack(alignment: .topTrailing) {
                    Color.black.ignoresSafeArea()
                    if let img = UIImage(contentsOfFile: f.path) {
                        Image(uiImage: img).resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                    Button { shown = nil } label: {
                        Image(systemName: "xmark").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                            .frame(width: 44, height: 44).background(Circle().fill(.white.opacity(0.2)))
                    }
                    .padding(16).accessibilityLabel("關閉")
                }
                .onTapGesture { shown = nil }
            }
        }
    }
}

extension URL: @retroactive Identifiable { public var id: String { absoluteString } }
