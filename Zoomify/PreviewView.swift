import Metal
import QuartzCore
import SwiftUI
import UIKit

/// A UIView backed by a CAMetalLayer that the renderer draws camera frames into (off the main thread).
final class MetalPreviewUIView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }
    private var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

    func configure(renderer: PreviewRenderer) {
        backgroundColor = .black
        metalLayer.device = renderer.device
        metalLayer.pixelFormat = .rgba16Float          // linear, extended range: wide colour + HDR headroom
        metalLayer.framebufferOnly = false             // the compute shader writes into the drawable
        metalLayer.wantsExtendedDynamicRangeContent = true
        metalLayer.colorspace = CGColorSpace(name: CGColorSpace.extendedLinearDisplayP3)
        metalLayer.maximumDrawableCount = 3
        metalLayer.isOpaque = true
        renderer.attach(layer: metalLayer)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? traitCollection.displayScale
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
    }
}

struct PreviewView: UIViewRepresentable {
    let renderer: PreviewRenderer

    func makeUIView(context: Context) -> MetalPreviewUIView {
        let view = MetalPreviewUIView()
        view.configure(renderer: renderer)
        return view
    }

    func updateUIView(_ uiView: MetalPreviewUIView, context: Context) {}
}
