import AVFoundation
import VideoToolbox

/// AVAssetWriter-based recorder so we control codec, bitrate, profile and colour tags directly
/// (AVCaptureMovieFileOutput only lets you pick the codec). Must be used from a single queue.
final class MovieRecorder {
    private enum State { case idle, armed, recording }

    private var state: State = .idle
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var audioInput: AVAssetWriterInput?
    private var url: URL?
    private var codec: VideoCodecChoice = .hevc
    private var rotationDegrees: CGFloat = 90
    private var includeAudio = false
    private var startTime: CMTime = .invalid
    private var latestAudioFormat: CMFormatDescription?
    private var failure: String?

    static func isProResSupported() -> Bool {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("zoomify-prores-probe.mov")
        try? FileManager.default.removeItem(at: url)
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mov) else { return false }
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.proRes422HQ,
            AVVideoWidthKey: 3840,
            AVVideoHeightKey: 2160,
        ]
        return writer.canApply(outputSettings: settings, forMediaType: .video)
    }

    func start(url: URL, codec: VideoCodecChoice, rotationDegrees: CGFloat, includeAudio: Bool) {
        guard state == .idle else { return }
        self.url = url
        self.codec = codec
        self.rotationDegrees = rotationDegrees
        self.includeAudio = includeAudio
        failure = nil
        state = .armed   // the writer is created on the first video frame, when we know size and colour tags
    }

    func appendVideo(_ sampleBuffer: CMSampleBuffer) {
        if state == .armed { setUpWriter(firstFrame: sampleBuffer) }
        guard state == .recording, let writer, let input = videoInput else { return }
        guard writer.status == .writing else {
            failure = writer.error?.localizedDescription ?? "The recording failed."
            return
        }
        if input.isReadyForMoreMediaData, !input.append(sampleBuffer) {
            failure = writer.error?.localizedDescription
        }
    }

    func appendAudio(_ sampleBuffer: CMSampleBuffer) {
        if let format = CMSampleBufferGetFormatDescription(sampleBuffer) { latestAudioFormat = format }
        guard state == .recording, let writer, writer.status == .writing, let input = audioInput else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard CMTimeCompare(pts, startTime) >= 0 else { return }
        if input.isReadyForMoreMediaData { input.append(sampleBuffer) }
    }

    func stop(completion: @escaping (URL?, String?) -> Void) {
        guard state == .recording, let writer else {
            let message = failure ?? (state == .armed ? "The recording was too short." : nil)
            reset()
            completion(nil, message)
            return
        }
        videoInput?.markAsFinished()
        audioInput?.markAsFinished()
        let outputURL = writer.outputURL
        writer.finishWriting {
            if writer.status == .completed {
                completion(outputURL, nil)
            } else {
                completion(nil, writer.error?.localizedDescription ?? "The recording failed.")
            }
        }
        reset()
    }

    private func reset() {
        writer = nil
        videoInput = nil
        audioInput = nil
        startTime = .invalid
        state = .idle
    }

    // MARK: - Writer setup

    private func setUpWriter(firstFrame sampleBuffer: CMSampleBuffer) {
        guard let url, let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        do {
            let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
            let width = CVPixelBufferGetWidth(pixelBuffer)
            let height = CVPixelBufferGetHeight(pixelBuffer)
            let pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer)
            let tenBit = pixelFormat == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
                || pixelFormat == kCVPixelFormatType_420YpCbCr10BiPlanarFullRange
            let colors = Self.colorProperties(of: pixelBuffer)
            let isHLG = (colors?[AVVideoTransferFunctionKey] as? String) == AVVideoTransferFunction_ITU_R_2100_HLG

            var choice = codec
            if tenBit && choice == .h264 { choice = .hevc }   // H.264 has no 10-bit hardware encoder

            var settings = Self.videoSettings(codec: choice, width: width, height: height,
                                              tenBit: tenBit, hdr: isHLG, colors: colors)
            if !writer.canApply(outputSettings: settings, forMediaType: .video) {
                choice = .hevc
                settings = Self.videoSettings(codec: .hevc, width: width, height: height,
                                              tenBit: tenBit, hdr: isHLG, colors: colors)
                if !writer.canApply(outputSettings: settings, forMediaType: .video) {
                    settings = Self.videoSettings(codec: .hevc, width: width, height: height,
                                                  tenBit: tenBit, hdr: isHLG, colors: nil)
                }
            }

            let video = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            video.expectsMediaDataInRealTime = true
            video.transform = CGAffineTransform(rotationAngle: rotationDegrees * .pi / 180)
            guard writer.canAdd(video) else { throw RecorderError.cannotAddInput }
            writer.add(video)

            var audio: AVAssetWriterInput?
            if includeAudio {
                let audioSettings = Self.audioSettings(for: latestAudioFormat, uncompressed: choice == .proRes)
                let input = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
                input.expectsMediaDataInRealTime = true
                if writer.canAdd(input) {
                    writer.add(input)
                    audio = input
                }
            }

            guard writer.startWriting() else { throw writer.error ?? RecorderError.cannotStart }
            let start = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            writer.startSession(atSourceTime: start)

            self.writer = writer
            self.videoInput = video
            self.audioInput = audio
            self.startTime = start
            self.state = .recording
        } catch {
            failure = error.localizedDescription
            reset()
        }
    }

    private static func videoSettings(codec: VideoCodecChoice, width: Int, height: Int,
                                      tenBit: Bool, hdr: Bool, colors: [String: Any]?) -> [String: Any] {
        var settings: [String: Any] = [
            AVVideoCodecKey: codec.avCodec,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ]
        if let colors { settings[AVVideoColorPropertiesKey] = colors }
        let is4K = width * height >= 3840 * 2160
        let bitrate = codec.megabits(is4K: is4K, hdr: hdr) * 1_000_000
        switch codec {
        case .hevc:
            settings[AVVideoCompressionPropertiesKey] = [
                AVVideoAverageBitRateKey: bitrate,
                AVVideoExpectedSourceFrameRateKey: 30,
                AVVideoMaxKeyFrameIntervalKey: 30,
                AVVideoProfileLevelKey: (tenBit ? kVTProfileLevel_HEVC_Main10_AutoLevel
                                                : kVTProfileLevel_HEVC_Main_AutoLevel) as String,
            ] as [String: Any]
        case .h264:
            settings[AVVideoCompressionPropertiesKey] = [
                AVVideoAverageBitRateKey: bitrate,
                AVVideoExpectedSourceFrameRateKey: 30,
                AVVideoMaxKeyFrameIntervalKey: 30,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
                AVVideoH264EntropyModeKey: AVVideoH264EntropyModeCABAC,
            ] as [String: Any]
        case .proRes:
            break
        }
        return settings
    }

    /// Copies the camera's colour tags (primaries / transfer / matrix) so the file is tagged exactly like the
    /// frames were captured: BT.709 for SDR, BT.2020 + HLG for HDR. The CV and AVVideo string values are identical.
    private static func colorProperties(of pixelBuffer: CVPixelBuffer) -> [String: Any]? {
        guard let primaries = CVBufferCopyAttachment(pixelBuffer, kCVImageBufferColorPrimariesKey, nil) as? String,
              let transfer = CVBufferCopyAttachment(pixelBuffer, kCVImageBufferTransferFunctionKey, nil) as? String,
              let matrix = CVBufferCopyAttachment(pixelBuffer, kCVImageBufferYCbCrMatrixKey, nil) as? String
        else { return nil }
        return [
            AVVideoColorPrimariesKey: primaries,
            AVVideoTransferFunctionKey: transfer,
            AVVideoYCbCrMatrixKey: matrix,
        ]
    }

    private static func audioSettings(for format: CMFormatDescription?, uncompressed: Bool) -> [String: Any] {
        var sampleRate: Double = 48_000
        var channels = 1
        if let format, let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee {
            sampleRate = asbd.mSampleRate
            channels = Int(asbd.mChannelsPerFrame)
        }
        channels = max(1, min(channels, 2))
        if uncompressed {
            // 24-bit PCM alongside ProRes.
            return [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: channels,
                AVLinearPCMBitDepthKey: 24,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ]
        }
        return [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: channels == 2 ? 256_000 : 160_000,
        ]
    }

    private enum RecorderError: LocalizedError {
        case cannotAddInput, cannotStart
        var errorDescription: String? {
            switch self {
            case .cannotAddInput: return "The video encoder couldn't be set up."
            case .cannotStart: return "The recording couldn't start."
            }
        }
    }
}
