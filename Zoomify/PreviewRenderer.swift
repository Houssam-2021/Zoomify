import CoreVideo
import Metal
import QuartzCore
import UIKit

/// Must match `PreviewParams` in Shaders.metal field for field.
struct PreviewParams {
    var viewSize: SIMD2<Float> = .zero
    var cropScale: SIMD2<Float> = .one
    var cropOffset: SIMD2<Float> = .zero
    var blurAmount: Float = 0
    var historyMix: Float = 0
    var historyScale: Float = 1
    var codeScale: Float = 255
    var yOffset: Float = 0
    var yRange: Float = 255
    var cOffset: Float = 128
    var cRange: Float = 255
    var kr: Float = 0.2126
    var kb: Float = 0.0722
    var rotation: UInt32 = 90
    var mirror: UInt32 = 0
    var transfer: UInt32 = 0
    var sampleCount: UInt32 = 1
    var seed: Float = 0
}

/// Draws camera frames into a CAMetalLayer with a single compute pass that does:
///   YCbCr -> linear RGB (BT.709 / BT.2020, SDR or HLG HDR, colour-managed via the layer's colour space)
///   + a radial "zoom" motion blur whose strength follows the zoom speed
///   + a short temporal cross-fade that hides the perspective jump when the virtual camera switches lenses.
/// `render` is called on the capture data queue; the setters can be called from anywhere.
final class PreviewRenderer: @unchecked Sendable {
    let device: MTLDevice?
    private let commandQueue: MTLCommandQueue?
    private let pipeline: MTLComputePipelineState?
    private var textureCache: CVMetalTextureCache?

    // Shared state (guarded by `lock`).
    private let lock = NSLock()
    private var sharedLayer: CAMetalLayer?
    private var sharedZoom: Float = 1
    private var sharedVelocity: Float = 0
    private var sharedMirror = false
    private var sharedSwitchTime: CFTimeInterval = 0
    private var sharedResetHistory = true

    // Render-queue state.
    private var history: [MTLTexture] = []
    private var historyIndex = 0
    private var historyValid = false
    private var blur: Float = 0
    private var lastZoom: Float = 1
    private var lastColorSpace: String?
    private var frameCounter: UInt32 = 0

    init() {
        let device = MTLCreateSystemDefaultDevice()
        self.device = device
        commandQueue = device?.makeCommandQueue()
        if let device,
           let library = device.makeDefaultLibrary(),
           let function = library.makeFunction(name: "zoomBlurPreview") {
            pipeline = try? device.makeComputePipelineState(function: function)
        } else {
            pipeline = nil
        }
        if let device {
            var cache: CVMetalTextureCache?
            CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, device, nil, &cache)
            textureCache = cache
        }
    }

    // MARK: - Inputs

    func attach(layer: CAMetalLayer) {
        lock.lock(); sharedLayer = layer; lock.unlock()
    }

    /// `zoom` is the displayed zoom (0.5, 1, 2 ...); `velocity` is in octaves (doublings) per second.
    func updateZoom(_ zoom: CGFloat, velocity: CGFloat) {
        lock.lock()
        sharedZoom = Float(zoom)
        sharedVelocity = Float(velocity)
        lock.unlock()
    }

    func setMirrored(_ mirrored: Bool) {
        lock.lock(); sharedMirror = mirrored; lock.unlock()
    }

    func triggerLensSwitch() {
        lock.lock(); sharedSwitchTime = CACurrentMediaTime(); lock.unlock()
    }

    func invalidateHistory() {
        lock.lock(); sharedResetHistory = true; lock.unlock()
    }

    // MARK: - Rendering

    func render(pixelBuffer: CVPixelBuffer) {
        guard let device, let commandQueue, let pipeline, let cache = textureCache else { return }

        lock.lock()
        let layer = sharedLayer
        let zoom = sharedZoom
        let velocity = sharedVelocity
        let mirror = sharedMirror
        let switchTime = sharedSwitchTime
        let resetHistory = sharedResetHistory
        sharedResetHistory = false
        lock.unlock()

        guard let layer, CVPixelBufferGetPlaneCount(pixelBuffer) >= 2 else { return }
        if resetHistory { historyValid = false }

        var params = PreviewParams()
        let tenBit: Bool
        switch CVPixelBufferGetPixelFormatType(pixelBuffer) {
        case kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
            tenBit = false
            (params.codeScale, params.yOffset, params.yRange, params.cOffset, params.cRange) = (255, 0, 255, 128, 255)
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange:
            tenBit = false
            (params.codeScale, params.yOffset, params.yRange, params.cOffset, params.cRange) = (255, 16, 219, 128, 224)
        case kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange:
            // 10-bit samples sit in the top bits of 16-bit words, so unorm * 65535/64 gives the 10-bit code.
            tenBit = true
            (params.codeScale, params.yOffset, params.yRange, params.cOffset, params.cRange) = (65535.0 / 64.0, 64, 876, 512, 896)
        case kCVPixelFormatType_420YpCbCr10BiPlanarFullRange:
            tenBit = true
            (params.codeScale, params.yOffset, params.yRange, params.cOffset, params.cRange) = (65535.0 / 64.0, 0, 1023, 512, 1023)
        default:
            return
        }

        guard let luma = makeTexture(pixelBuffer, plane: 0, format: tenBit ? .r16Unorm : .r8Unorm, cache: cache),
              let chroma = makeTexture(pixelBuffer, plane: 1, format: tenBit ? .rg16Unorm : .rg8Unorm, cache: cache)
        else { return }

        // Colour tags written by the camera.
        let matrix = CVBufferCopyAttachment(pixelBuffer, kCVImageBufferYCbCrMatrixKey, nil) as? String
        let transfer = CVBufferCopyAttachment(pixelBuffer, kCVImageBufferTransferFunctionKey, nil) as? String
        let primaries = CVBufferCopyAttachment(pixelBuffer, kCVImageBufferColorPrimariesKey, nil) as? String
        if matrix == (kCVImageBufferYCbCrMatrix_ITU_R_2020 as String) {
            (params.kr, params.kb) = (0.2627, 0.0593)
        } else if matrix == (kCVImageBufferYCbCrMatrix_ITU_R_601_4 as String) {
            (params.kr, params.kb) = (0.299, 0.114)
        } else {
            (params.kr, params.kb) = (0.2126, 0.0722)
        }
        params.transfer = transfer == (kCVImageBufferTransferFunction_ITU_R_2100_HLG as String) ? 1 : 0

        // The shader outputs linear light, so tag the layer with the matching extended-linear colour space and
        // let Core Animation colour-manage it to the display (Display P3 / EDR on iPhone).
        let colorSpaceName: CFString
        if primaries == (kCVImageBufferColorPrimaries_ITU_R_2020 as String) {
            colorSpaceName = CGColorSpace.extendedLinearITUR_2020
        } else if primaries == (kCVImageBufferColorPrimaries_P3_D65 as String) {
            colorSpaceName = CGColorSpace.extendedLinearDisplayP3
        } else {
            colorSpaceName = CGColorSpace.extendedLinearSRGB
        }
        if lastColorSpace != colorSpaceName as String {
            lastColorSpace = colorSpaceName as String
            let colorSpace = CGColorSpace(name: colorSpaceName)
            DispatchQueue.main.async { layer.colorspace = colorSpace }
        }

        guard let drawable = layer.nextDrawable() else { return }
        let output = drawable.texture
        ensureHistory(width: output.width, height: output.height, device: device)
        guard history.count == 2 else { return }

        // Geometry: aspect-fill the rotated sensor image into the view.
        let bufferWidth = Float(CVPixelBufferGetWidth(pixelBuffer))
        let bufferHeight = Float(CVPixelBufferGetHeight(pixelBuffer))
        params.rotation = 90
        params.mirror = mirror ? 1 : 0
        params.viewSize = SIMD2(Float(output.width), Float(output.height))
        let imageAspect = bufferHeight / bufferWidth            // rotated to portrait
        let viewAspect = Float(output.width) / Float(output.height)
        if imageAspect > viewAspect {
            params.cropScale = SIMD2(viewAspect / imageAspect, 1)
        } else {
            params.cropScale = SIMD2(1, imageAspect / viewAspect)
        }
        params.cropOffset = (SIMD2<Float>(1, 1) - params.cropScale) * 0.5

        // Motion: radial blur follows zoom speed; lens switches get a quick blur + cross-fade pulse.
        let sinceSwitch = Float(CACurrentMediaTime() - switchTime)
        let pulse: Float = sinceSwitch >= 0 && sinceSwitch < 0.6 ? exp(-sinceSwitch / 0.14) : 0
        let speed = abs(velocity)
        let targetBlur = min(0.14, speed * 0.022) + pulse * 0.02
        blur += (targetBlur - blur) * (targetBlur > blur ? 0.6 : 0.35)
        if blur < 0.0015 { blur = 0 }
        params.blurAmount = blur
        params.sampleCount = blur > 0 ? 14 : 1
        let motionMix = min(0.4, speed * 0.07)
        params.historyMix = historyValid ? max(motionMix, pulse * 0.75) : 0
        params.historyScale = zoom > 0 ? lastZoom / zoom : 1
        lastZoom = zoom
        frameCounter &+= 1
        params.seed = Float(frameCounter % 64) * 0.618034

        guard let commandBuffer = commandQueue.makeCommandBuffer(),
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(luma.texture, index: 0)
        encoder.setTexture(chroma.texture, index: 1)
        encoder.setTexture(history[historyIndex], index: 2)
        encoder.setTexture(output, index: 3)
        encoder.setTexture(history[1 - historyIndex], index: 4)
        encoder.setBytes(&params, length: MemoryLayout<PreviewParams>.stride, index: 0)
        let width = pipeline.threadExecutionWidth
        let height = max(1, pipeline.maxTotalThreadsPerThreadgroup / width)
        encoder.dispatchThreads(MTLSize(width: output.width, height: output.height, depth: 1),
                                threadsPerThreadgroup: MTLSize(width: width, height: height, depth: 1))
        encoder.endEncoding()

        // Keep the CVMetalTextures (and thus the camera buffer) alive until the GPU is done.
        let lumaRef = luma.cvTexture
        let chromaRef = chroma.cvTexture
        commandBuffer.addCompletedHandler { _ in
            withExtendedLifetime((lumaRef, chromaRef)) {}
        }
        commandBuffer.present(drawable)
        commandBuffer.commit()

        historyIndex = 1 - historyIndex
        historyValid = true
    }

    private func ensureHistory(width: Int, height: Int, device: MTLDevice) {
        if let first = history.first, first.width == width, first.height == height { return }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float,
                                                                  width: width, height: height, mipmapped: false)
        descriptor.usage = [.shaderRead, .shaderWrite]
        descriptor.storageMode = .private
        history = (0..<2).compactMap { _ in device.makeTexture(descriptor: descriptor) }
        historyIndex = 0
        historyValid = false
    }

    private func makeTexture(_ pixelBuffer: CVPixelBuffer, plane: Int, format: MTLPixelFormat,
                             cache: CVMetalTextureCache) -> (texture: MTLTexture, cvTexture: CVMetalTexture)? {
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, plane)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, plane)
        var cvTexture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(kCFAllocatorDefault, cache, pixelBuffer, nil,
                                                               format, width, height, plane, &cvTexture)
        guard status == kCVReturnSuccess, let cvTexture, let texture = CVMetalTextureGetTexture(cvTexture) else {
            return nil
        }
        return (texture, cvTexture)
    }
}
