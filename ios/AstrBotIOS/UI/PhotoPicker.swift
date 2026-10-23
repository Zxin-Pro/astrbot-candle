import SwiftUI
import PhotosUI
import UIKit

/// 选自定义背景图。Android 版走 `ACTION_GET_CONTENT`，
/// iOS 这边用 PHPicker（不需要相册写权限，只有选中的那张会给到我们）。
struct PhotoPicker: UIViewControllerRepresentable {
    /// 选完回主线程给图；用户取消就不回调
    let onPick: (UIImage) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var cfg = PHPickerConfiguration(photoLibrary: .shared())
        cfg.filter = .images
        cfg.selectionLimit = 1
        cfg.preferredAssetRepresentationMode = .current
        let vc = PHPickerViewController(configuration: cfg)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPick: (UIImage) -> Void
        init(onPick: @escaping (UIImage) -> Void) { self.onPick = onPick }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let provider = results.first?.itemProvider,
                  provider.canLoadObject(ofClass: UIImage.self) else { return }
            provider.loadObject(ofClass: UIImage.self) { obj, _ in
                guard let img = obj as? UIImage else { return }
                DispatchQueue.main.async { self.onPick(img) }
            }
        }
    }
}
