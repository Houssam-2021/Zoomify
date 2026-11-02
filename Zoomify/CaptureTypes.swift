import AVFoundation
import UIKit

enum CaptureMode: String, CaseIterable, Identifiable {
    case video = "VIDEO"
    case photo = "PHOTO"
    var id: String { rawValue }
}

enum PhotoFormat: CaseIterable {
    /// 24 MP (or 12 MP) HEIF with Smart HDR / Deep Fusion: what the stock Camera app shoots by default.
    case heif
    /// Full-sensor (48 MP) HEIF where the hardware supports it.
    case heifMax
    /// 48 MP Apple ProRAW DNG: the highest-quality, least-processed file available.
    case proRAW

    var label: String {
        switch self {
        case .heif: return "HEIF"
        case .heifMax: return "HEIF MAX"
        case .proRAW: return "RAW MAX"
        }
    }
}

enum FlashSetting: CaseIterable {
    case off, auto, on

    var avMode: AVCaptureDevice.FlashMode {
        switch self {
        case .off: return .off
        case .auto: return .auto
        case .on: return .on
        }
    }

    var symbol: String {
        switch self {
        case .off: return "bolt.slash.fill"
        case .auto: return "bolt.badge.automatic.fill"
        case .on: return "bolt.fill"
        }
    }
}

enum VideoCodecChoice: CaseIterable {
    case hevc, h264, proRes

    var avCodec: AVVideoCodecType {
        switch self {
        case .hevc: return .hevc
        case .h264: return .h264
        case .proRes: return .proRes422HQ
        }
    }

    /// Target average bitrate in Mbit/s. Apple's Camera app records 4K30 HEVC at roughly 40-50 Mbit/s;
    /// these are about 2-3x higher. ProRes is intra-frame and has no bitrate target (~700 Mbit/s at 4K30).
    func megabits(is4K: Bool, hdr: Bool) -> Int {
        switch self {
        case .hevc: return is4K ? (hdr ? 120 : 100) : (hdr ? 50 : 40)
        case .h264: return is4K ? 150 : 60
        case .proRes: return 0
        }
    }

    func label(is4K: Bool, hdr: Bool) -> String {
        switch self {
        case .hevc: return "HEVC \(megabits(is4K: is4K, hdr: hdr))M"
        case .h264: return "H.264 \(megabits(is4K: is4K, hdr: hdr))M"
        case .proRes: return "ProRes HQ"
        }
    }
}

struct CaptureConfig: Equatable {
    var mode: CaptureMode = .photo
    var position: AVCaptureDevice.Position = .back
    var photoFormat: PhotoFormat = .heif
    var hdrVideo: Bool = true
}

/// What the session ended up with after a (re)configuration.
struct ConfigResult {
    let device: AVCaptureDevice
    let zoom: ZoomCapabilities
    let proRAWSupported: Bool
    let has48MP: Bool
    let hdrSupported: Bool
    let hdrActive: Bool
    let is4K: Bool
    /// Width / height of the landscape sensor buffers (4:3 for photo, 16:9 for video).
    let bufferAspect: CGFloat
}

/// Zoom range and the "lens buttons" for a camera.
///
/// On virtual multi-lens devices (`builtInTripleCamera`, `builtInDualWideCamera`) a `videoZoomFactor` of 1.0
/// is the ultra-wide lens, i.e. "0.5x". The first switch-over factor is where the wide lens takes over, so we
/// divide by it to get the numbers users expect (0.5x, 1x, 2x, 3x/5x ...).
struct ZoomCapabilities {
    var minFactor: CGFloat = 1
    var maxFactor: CGFloat = 1
    var baseFactor: CGFloat = 1
    var presets: [CGFloat] = [1]

    var minDisplay: CGFloat { minFactor / baseFactor }
    var maxDisplay: CGFloat { maxFactor / baseFactor }

    init() {}

    init(device: AVCaptureDevice, maxDisplayZoom: CGFloat = 50) {
        let switches = device.virtualDeviceSwitchOverVideoZoomFactors.map { CGFloat($0.doubleValue) }
        let hasUltraWide = device.deviceType == .builtInTripleCamera || device.deviceType == .builtInDualWideCamera
        baseFactor = hasUltraWide ? (switches.first ?? 2) : 1
        minFactor = device.minAvailableVideoZoomFactor
        maxFactor = max(minFactor, min(device.maxAvailableVideoZoomFactor, maxDisplayZoom * baseFactor))

        var list: [CGFloat] = [1]
        if hasUltraWide { list.append(minFactor / baseFactor) }
        // e.g. the 2x "optical-quality" crop of a 48 MP wide sensor.
        for factor in device.activeFormat.secondaryNativeResolutionZoomFactors {
            list.append(factor / baseFactor)
        }
        // Telephoto lens.
        if device.deviceType == .builtInTripleCamera, switches.count >= 2 {
            list.append(switches[1] / baseFactor)
        } else if device.deviceType == .builtInDualCamera, let tele = switches.first {
            list.append(tele / baseFactor)
        }

        let lo = minFactor / baseFactor - 0.01
        let hi = maxFactor / baseFactor + 0.01
        var cleaned: [CGFloat] = []
        for value in list.map({ ($0 * 10).rounded() / 10 }).sorted() where value >= lo && value <= hi {
            if let last = cleaned.last, abs(last - value) < 0.05 { continue }
            cleaned.append(value)
        }
        presets = cleaned.isEmpty ? [1] : cleaned
    }
}
