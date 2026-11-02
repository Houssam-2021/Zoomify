import AVFoundation
import QuartzCore
import UIKit

/// Drives `videoZoomFactor` with a critically damped spring in log2 space, ticked by a 120 Hz display link.
///
/// Working in log space makes every doubling take the same time (0.5x -> 1x feels like 1x -> 2x and 25x -> 50x),
/// which is how the Camera app's zoom feels. A critically damped spring never overshoots, so it eases in and out
/// without bouncing, and lets pinches, dial drags and button taps blend into each other mid-flight.
@MainActor
final class ZoomController: NSObject {
    enum Motion {
        case snap   // jump immediately
        case tap    // lens button: ~0.4 s eased glide
        case pinch  // follow fingers with light smoothing
        case dial   // follow the dial almost 1:1
    }

    private weak var device: AVCaptureDevice?
    private(set) var capabilities = ZoomCapabilities()

    private var position: Double = 0   // log2(display zoom)
    private var velocity: Double = 0   // octaves per second
    private var target: Double = 0
    private var omega: Double = 15
    private var link: CADisplayLink?
    private var lastWrittenFactor: CGFloat = 0

    /// Called every tick with (display zoom, velocity in octaves per second).
    var onChange: ((CGFloat, CGFloat) -> Void)?

    var displayZoom: CGFloat { CGFloat(exp2(position)) }
    var targetZoom: CGFloat { CGFloat(exp2(target)) }

    func attach(device: AVCaptureDevice, capabilities: ZoomCapabilities, initialZoom: CGFloat) {
        stopLink()
        self.device = device
        self.capabilities = capabilities
        lastWrittenFactor = 0
        let zoom = clamp(initialZoom)
        position = log2(Double(zoom))
        target = position
        velocity = 0
        write(zoom)
        onChange?(zoom, 0)
    }

    func setZoom(_ zoom: CGFloat, motion: Motion) {
        let clamped = clamp(zoom)
        target = log2(Double(clamped))
        switch motion {
        case .snap:
            stopLink()
            position = target
            velocity = 0
            write(clamped)
            onChange?(clamped, 0)
            return
        case .tap:
            omega = 2 * .pi / 0.42
        case .pinch:
            omega = 2 * .pi / 0.14
        case .dial:
            omega = 2 * .pi / 0.10
        }
        startLink()
    }

    private func clamp(_ zoom: CGFloat) -> CGFloat {
        min(max(zoom, capabilities.minDisplay), capabilities.maxDisplay)
    }

    private func startLink() {
        guard link == nil else { return }
        let displayLink = CADisplayLink(target: self, selector: #selector(step(_:)))
        displayLink.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        displayLink.add(to: .main, forMode: .common)
        link = displayLink
    }

    private func stopLink() {
        link?.invalidate()
        link = nil
    }

    @objc private func step(_ displayLink: CADisplayLink) {
        let dt = min(max(displayLink.targetTimestamp - displayLink.timestamp, 1.0 / 240.0), 1.0 / 30.0)

        // Exact critically damped spring step (stable for any dt).
        let delta = position - target
        let temp = (velocity + omega * delta) * dt
        let decay = exp(-omega * dt)
        velocity = (velocity - omega * temp) * decay
        position = target + (delta + temp) * decay

        if abs(position - target) < 0.0008 && abs(velocity) < 0.01 {
            position = target
            velocity = 0
            stopLink()
        }
        let zoom = CGFloat(exp2(position))
        write(zoom)
        onChange?(zoom, CGFloat(velocity))
    }

    private func write(_ displayZoom: CGFloat) {
        guard let device else { return }
        let factor = min(max(displayZoom * capabilities.baseFactor, device.minAvailableVideoZoomFactor),
                         device.maxAvailableVideoZoomFactor)
        guard abs(factor - lastWrittenFactor) > factor * 0.0004 else { return }
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = factor
            device.unlockForConfiguration()
            lastWrittenFactor = factor
        } catch {
            print("Zoomify: zoom failed: \(error)")
        }
    }
}
