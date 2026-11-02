import AVFoundation
import SwiftUI
import UIKit

/// Zoom state lives in its own object so the 120 Hz zoom updates only re-render the zoom controls.
@MainActor
final class ZoomUIState: ObservableObject {
    @Published var zoom: CGFloat = 1
    @Published var presets: [CGFloat] = [1]
    @Published var minZoom: CGFloat = 1
    @Published var maxZoom: CGFloat = 1
    @Published var dialVisible = false
}

@MainActor
final class CameraModel: ObservableObject {
    @Published private(set) var mode: CaptureMode = .photo
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published private(set) var flash: FlashSetting = .off
    @Published private(set) var photoFormat: PhotoFormat = .heif
    @Published private(set) var codec: VideoCodecChoice = .hevc
    @Published private(set) var hdrVideo = true

    @Published private(set) var cameraAuthorized: Bool?
    @Published private(set) var isRecording = false
    @Published private(set) var recordingStarted: Date?
    @Published private(set) var capturesInFlight = 0
    @Published private(set) var shutterBlink = false
    @Published private(set) var thumbnail: UIImage?
    @Published private(set) var focusPoint: CGPoint?
    @Published var toast: String?

    @Published private(set) var proRAWSupported = false
    @Published private(set) var has48MP = false
    @Published private(set) var proResSupported = false
    @Published private(set) var hdrSupported = false
    @Published private(set) var hdrActive = false
    @Published private(set) var is4K = true

    let service = CameraService()
    let zoomUI = ZoomUIState()
    private let zoom = ZoomController()

    private var bufferAspect: CGFloat = 4.0 / 3.0
    private var gestureStartZoom: CGFloat = 1
    private var focusHideTask: Task<Void, Never>?
    private var dialHideTask: Task<Void, Never>?
    private var started = false

    init() {
        zoom.onChange = { [weak self] value, velocity in
            guard let self else { return }
            self.zoomUI.zoom = value
            self.service.renderer.updateZoom(value, velocity: velocity)
        }
        service.onSubjectAreaChange = { [weak self] in
            Task { @MainActor in self?.service.resetFocus() }
        }
        proResSupported = MovieRecorder.isProResSupported()
    }

    var codecLabel: String { codec.label(is4K: is4K, hdr: hdrActive) }

    // MARK: - Lifecycle

    func start() {
        guard !started else { return }
        started = true
        Task {
            let granted = await Self.requestAccess(for: .video)
            cameraAuthorized = granted
            guard granted else { return }
            reconfigure(keepZoom: false)
        }
    }

    static func requestAccess(for type: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: type) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: type)
        default: return false
        }
    }

    private func reconfigure(keepZoom: Bool) {
        let config = CaptureConfig(mode: mode, position: position, photoFormat: photoFormat, hdrVideo: hdrVideo)
        let previousZoom = zoom.targetZoom
        service.apply(config) { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                guard let result else {
                    self.toast = "The camera is unavailable."
                    return
                }
                self.proRAWSupported = result.proRAWSupported
                self.has48MP = result.has48MP
                self.hdrSupported = result.hdrSupported
                self.hdrActive = result.hdrActive
                self.is4K = result.is4K
                self.bufferAspect = result.bufferAspect
                if self.photoFormat == .proRAW && !result.proRAWSupported && self.mode == .photo {
                    self.photoFormat = .heif
                }
                if self.hdrActive && self.codec == .h264 { self.codec = .hevc }
                self.zoomUI.presets = result.zoom.presets
                self.zoomUI.minZoom = result.zoom.minDisplay
                self.zoomUI.maxZoom = result.zoom.maxDisplay
                self.zoom.attach(device: result.device, capabilities: result.zoom,
                                 initialZoom: keepZoom ? previousZoom : 1)
            }
        }
    }

    // MARK: - Settings

    func setMode(_ newMode: CaptureMode) {
        guard newMode != mode, !isRecording else { return }
        mode = newMode
        Task {
            if newMode == .video { _ = await Self.requestAccess(for: .audio) }
            reconfigure(keepZoom: true)
        }
    }

    func flipCamera() {
        guard !isRecording else { return }
        position = position == .back ? .front : .back
        reconfigure(keepZoom: false)
    }

    func cycleFlash() {
        let all = FlashSetting.allCases
        flash = all[(all.firstIndex(of: flash)! + 1) % all.count]
    }

    func cyclePhotoFormat() {
        var options: [PhotoFormat] = [.heif]
        if has48MP { options.append(.heifMax) }
        if proRAWSupported { options.append(.proRAW) }
        let index = options.firstIndex(of: photoFormat) ?? 0
        let next = options[(index + 1) % options.count]
        guard next != photoFormat else { return }
        photoFormat = next
        reconfigure(keepZoom: true)
    }

    func toggleHDR() {
        guard !isRecording, hdrSupported else { return }
        hdrVideo.toggle()
        reconfigure(keepZoom: true)
    }

    func cycleCodec() {
        guard !isRecording else { return }
        var options: [VideoCodecChoice] = [.hevc]
        if !hdrActive { options.append(.h264) }
        if proResSupported { options.append(.proRes) }
        let index = options.firstIndex(of: codec) ?? 0
        codec = options[(index + 1) % options.count]
    }

    // MARK: - Zoom

    func selectPreset(_ value: CGFloat) {
        zoom.setZoom(value, motion: .tap)
    }

    func pinchBegan() {
        gestureStartZoom = zoom.targetZoom
    }

    func pinchChanged(_ magnification: CGFloat) {
        zoom.setZoom(gestureStartZoom * magnification, motion: .pinch)
    }

    func dialBegan() {
        gestureStartZoom = zoom.targetZoom
        dialHideTask?.cancel()
        zoomUI.dialVisible = true
    }

    /// Dragging left zooms in, one doubling per `ZoomDial.pointsPerOctave` points.
    func dialChanged(_ translation: CGFloat) {
        let octaves = -translation / ZoomDial.pointsPerOctave
        zoom.setZoom(gestureStartZoom * CGFloat(exp2(Double(octaves))), motion: .dial)
    }

    func dialEnded() {
        dialHideTask?.cancel()
        dialHideTask = Task {
            try? await Task.sleep(for: .seconds(1.5))
            if !Task.isCancelled { zoomUI.dialVisible = false }
        }
    }

    // MARK: - Focus

    func focus(at point: CGPoint, in size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        let devicePoint = Self.devicePoint(for: point, viewSize: size, bufferAspect: bufferAspect,
                                           mirrored: position == .front)
        service.focus(at: devicePoint)
        focusPoint = point
        focusHideTask?.cancel()
        focusHideTask = Task {
            try? await Task.sleep(for: .seconds(1.2))
            if !Task.isCancelled { focusPoint = nil }
        }
    }

    /// Same mapping as `viewToBuffer` in Shaders.metal (aspect fill, 90° rotation, optional mirror).
    static func devicePoint(for point: CGPoint, viewSize: CGSize, bufferAspect: CGFloat, mirrored: Bool) -> CGPoint {
        let imageAspect = 1 / bufferAspect
        let viewAspect = viewSize.width / viewSize.height
        var scale = CGSize(width: 1, height: 1)
        if imageAspect > viewAspect {
            scale.width = viewAspect / imageAspect
        } else {
            scale.height = imageAspect / viewAspect
        }
        let offset = CGPoint(x: (1 - scale.width) / 2, y: (1 - scale.height) / 2)
        var r = CGPoint(x: offset.x + point.x / viewSize.width * scale.width,
                        y: offset.y + point.y / viewSize.height * scale.height)
        if mirrored { r.x = 1 - r.x }
        return CGPoint(x: r.y, y: 1 - r.x)
    }

    // MARK: - Capture

    func shutter() {
        switch mode {
        case .photo: capturePhoto()
        case .video: isRecording ? stopRecording() : startRecording()
        }
    }

    private func capturePhoto() {
        capturesInFlight += 1
        service.capturePhoto(format: photoFormat, flash: flash, willCapture: { [weak self] in
            Task { @MainActor in self?.blink() }
        }, completion: { [weak self] image, error in
            Task { @MainActor in
                guard let self else { return }
                self.capturesInFlight = max(0, self.capturesInFlight - 1)
                if let image { self.thumbnail = image }
                if let error { self.toast = error }
            }
        })
    }

    private func blink() {
        shutterBlink = true
        Task {
            try? await Task.sleep(for: .milliseconds(90))
            shutterBlink = false
        }
    }

    private func startRecording() {
        isRecording = true
        recordingStarted = Date()
        service.startRecording(codec: codec)
    }

    func stopRecordingIfNeeded() {
        if isRecording { stopRecording() }
    }

    private func stopRecording() {
        isRecording = false
        recordingStarted = nil
        service.stopRecording { [weak self] url, error in
            Task { @MainActor in
                guard let self else { return }
                if let error { self.toast = error }
                guard let url else { return }
                if let image = await Self.videoThumbnail(url) { self.thumbnail = image }
                PhotoLibrary.saveVideo(url) { saved in
                    if !saved {
                        Task { @MainActor in self.toast = "Couldn't save the video to your library." }
                    }
                }
            }
        }
    }

    private static func videoThumbnail(_ url: URL) async -> UIImage? {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 300, height: 300)
        guard let result = try? await generator.image(at: .zero) else { return nil }
        return UIImage(cgImage: result.image)
    }
}
