import AVFoundation
import CoreImage
import ImageIO
import Photos
import UIKit

/// Delegate for a single photo capture: grabs the file data and a small thumbnail, then saves to Photos.
final class PhotoCaptureProcessor: NSObject, AVCapturePhotoCaptureDelegate {
    let uniqueID: Int64
    private let willCapture: () -> Void
    private let completion: (UIImage?, String?) -> Void

    private var photoData: Data?
    private var isRAW = false
    private var thumbnail: UIImage?
    private var processingError: String?

    private static let ciContext = CIContext()

    init(uniqueID: Int64, willCapture: @escaping () -> Void, completion: @escaping (UIImage?, String?) -> Void) {
        self.uniqueID = uniqueID
        self.willCapture = willCapture
        self.completion = completion
    }

    func photoOutput(_ output: AVCapturePhotoOutput, willCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
        willCapture()
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            processingError = error.localizedDescription
            return
        }
        photoData = photo.fileDataRepresentation()
        isRAW = photo.isRawPhoto
        if let preview = photo.previewPixelBuffer {
            let orientation = (photo.metadata[kCGImagePropertyOrientation as String] as? UInt32)
                .flatMap { CGImagePropertyOrientation(rawValue: $0) }
            thumbnail = Self.makeImage(preview, orientation: orientation)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings, error: Error?) {
        if let error {
            completion(nil, error.localizedDescription)
            return
        }
        guard let data = photoData else {
            completion(nil, processingError ?? "The photo could not be captured.")
            return
        }
        let thumbnail = self.thumbnail
        let completion = self.completion
        PhotoLibrary.savePhoto(data, uniformTypeIdentifier: isRAW ? "com.adobe.raw-image" : nil) { saved in
            completion(thumbnail, saved ? nil : "Couldn't save the photo to your library.")
        }
    }

    private static func makeImage(_ buffer: CVPixelBuffer, orientation: CGImagePropertyOrientation?) -> UIImage? {
        var image = CIImage(cvPixelBuffer: buffer)
        if let orientation { image = image.oriented(orientation) }
        guard let cgImage = ciContext.createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

enum PhotoLibrary {
    static func savePhoto(_ data: Data, uniformTypeIdentifier: String?, completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                completion(false)
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.uniformTypeIdentifier = uniformTypeIdentifier
                request.addResource(with: .photo, data: data, options: options)
            }, completionHandler: { success, error in
                if let error { print("Zoomify: save photo failed: \(error)") }
                completion(success)
            })
        }
    }

    static func saveVideo(_ url: URL, completion: @escaping (Bool) -> Void) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                completion(false)
                return
            }
            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.shouldMoveFile = true
                request.addResource(with: .video, fileURL: url, options: options)
            }, completionHandler: { success, error in
                if let error { print("Zoomify: save video failed: \(error)") }
                completion(success)
            })
        }
    }
}
