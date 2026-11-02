import AVFoundation
import UIKit

/// Owns the AVCaptureSession. Everything that touches the session runs on `sessionQueue`;
/// camera/audio frames arrive on `dataQueue`, where they are drawn to the preview and handed to the recorder.
final class CameraService: NSObject, @unchecked Sendable {
    let session = AVCaptureSession()
    let renderer = PreviewRenderer()

    private let sessionQueue = DispatchQueue(label: "zoomify.session")
    private let dataQueue = DispatchQueue(label: "zoomify.data", qos: .userInteractive)

    private let recorder = MovieRecorder()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let audioOutput = AVCaptureAudioDataOutput()
    private var outputsAdded = false

    private var device: AVCaptureDevice?
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var rotationCoordinator: AVCaptureDevice.RotationCoordinator?
    private var constituentObservation: NSKeyValueObservation?
    private var photoProcessors: [Int64: PhotoCaptureProcessor] = [:]

    /// Called (on an arbitrary queue) when auto focus/exposure should go back to continuous mode.
    var onSubjectAreaChange: (@Sendable () -> Void)?

    override init() {
        super.init()
        NotificationCenter.default.addObserver(self, selector: #selector(sessionRuntimeError(_:)),
                                               name: NSNotification.Name("AVCaptureSessionRuntimeErrorNotification"), object: session)
        NotificationCenter.default.addObserver(self, selector: #selector(subjectAreaDidChange(_:)),
                                               name: NSNotification.Name("AVCaptureDeviceSubjectAreaDidChangeNotification"), object: nil)
    }

    // MARK: - Configuration

    func apply(_ config: CaptureConfig, completion: @escaping @Sendable (ConfigResult?) -> Void) {
        sessionQueue.async {
            let result = self.configureSession(config)
            if !self.session.isRunning { self.session.startRunning() }
            completion(result)
        }
    }

    static func bestCamera(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        // Virtual multi-lens devices first: the system then switches between ultra-wide, wide and tele
        // by itself while we only change `videoZoomFactor`, which is what makes 0.5x -> 1x seamless.
        let types: [AVCaptureDevice.DeviceType] = position == .back
            ? [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
            : [.builtInTrueDepthCamera, .builtInWideAngleCamera]
        let discovery = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: position)
        for type in types {
            if let match = discovery.devices.first(where: { $0.deviceType == type }) { return match }
        }
        return discovery.devices.first
    }

    private func configureSession(_ config: CaptureConfig) -> ConfigResult? {
        session.beginConfiguration()

        // 1. Camera input.
        if device == nil || device?.position != config.position {
            if let input = videoInput {
                session.removeInput(input)
                videoInput = nil
            }
            guard let camera = Self.bestCamera(for: config.position),
                  let input = try? AVCaptureDeviceInput(device: camera),
                  session.canAddInput(input) else {
                session.commitConfiguration()
                return nil
            }
            session.addInput(input)
            videoInput = input
            device = camera
        }
        guard let device else {
            session.commitConfiguration()
            return nil
        }

        // 2. Outputs (added once).
        if !outputsAdded {
            if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
            videoOutput.setSampleBufferDelegate(self, queue: dataQueue)
            if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
            audioOutput.setSampleBufferDelegate(self, queue: dataQueue)
            if session.canAddOutput(audioOutput) { session.addOutput(audioOutput) }
            outputsAdded = true
        }

        // 3. Microphone only in video mode (like the Camera app, so music keeps playing in photo mode).
        if config.mode == .video {
            if audioInput == nil,
               AVCaptureDevice.authorizationStatus(for: .audio) == .authorized,
               let mic = AVCaptureDevice.default(for: .audio),
               let input = try? AVCaptureDeviceInput(device: mic),
               session.canAddInput(input) {
                session.addInput(input)
                audioInput = input
            }
        } else if let input = audioInput {
            session.removeInput(input)
            audioInput = nil
        }

        // 4. Format.
        var hdrSupported = false
        var hdrActive = false
        switch config.mode {
        case .photo:
            // The .photo preset is Apple's own tuned photo pipeline (Smart HDR, Deep Fusion, Photonic Engine),
            // and with wide colour enabled the session runs in Display P3, exactly like the Camera app.
            session.automaticallyConfiguresCaptureDeviceForWideColor = true
            if session.canSetSessionPreset(.photo) { session.sessionPreset = .photo }
            videoOutput.automaticallyConfiguresOutputBufferDimensions = true
            videoOutput.alwaysDiscardsLateVideoFrames = true

        case .video:
            session.automaticallyConfiguresCaptureDeviceForWideColor = false
            hdrSupported = Self.pickVideoFormat(device, hdr: true) != nil
            let wantHDR = config.hdrVideo && hdrSupported
            if let format = Self.pickVideoFormat(device, hdr: wantHDR) {
                do {
                    try device.lockForConfiguration()
                    device.activeFormat = format
                    let thirty = CMTime(value: 1, timescale: 30)
                    device.activeVideoMinFrameDuration = thirty
                    device.activeVideoMaxFrameDuration = thirty
                    if wantHDR, format.supportedColorSpaces.contains(.HLG_BT2020) {
                        // 10-bit HLG / BT.2020: the same HDR flavour the Camera app records.
                        device.activeColorSpace = .HLG_BT2020
                        hdrActive = true
                    } else if format.supportedColorSpaces.contains(.sRGB) {
                        device.activeColorSpace = .sRGB
                    }
                    device.unlockForConfiguration()
                } catch {
                    print("Zoomify: could not lock device: \(error)")
                }
            } else if session.canSetSessionPreset(.high) {
                session.sessionPreset = .high
            }
            // Full-resolution (4K) buffers: they feed both the preview and the encoder.
            videoOutput.automaticallyConfiguresOutputBufferDimensions = false
            videoOutput.deliversPreviewSizedOutputBuffers = false
            videoOutput.alwaysDiscardsLateVideoFrames = false
        }

        // 5. Pixel format: 10-bit for HDR, 8-bit full range otherwise.
        let preferred: [OSType] = hdrActive
            ? [kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange, kCVPixelFormatType_420YpCbCr10BiPlanarFullRange,
               kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
            : [kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
        let available = videoOutput.availableVideoPixelFormatTypes
        if let pixelFormat = preferred.first(where: { available.contains($0) }) {
            videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: pixelFormat]
        }

        if let connection = videoOutput.connection(with: .video) {
            // The preview shader rotates / mirrors, so keep the buffers in native sensor orientation.
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = false
            }
            if connection.isVideoStabilizationSupported {
                connection.preferredVideoStabilizationMode = config.mode == .video ? .standard : .off
            }
        }

        // 6. Photo output.
        configurePhotoOutput(device: device, config: config)

        session.commitConfiguration()

        // 7. Lens-switch notifications drive the cross-fade in the preview.
        constituentObservation = nil
        if device.isVirtualDevice {
            constituentObservation = device.observe(\.activePrimaryConstituent, options: [.new]) { [weak self] _, _ in
                self?.renderer.triggerLensSwitch()
            }
        }
        rotationCoordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
        renderer.setMirrored(config.position == .front)
        renderer.invalidateHistory()
        resetFocusLocked(device)

        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let photoDims = device.activeFormat.supportedMaxPhotoDimensions
        return ConfigResult(
            device: device,
            zoom: ZoomCapabilities(device: device, maxDisplayZoom: 50),
            proRAWSupported: config.mode == .photo && photoOutput.isAppleProRAWSupported,
            has48MP: photoDims.contains { Int($0.width) * Int($0.height) > 40_000_000 },
            hdrSupported: hdrSupported,
            hdrActive: hdrActive,
            is4K: dims.width >= 3840,
            bufferAspect: dims.height > 0 ? CGFloat(dims.width) / CGFloat(dims.height) : 4.0 / 3.0
        )
    }

    private func configurePhotoOutput(device: AVCaptureDevice, config: CaptureConfig) {
        photoOutput.maxPhotoQualityPrioritization = .quality
        let wantRAW = config.mode == .photo && config.photoFormat == .proRAW
        if photoOutput.isAppleProRAWSupported {
            photoOutput.isAppleProRAWEnabled = wantRAW
        }
        let allowFullSensor = config.photoFormat != .heif
        if let dims = Self.choosePhotoDimensions(device.activeFormat.supportedMaxPhotoDimensions,
                                                 allowFullSensor: allowFullSensor) {
            photoOutput.maxPhotoDimensions = dims
        }
        if photoOutput.isContentAwareDistortionCorrectionSupported {
            photoOutput.isContentAwareDistortionCorrectionEnabled = true
        }
        if photoOutput.isZeroShutterLagSupported { photoOutput.isZeroShutterLagEnabled = true }
        if photoOutput.isResponsiveCaptureSupported { photoOutput.isResponsiveCaptureEnabled = true }
        if photoOutput.isFastCapturePrioritizationSupported { photoOutput.isFastCapturePrioritizationEnabled = true }
    }

    static func choosePhotoDimensions(_ dims: [CMVideoDimensions], allowFullSensor: Bool) -> CMVideoDimensions? {
        let sorted = dims.sorted { Int($0.width) * Int($0.height) < Int($1.width) * Int($1.height) }
        if allowFullSensor { return sorted.last }
        // Camera-app default: 24 MP where offered, otherwise 12 MP.
        return sorted.last(where: { Int($0.width) * Int($0.height) <= 25_000_000 }) ?? sorted.last
    }

    /// Picks a 4K (falling back to 1080p) format that can run at 30 fps.
    static func pickVideoFormat(_ device: AVCaptureDevice, hdr: Bool) -> AVCaptureDevice.Format? {
        let tenBitTypes: Set<OSType> = [kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
                                        kCVPixelFormatType_420YpCbCr10BiPlanarFullRange]
        let candidates = device.formats.filter { format in
            guard format.videoSupportedFrameRateRanges.contains(where: { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 })
            else { return false }
            let subtype = CMFormatDescriptionGetMediaSubType(format.formatDescription)
            let isTenBit = tenBitTypes.contains(subtype)
            if hdr { return isTenBit && format.supportedColorSpaces.contains(.HLG_BT2020) }
            return !isTenBit
        }
        func score(_ format: AVCaptureDevice.Format) -> Double {
            var s = 0.0
            if !format.isVideoBinned { s += 1000 }
            if format.isVideoHDRSupported { s += 100 }
            if format.isVideoStabilizationModeSupported(.cinematicExtended) { s += 20 }
            let subtype = CMFormatDescriptionGetMediaSubType(format.formatDescription)
            if subtype == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange { s += 10 }
            s += Double(min(format.videoMaxZoomFactor, 200)) / 100
            return s
        }
        for (width, height) in [(Int32(3840), Int32(2160)), (Int32(1920), Int32(1080))] {
            let matches = candidates.filter {
                let d = CMVideoFormatDescriptionGetDimensions($0.formatDescription)
                return d.width == width && d.height == height
            }
            if let best = matches.max(by: { score($0) < score($1) }) { return best }
        }
        return nil
    }

    // MARK: - Focus / exposure

    /// `devicePoint` is in sensor coordinates: (0,0) top-left, (1,1) bottom-right of the landscape buffer.
    func focus(at devicePoint: CGPoint) {
        sessionQueue.async {
            guard let device = self.device else { return }
            do {
                try device.lockForConfiguration()
                if device.isFocusPointOfInterestSupported, device.isFocusModeSupported(.autoFocus) {
                    device.focusPointOfInterest = devicePoint
                    device.focusMode = .autoFocus
                }
                if device.isExposurePointOfInterestSupported, device.isExposureModeSupported(.autoExpose) {
                    device.exposurePointOfInterest = devicePoint
                    device.exposureMode = .autoExpose
                }
                device.isSubjectAreaChangeMonitoringEnabled = true
                device.unlockForConfiguration()
            } catch {
                print("Zoomify: focus failed: \(error)")
            }
        }
    }

    func resetFocus() {
        sessionQueue.async {
            guard let device = self.device else { return }
            self.resetFocusLocked(device)
        }
    }

    private func resetFocusLocked(_ device: AVCaptureDevice) {
        do {
            try device.lockForConfiguration()
            let center = CGPoint(x: 0.5, y: 0.5)
            if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = center }
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            if device.isExposurePointOfInterestSupported { device.exposurePointOfInterest = center }
            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            device.isSubjectAreaChangeMonitoringEnabled = false
            device.unlockForConfiguration()
        } catch {
            print("Zoomify: reset focus failed: \(error)")
        }
    }

    @objc private func subjectAreaDidChange(_ note: Notification) {
        onSubjectAreaChange?()
    }

    @objc private func sessionRuntimeError(_ note: Notification) {
        sessionQueue.async {
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    // MARK: - Photo

    func capturePhoto(format: PhotoFormat,
                      flash: FlashSetting,
                      willCapture: @escaping @Sendable () -> Void,
                      completion: @escaping @Sendable (UIImage?, String?) -> Void) {
        sessionQueue.async {
            if let connection = self.photoOutput.connection(with: .video),
               let angle = self.rotationCoordinator?.videoRotationAngleForHorizonLevelCapture,
               connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            }
            let settings = self.makePhotoSettings(format: format, flash: flash)
            let id = settings.uniqueID
            let processor = PhotoCaptureProcessor(uniqueID: id, willCapture: willCapture) { [weak self] image, error in
                self?.sessionQueue.async { self?.photoProcessors[id] = nil }
                completion(image, error)
            }
            self.photoProcessors[id] = processor
            self.photoOutput.capturePhoto(with: settings, delegate: processor)
        }
    }

    private func makePhotoSettings(format: PhotoFormat, flash: FlashSetting) -> AVCapturePhotoSettings {
        let settings: AVCapturePhotoSettings
        if format == .proRAW, photoOutput.isAppleProRAWEnabled,
           let raw = photoOutput.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isAppleProRAWPixelFormat($0) }) {
            settings = AVCapturePhotoSettings(rawPixelFormatType: raw)
        } else if photoOutput.availablePhotoCodecTypes.contains(.hevc) {
            settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.hevc])
        } else {
            settings = AVCapturePhotoSettings()
        }
        settings.maxPhotoDimensions = photoOutput.maxPhotoDimensions
        // Maximum processing: multi-frame fusion, Deep Fusion / Photonic Engine where the device has it.
        settings.photoQualityPrioritization = .quality
        if photoOutput.supportedFlashModes.contains(flash.avMode) {
            settings.flashMode = flash.avMode
        }
        if let previewType = settings.availablePreviewPhotoPixelFormatTypes.first {
            settings.previewPhotoFormat = [
                kCVPixelBufferPixelFormatTypeKey as String: previewType,
                kCVPixelBufferWidthKey as String: 360,
                kCVPixelBufferHeightKey as String: 360,
            ]
        }
        return settings
    }

    // MARK: - Video

    func startRecording(codec: VideoCodecChoice) {
        sessionQueue.async {
            let angle = self.rotationCoordinator?.videoRotationAngleForHorizonLevelCapture ?? 90
            let includeAudio = self.audioInput != nil
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("Zoomify-\(UUID().uuidString)")
                .appendingPathExtension("mov")
            self.dataQueue.async {
                self.recorder.start(url: url, codec: codec, rotationDegrees: angle, includeAudio: includeAudio)
            }
        }
    }

    func stopRecording(completion: @escaping @Sendable (URL?, String?) -> Void) {
        dataQueue.async {
            self.recorder.stop(completion: completion)
        }
    }
}

extension CameraService: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if output === videoOutput {
            if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
                renderer.render(pixelBuffer: pixelBuffer)
            }
            recorder.appendVideo(sampleBuffer)
        } else if output === audioOutput {
            recorder.appendAudio(sampleBuffer)
        }
    }
}
