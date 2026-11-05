package com.zoomify.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.SessionConfig
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.zoomify.camera.ui.presetLabel
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class LastCapture(val uri: Uri, val isVideo: Boolean)

enum class VideoProfile(val label: String) { STANDARD("Standard"), HDR("HDR 10-bit"), LOG("Zoomify Log") }
enum class Stabilization(val label: String) { OFF("Off"), STANDARD("Standard"), HORIZON_LOCK("Horizon lock") }
enum class LutTarget(val label: String) { PREVIEW("Preview only"), ALL("Preview + captures") }

/**
 * Owns CameraX and implements Velora Pro's behaviour natively. All public methods run on the main thread;
 * Compose observes the state properties directly.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val activity: ComponentActivity) {

    val previewView = PreviewView(activity).apply {
        // Android 13+: TextureView, so the Liquid Glass RenderEffect can refract the camera image.
        implementationMode = if (android.os.Build.VERSION.SDK_INT >= 33) PreviewView.ImplementationMode.COMPATIBLE
        else PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val zoomUi = ZoomUiState()

    // region UI state

    var mode by mutableStateOf(CaptureMode.PHOTO); private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK); private set
    var isRecording by mutableStateOf(false); private set
    var recordingStartedAt by mutableLongStateOf(0L); private set
    var photoBusy by mutableStateOf(false); private set
    var flashWhite by mutableStateOf(false); private set
    var countdown by mutableStateOf<Int?>(null); private set
    var thumbnail by mutableStateOf<Bitmap?>(null); private set
    var lastCapture by mutableStateOf<LastCapture?>(null); private set
    var focusPoint by mutableStateOf<Offset?>(null); private set
    var freezeFrame by mutableStateOf<Bitmap?>(null); private set
    var toast by mutableStateOf<String?>(null)
    var cameraStatus by mutableStateOf("READY"); private set
    var sourceInfo by mutableStateOf("LIVE SOURCE"); private set
    var engineStatus by mutableStateOf("GPU MOTION ENGINE"); private set

    var timerSeconds by mutableIntStateOf(0); private set
    var gridOn by mutableStateOf(false); private set
    var torchOn by mutableStateOf(false); private set

    // Settings (Velora's "Cinematic controls" + additions).
    var blurStrength by mutableFloatStateOf(0.65f); private set
    var smoothness by mutableFloatStateOf(0.65f); private set
    var exposureEv by mutableFloatStateOf(0f); private set
    var videoResolution by mutableStateOf(VideoResolution.UHD); private set
    var bitrate by mutableStateOf(VideoBitrate.B50); private set
    var videoPath by mutableStateOf(VideoPath.CINEMATIC); private set
    var videoProfile by mutableStateOf(VideoProfile.STANDARD); private set
    var stabilization by mutableStateOf(Stabilization.STANDARD); private set
    var colorLook by mutableStateOf(ColorLook.NATURAL); private set
    var lutId by mutableStateOf<String?>(null); private set
    var lutTarget by mutableStateOf(LutTarget.ALL); private set
    var logViewAssist by mutableStateOf(true); private set
    var photoPipeline by mutableStateOf(PhotoPipeline.PROCESSED); private set
    var photoFormat by mutableStateOf(PhotoFormat.JPEG); private set
    var availablePhotoFormats by mutableStateOf(PhotoFormat.entries.toList()); private set
    /** Formats the phone can't produce on any back camera (shown greyed out, with [rawStatus] as the reason). */
    var unsupportedPhotoFormats by mutableStateOf(emptySet<PhotoFormat>()); private set
    /** Plain-language RAW diagnosis for the settings sheet. */
    var rawStatus by mutableStateOf(""); private set
    var previewPerformance by mutableStateOf(PreviewPerformance.AUTO); private set
    var sharpening by mutableStateOf(Sharpening.NATURAL); private set
    var hardwareAssist by mutableStateOf(true); private set
    var mirrorFront by mutableStateOf(true); private set
    var micEnabled by mutableStateOf(true); private set

    // Capabilities of the current camera.
    var hdrSupported by mutableStateOf(false); private set
    var logHardware by mutableStateOf(false); private set
    var stabilizationSupported by mutableStateOf(false); private set
    var hardwareZoomRange by mutableStateOf("1.0–1.0×"); private set
    var effectivePath by mutableStateOf(VideoPath.CINEMATIC); private set
    var frameRate by mutableIntStateOf(30); private set
    var fps60Supported by mutableStateOf(false); private set
    var activeFps by mutableIntStateOf(30); private set

    // endregion

    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val processor = ZoomBlurProcessor()
    private val zoomAnimator = ZoomAnimator { zoom, velocity -> onZoomFrame(zoom, velocity) }
    private val horizonLock = HorizonLock(activity) { angle, crop ->
        processor.horizonAngle = angle
        processor.horizonCrop = crop
    }
    private val vibrator: Vibrator? = activity.getSystemService(Vibrator::class.java)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var started = false

    private var hardwareMinZoom = 1f
    private var hardwareMaxZoom = 1f
    private var mainMaxZoom = 1f
    private var effectActive = false
    private var digitalZoomAllowed = false
    private var currentDigitalZoom = 1f
    private var lastHardwareZoom = -1f
    private var targetRotation = Surface.ROTATION_0
    @Volatile private var lastPhysicalCameraId: String? = null

    // Separate ultra-wide camera (phones that don't put the ultra-wide inside the main logical camera).
    /** Back cameras that aren't fused into the main logical camera: an ultra-wide (< 1x) and/or telephotos (> 1x). */
    private data class SeparateLens(val id: String, val ratio: Float)
    private var separateLenses: List<SeparateLens> = emptyList()
    /** Native zoom of every telephoto, fused or separate (shown as presets). */
    private var teleRatios: List<Float> = emptyList()
    /** The separate lens currently bound, or null for the main camera. */
    private var activeLens: SeparateLens? = null
    private var mainMinZoom = 1f
    private var lensHopInProgress = false

    private var focusJob: Job? = null
    private var pinchStartZoom = 1f

    private val lensSwitchCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
        ) {
            val id = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return
            val previous = lastPhysicalCameraId
            if (id != previous) {
                lastPhysicalCameraId = id
                if (previous != null) processor.triggerLensSwitch()
            }
        }
    }

    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation == targetRotation || isRecording) return
            targetRotation = rotation
            horizonLock.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        }
    }

    // region Lifecycle

    fun start() {
        if (started) return
        started = true
        orientationListener.enable()
        zoomAnimator.smoothness = smoothness.toDouble()
        processor.blurStrength = blurStrength
        processor.look = colorLook
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                provider = future.get()
                bind(keepZoom = false)
                toast = "Camera ready · 50× zoom unlocked"
            } catch (e: Exception) {
                toast = "Couldn't open the camera: ${e.message}"
            }
        }, mainExecutor)
        activity.lifecycleScope.launch { statusLoop() }
    }

    fun stopRecordingIfNeeded() {
        if (isRecording) stopRecording()
    }

    fun release() {
        orientationListener.disable()
        horizonLock.stop()
        zoomAnimator.stop()
        recording?.stop()
        provider?.unbindAll()
        processor.release()
        ioExecutor.shutdown()
    }

    /** Velora's status pills: camera + source resolution, and the measured preview frame rate. */
    private suspend fun statusLoop() {
        var lastFrames = processor.previewFrames
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(1800)
            val frames = processor.previewFrames
            val now = SystemClock.elapsedRealtime()
            val fps = ((frames - lastFrames) * 1000.0 / max(1L, now - lastTime)).roundToInt()
            lastFrames = frames
            lastTime = now
            val size = (if (mode == CaptureMode.VIDEO) videoCapture?.resolutionInfo?.resolution else null)
                ?: preview?.resolutionInfo?.resolution
            val label = when {
                size == null -> ""
                max(size.width, size.height) >= 3800 -> "4K"
                size.width.toLong() * size.height >= 1_800_000 -> "FHD"
                size.width.toLong() * size.height >= 850_000 -> "HD"
                else -> "SD"
            }
            val lens = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "REAR"
            val fpsLabel = if (mode == CaptureMode.VIDEO) activeFps else 30
            cameraStatus = "$lens · $label $fpsLabel FPS"
            sourceInfo = if (size != null) "LIVE ${size.width} × ${size.height} · $fpsLabel FPS" else "LIVE SOURCE"
            engineStatus = if (effectActive) "PREVIEW $fps FPS" else "DIRECT SENSOR"
        }
    }

    // endregion

    // region Binding

    private fun bind(keepZoom: Boolean) {
        val provider = provider ?: return
        if (isRecording) return
        val previousZoom = zoomAnimator.targetZoom
        val front = lensFacing == CameraSelector.LENS_FACING_FRONT
        val mainSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(mainSelector)) {
            toast = "This camera isn't available."
            return
        }
        if (!front) findSeparateLenses(provider, provider.getCameraInfo(mainSelector)) else {
            separateLenses = emptyList()
            teleRatios = emptyList()
        }
        if (front || activeLens !in separateLenses) activeLens = null

        // RAW: the default (logical) back camera often doesn't offer RAW to apps even when one of the phone's
        // individual cameras does, so look across all of them and use the best one for RAW modes.
        val wantsRaw = mode == CaptureMode.PHOTO && photoFormat.isRaw
        val mainInfo = provider.getCameraInfo(mainSelector)
        val mainHasRaw = hasRaw(mainInfo)
        val rawCameraId = if (front) null else if (mainHasRaw) Camera2CameraInfo.from(mainInfo).cameraId
        else findRawCamera(provider, mainInfo)
        rawStatus = when {
            front -> if (mainHasRaw) "RAW is available on the front camera." else "The front camera doesn't offer RAW."
            mainHasRaw -> "RAW is available on the main camera."
            rawCameraId != null -> "RAW is available on camera $rawCameraId; RAW modes switch to it automatically " +
                "(zoom is limited to that lens)."
            else -> "This phone doesn't give RAW to other apps on any camera, so only JPEG, Max res and Ultra HDR " +
                "can be saved. Many mid-range Galaxy phones work this way."
        }
        val useRawCamera = wantsRaw && !front && !mainHasRaw && rawCameraId != null
        if (useRawCamera) activeLens = null
        val lens = activeLens

        val selector = when {
            useRawCamera -> CameraSelector.Builder().addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == rawCameraId }
            }.build()
            lens != null -> CameraSelector.Builder().addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == lens.id }
            }.build()
            else -> mainSelector
        }
        val cameraInfo = provider.getCameraInfo(selector)
        val c2 = Camera2CameraInfo.from(cameraInfo)

        val isVideo = mode == CaptureMode.VIDEO
        val modes = c2.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        val curvePoints = c2.getCameraCharacteristic(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
        logHardware = modes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true && curvePoints >= 32

        val videoCaps = Recorder.getVideoCapabilities(cameraInfo)
        hdrSupported = DynamicRange.HLG_10_BIT in videoCaps.supportedDynamicRanges
        stabilizationSupported = videoCaps.isStabilizationSupported
        val hdr = isVideo && videoProfile == VideoProfile.HDR && hdrSupported
        val log = isVideo && videoProfile == VideoProfile.LOG
        val hardwareLog = log && logHardware
        val horizon = isVideo && stabilization == Stabilization.HORIZON_LOCK && !hdr && horizonLock.available
        // Effects need the 8-bit GL path; emulated Log and Horizon lock need it for the recording too.
        effectivePath = when {
            !isVideo -> VideoPath.CINEMATIC
            hdr -> VideoPath.SENSOR_HQ
            (log && !logHardware) || horizon -> VideoPath.CINEMATIC
            else -> videoPath
        }

        // Preview: 16:9 like Velora, sized by "Preview performance".
        val previewBuilder = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(previewPerformance.width, previewPerformance.height),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    )
                )
                .build()
        )
        configureRepeating(Camera2Interop.Extender(previewBuilder), hardwareLog, curvePoints)
        val newPreview = previewBuilder.build()
        newPreview.setSurfaceProvider(previewView.surfaceProvider)
        preview = newPreview

        val useCases = mutableListOf<UseCase>(newPreview)
        var effectTargets: Int? = CameraEffect.PREVIEW
        imageCapture = null
        videoCapture = null

        if (!isVideo) {
            val formats = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats
            val anyRawJpeg = rawCameraId != null || ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats
            val anyRaw = rawCameraId != null || ImageCapture.OUTPUT_FORMAT_RAW in formats
            unsupportedPhotoFormats = buildSet {
                if (ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR !in formats) add(PhotoFormat.ULTRA_HDR)
                if (!anyRawJpeg) add(PhotoFormat.RAW)
                if (!anyRaw) add(PhotoFormat.RAW_ONLY)
            }
            if (photoFormat in unsupportedPhotoFormats ||
                (photoFormat == PhotoFormat.RAW && ImageCapture.OUTPUT_FORMAT_RAW_JPEG !in formats) ||
                (photoFormat == PhotoFormat.RAW_ONLY && ImageCapture.OUTPUT_FORMAT_RAW !in formats) ||
                (photoFormat == PhotoFormat.ULTRA_HDR && ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR !in formats)
            ) {
                toast = "${photoFormat.label} isn't available on this camera · saving JPEG"
                photoFormat = PhotoFormat.JPEG
            }
            val fullSensor = photoFormat == PhotoFormat.HIGH_RES || photoFormat == PhotoFormat.RAW ||
                photoFormat == PhotoFormat.RAW_ONLY
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    if (fullSensor) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                )
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .apply {
                    if (photoFormat == PhotoFormat.HIGH_RES) {
                        setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    }
                }
                .build()
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setTargetRotation(targetRotation)
                .setResolutionSelector(resolution)
            when (photoFormat) {
                PhotoFormat.ULTRA_HDR -> captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                PhotoFormat.RAW -> captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                PhotoFormat.RAW_ONLY -> captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                else -> Unit
            }
            val stillExtender = Camera2Interop.Extender(captureBuilder)
            when (sharpening) {
                Sharpening.OFF -> stillExtender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                Sharpening.NATURAL ->
                    stillExtender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                Sharpening.CAMERA -> Unit
            }
            val capture = captureBuilder.build()
            imageCapture = capture
            useCases += capture
            digitalZoomAllowed = photoFormat == PhotoFormat.JPEG
        } else {
            val range = if (hdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR
            val qualities = videoCaps.getSupportedQualities(range)
            val wanted = videoResolution.quality
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(wanted, FallbackStrategy.lowerQualityOrHigherThan(wanted)))
                .setTargetVideoEncodingBitRate(bitrate.mbps * 1_000_000)
                .build()
            if (wanted !in qualities) toast = "${videoResolution.label} isn't available here · using the closest size"
            val videoBuilder = VideoCapture.Builder(recorder)
                .setDynamicRange(range)
                .setTargetRotation(targetRotation)
                .setMirrorMode(if (mirrorFront) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
            if (stabilization != Stabilization.OFF && stabilizationSupported) {
                videoBuilder.setVideoStabilizationEnabled(true)
            }
            configureRepeating(Camera2Interop.Extender(videoBuilder), hardwareLog, curvePoints)
            val capture = videoBuilder.build()
            videoCapture = capture
            useCases += capture
            effectTargets = when {
                hdr -> null
                effectivePath == VideoPath.CINEMATIC -> CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
                else -> CameraEffect.PREVIEW
            }
            digitalZoomAllowed = effectivePath == VideoPath.CINEMATIC
        }

        val wantFps = if (isVideo) frameRate else null
        val bound = bindSession(provider, selector, cameraInfo, useCases, effectTargets, wantFps)
            ?: if (effectTargets != null) bindSession(provider, selector, cameraInfo, useCases, null, wantFps) else null
        if (isVideo && frameRate == 60 && activeFps != 60) {
            toast = "60 fps isn't supported at ${videoResolution.label} in this mode · recording at $activeFps fps"
        }
        if (bound == null) {
            toast = "Couldn't start the camera."
            return
        }
        if (!effectActive) digitalZoomAllowed = false
        updateGrades(horizon)
        if (horizon && effectActive) horizonLock.start() else horizonLock.stop()
        onCameraBound(bound, keepZoom, previousZoom)
    }

    /** Sharpening, Log tonemap and the lens-switch callback for the repeating (preview / video) stream. */
    private fun configureRepeating(extender: Camera2Interop.Extender<*>, hardwareLog: Boolean, curvePoints: Int) {
        extender.setSessionCaptureCallback(lensSwitchCallback)
        if (sharpening != Sharpening.CAMERA) {
            // The stock preview/video sharpening is what makes Velora look over-sharpened (and digital zoom
            // magnifies its halos), so it is switched off for the live stream.
            extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        if (hardwareLog) {
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, ZoomifyLog.tonemapCurve(curvePoints))
        }
    }

    /**
     * Binds with CameraX 1.5's SessionConfig. For video, the frame rate is only set to a range the camera reports
     * as supported for this exact combination (resolution, effects, stabilisation), so 60 fps is offered only
     * where it really works, and the rate is then guaranteed rather than a hint.
     */
    private fun bindSession(
        provider: ProcessCameraProvider, selector: CameraSelector, cameraInfo: CameraInfo,
        useCases: List<UseCase>, effectTargets: Int?, wantFps: Int?,
    ): Camera? = try {
        val effects: List<CameraEffect> =
            if (effectTargets != null) listOf(ZoomBlurEffect(effectTargets, processor)) else emptyList()
        val probe = SessionConfig(useCases = useCases, effects = effects)
        val supported = if (wantFps != null) cameraInfo.getSupportedFrameRateRanges(probe) else emptySet()
        fun fixed(fps: Int) = supported.any { it.lower == fps && it.upper == fps }
        val fps = when {
            wantFps == null -> null
            wantFps == 60 && fixed(60) -> 60
            fixed(30) -> 30
            else -> null
        }
        val config = if (fps != null) {
            SessionConfig(useCases = useCases, effects = effects, frameRateRange = Range(fps, fps))
        } else probe
        provider.unbindAll()
        val camera = provider.bindToLifecycle(activity, selector, config)
        effectActive = effects.isNotEmpty()
        if (wantFps != null) fps60Supported = fixed(60)
        activeFps = fps ?: 30
        camera
    } catch (e: Exception) {
        Log.w("Zoomify", "Binding failed (effect=${effectTargets != null})", e)
        null
    }

    private fun onCameraBound(camera: Camera, keepZoom: Boolean, previousZoom: Float) {
        zoomObserver?.let { observer -> this.camera?.cameraInfo?.zoomState?.removeObserver(observer) }
        this.camera = camera
        lastPhysicalCameraId = null
        lastHardwareZoom = -1f

        val observer = Observer<ZoomState> { state ->
            if (state.minZoomRatio != hardwareMinZoom || state.maxZoomRatio != hardwareMaxZoom) {
                updateZoomRange(state.minZoomRatio, state.maxZoomRatio)
            }
        }
        zoomObserver = observer
        camera.cameraInfo.zoomState.observe(activity, observer)
        val state = camera.cameraInfo.zoomState.value
        updateZoomRange(state?.minZoomRatio ?: 1f, state?.maxZoomRatio ?: 1f)
        zoomAnimator.snapTo(if (keepZoom) previousZoom else 1f)
        applyExposure()
        if (torchOn) camera.cameraControl.enableTorch(true)
    }

    private fun updateZoomRange(lo: Float, hi: Float) {
        hardwareMinZoom = lo
        hardwareMaxZoom = hi
        val lens = activeLens
        if (lens == null) {
            mainMinZoom = lo
            mainMaxZoom = hi
        }
        val base = lens?.ratio ?: 1f
        val ultraWide = separateLenses.firstOrNull { it.ratio < 1f }
        val longestTele = separateLenses.filter { it.ratio > 1f }.maxOfOrNull { it.ratio }
        val widest = when {
            !hardwareAssist -> 1f
            ultraWide != null -> ultraWide.ratio
            else -> min(1f, mainMinZoom)
        }
        // With a separate telephoto, the optical reach is its native zoom times its own (digital) zoom range.
        val opticalMax = max(mainMaxZoom, if (longestTele != null) longestTele * max(hi, 4f) else 0f)
        val longest = when {
            digitalZoomAllowed -> 50f
            hardwareAssist -> min(50f, opticalMax)
            else -> 1f
        }
        hardwareZoomRange = String.format(Locale.US, "%.1f–%.1f×", widest, min(50f, opticalMax)) +
            if (teleRatios.isNotEmpty()) " (telephoto ${teleRatios.joinToString(", ") { presetLabel(it) }})" else ""
        zoomUi.minZoom = widest
        zoomUi.maxZoom = longest
        zoomUi.presets = buildList {
            if (widest < 0.95f) add((widest * 10).roundToInt() / 10f)
            val values = (listOf(1f, 2f, 5f, 10f, 20f, 50f) + if (hardwareAssist) teleRatios else emptyList())
                .filter { it <= longest + 0.01f }
                .sorted()
            for (v in values) if (none { abs(ln(it / v)) < 0.08f }) add(v)
        }
        zoomAnimator.setRange(widest, longest)
    }

    /**
     * Works out the phone's lenses. Lenses fused into the main logical camera are reached by plain zoom (the
     * smoothest path: the camera switches lenses itself). Lenses exposed only as separate cameras (common on
     * Samsung) get a freeze-frame lens hop. Phones without an ultra-wide or telephoto simply don't get one.
     */
    private fun findSeparateLenses(provider: ProcessCameraProvider, main: CameraInfo) {
        val mainC2 = Camera2CameraInfo.from(main)
        val mainId = mainC2.cameraId
        val mainMin = mainC2.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        val mainMetric = fovMetric(main)
        if (mainMetric == null) {
            separateLenses = emptyList()
            teleRatios = emptyList()
            return
        }

        // Telephotos inside the main logical camera (reached by zooming the main camera).
        val fusedTele = mutableListOf<Float>()
        try {
            val manager = activity.getSystemService(android.hardware.camera2.CameraManager::class.java)
            for (id in manager.getCameraCharacteristics(mainId).physicalCameraIds) {
                val ratio = (fovMetric(manager.getCameraCharacteristics(id)) ?: continue) / mainMetric
                if (ratio > 1.6f) fusedTele += ratio
            }
        } catch (e: Exception) {
            // Physical cameras hidden: fall back to the separate-camera scan below.
        }

        var ultraWide: SeparateLens? = null
        val teles = mutableListOf<SeparateLens>()
        for (info in provider.availableCameraInfos) {
            if (info.lensFacing != CameraSelector.LENS_FACING_BACK) continue
            val id = Camera2CameraInfo.from(info).cameraId
            if (id == mainId) continue
            val ratio = (fovMetric(info) ?: continue) / mainMetric
            when {
                ratio < 0.8f && mainMin >= 0.95f && (ultraWide == null || ratio < ultraWide.ratio) ->
                    ultraWide = SeparateLens(id, ratio)
                ratio > 1.6f && fusedTele.none { abs(ln(it / ratio)) < 0.1f } &&
                    teles.none { abs(ln(it.ratio / ratio)) < 0.1f } -> teles += SeparateLens(id, ratio)
            }
        }
        separateLenses = listOfNotNull(ultraWide) + teles.sortedBy { it.ratio }
        teleRatios = (fusedTele + teles.map { it.ratio }).map { (it * 10).roundToInt() / 10f }.distinct().sorted()
    }

    private fun hasRaw(info: CameraInfo): Boolean {
        val formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
        return ImageCapture.OUTPUT_FORMAT_RAW in formats || ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats
    }

    /** The back camera with RAW whose field of view is closest to the main camera's (i.e. the main sensor). */
    private fun findRawCamera(provider: ProcessCameraProvider, main: CameraInfo): String? {
        val mainMetric = fovMetric(main) ?: 1f
        return provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK && hasRaw(it) }
            .minByOrNull { info -> abs(ln((fovMetric(info) ?: mainMetric) / mainMetric)) }
            ?.let { Camera2CameraInfo.from(it).cameraId }
    }

    private fun fovMetric(info: CameraInfo): Float? {
        val c2 = Camera2CameraInfo.from(info)
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?: return null
        val sensor = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return if (sensor.width > 0f) focal / sensor.width else null
    }

    private fun fovMetric(chars: CameraCharacteristics): Float? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return if (sensor.width > 0f) focal / sensor.width else null
    }

    private fun updateGrades(horizon: Boolean) {
        val isVideo = mode == CaptureMode.VIDEO
        val log = isVideo && videoProfile == VideoProfile.LOG
        val hwLog = log && logHardware
        val hasLut = lutId != null
        processor.previewGrade = when {
            !log -> OutputGrade(look = true, lut = hasLut, horizon = horizon)
            logViewAssist -> OutputGrade(transform = if (hwLog) 1 else 0, look = false, lut = hasLut, horizon = horizon)
            else -> OutputGrade(transform = if (hwLog) 0 else 2, look = false, lut = false, horizon = horizon)
        }
        val bake = lutTarget == LutTarget.ALL && hasLut
        processor.videoGrade = when {
            !log -> OutputGrade(look = true, lut = bake, blurScale = 0.65f, horizon = horizon)
            bake -> OutputGrade(transform = if (hwLog) 1 else 0, look = false, lut = true, blurScale = 0.65f, horizon = horizon)
            else -> OutputGrade(transform = if (hwLog) 0 else 2, look = false, lut = false, blurScale = 0.65f, horizon = horizon)
        }
    }

    // endregion

    // region Zoom

    private fun onZoomFrame(zoom: Float, velocity: Float) {
        zoomUi.zoom = zoom
        zoomUi.target = zoomAnimator.targetZoom
        maybeHopLens()
        val base = activeLens?.ratio ?: 1f
        val hardware = if (hardwareAssist) (zoom / base).coerceIn(hardwareMinZoom, max(hardwareMinZoom, hardwareMaxZoom))
        else 1f
        val digital = if (effectActive && digitalZoomAllowed) max(1f, zoom / (hardware * base)) else 1f
        currentDigitalZoom = digital
        if (!lensHopInProgress && abs(hardware - lastHardwareZoom) > hardware * 0.0005f) {
            camera?.cameraControl?.setZoomRatio(hardware)
            lastHardwareZoom = hardware
        }
        processor.updateZoom(zoom, velocity, digital)
    }

    /** Which camera should serve this zoom: a separate telephoto past its native zoom, the ultra-wide below 1x. */
    private fun lensFor(zoom: Float): SeparateLens? {
        val current = activeLens
        for (tele in separateLenses.filter { it.ratio > 1f }.sortedByDescending { it.ratio }) {
            val threshold = if (current == tele) tele.ratio * 0.97f else tele.ratio   // hysteresis
            if (zoom >= threshold) return tele
        }
        val ultraWide = separateLenses.firstOrNull { it.ratio < 1f } ?: return null
        val threshold = if (current == ultraWide) 1.0f else 0.97f
        return if (zoom < threshold) ultraWide else null
    }

    /**
     * Phones whose ultra-wide / telephoto are separate cameras: hop to the right one as the zoom crosses its
     * native zoom. The jump is hidden behind a blurred freeze-frame that cross-fades into the new lens.
     */
    private fun maybeHopLens() {
        if (separateLenses.isEmpty() || lensHopInProgress || isRecording || !hardwareAssist) return
        val next = lensFor(zoomAnimator.targetZoom)
        if (next == activeLens) return
        lensHopInProgress = true
        freezeFrame = previewView.bitmap
        activeLens = next
        activity.lifecycleScope.launch {
            bind(keepZoom = true)
            val startFrames = processor.previewFrames
            val started = SystemClock.elapsedRealtime()
            while (processor.previewFrames < startFrames + 2 && SystemClock.elapsedRealtime() - started < 1500) delay(16)
            lensHopInProgress = false
            lastHardwareZoom = -1f
            onZoomFrame(zoomUi.zoom, 0f)
            freezeFrame = null
        }
    }

    /** Velora: presets, the dial and the keyboard all just set the target; the spring does the rest. */
    fun setZoom(value: Float) = zoomAnimator.animateTo(value)

    fun selectPreset(value: Float) {
        setZoom(value)
        buzz(7)
    }

    fun pinchBegan() {
        pinchStartZoom = zoomAnimator.targetZoom
    }

    /** Velora's pinch: start zoom x (finger distance ratio)^1.4. */
    fun pinchChanged(distanceRatio: Float) = setZoom(pinchStartZoom * Math.pow(distanceRatio.toDouble(), 1.4).toFloat())

    /** Velora's double tap: toggles between 1x and 2x. */
    fun doubleTap() {
        setZoom(if (zoomAnimator.targetZoom < 1.6f) 2f else 1f)
        buzz(7)
    }

    // endregion

    // region Settings

    fun switchMode(newMode: CaptureMode) {
        if (isRecording) return run { toast = "Stop recording first" }
        if (newMode == mode) return
        mode = newMode
        bind(keepZoom = true)
    }

    fun flipCamera() {
        if (isRecording) return run { toast = "Stop recording before switching cameras" }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        activeLens = null
        torchOn = false
        bind(keepZoom = false)
    }

    fun cycleTimer() {
        timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }
        toast = if (timerSeconds == 0) "Photo timer off" else "$timerSeconds-second photo timer"
    }

    fun toggleGrid() {
        gridOn = !gridOn
    }

    fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return run { toast = "Torch not supported by this camera" }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
    }

    fun updateBlurStrength(value: Float) {
        blurStrength = value
        processor.blurStrength = value
    }

    fun updateSmoothness(value: Float) {
        smoothness = value
        zoomAnimator.smoothness = value.toDouble()
    }

    fun updateExposure(value: Float) {
        exposureEv = (value * 10).roundToInt() / 10f
        applyExposure()
    }

    private fun applyExposure() {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f) return
        // Log is recorded 1 EV under to protect highlights (the Log -> Rec709 conversion brings it back).
        val offset = if (mode == CaptureMode.VIDEO && videoProfile == VideoProfile.LOG) ZoomifyLog.EXPOSURE_OFFSET_EV else 0f
        val index = ((exposureEv + offset) / step).roundToInt()
            .coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    private inline fun changeAndRebind(block: () -> Unit) {
        if (isRecording) return run { toast = "Stop recording first" }
        block()
        bind(keepZoom = true)
    }

    fun chooseResolution(value: VideoResolution) = changeAndRebind { videoResolution = value }
    fun chooseBitrate(value: VideoBitrate) = changeAndRebind { bitrate = value }
    fun chooseFrameRate(value: Int) = changeAndRebind { frameRate = value }
    fun chooseVideoPath(value: VideoPath) = changeAndRebind { videoPath = value }
    fun choosePhotoFormat(value: PhotoFormat) = changeAndRebind { photoFormat = value }
    fun choosePreviewPerformance(value: PreviewPerformance) = changeAndRebind { previewPerformance = value }
    fun chooseSharpening(value: Sharpening) = changeAndRebind { sharpening = value }
    fun chooseStabilization(value: Stabilization) = changeAndRebind {
        stabilization = value
        if (value == Stabilization.HORIZON_LOCK && !horizonLock.available) toast = "No motion sensor for horizon lock"
    }
    fun chooseVideoProfile(value: VideoProfile) = changeAndRebind {
        videoProfile = value
        if (value == VideoProfile.HDR && !hdrSupported) toast = "10-bit HDR isn't supported by this camera"
        if (value == VideoProfile.LOG && !logHardware) toast = "Log is emulated on this phone (no custom tone curve)"
    }

    fun toggleMirror() = changeAndRebind { mirrorFront = !mirrorFront }

    fun chooseLook(value: ColorLook) {
        colorLook = value
        processor.look = value
    }

    fun chooseLut(id: String?) {
        lutId = id
        processor.setLut(id?.let { LutLibrary.load(activity, it) })
        updateGrades(processor.previewGrade.horizon)
    }

    fun chooseLutTarget(value: LutTarget) {
        lutTarget = value
        updateGrades(processor.previewGrade.horizon)
    }

    fun toggleLogViewAssist() {
        logViewAssist = !logViewAssist
        updateGrades(processor.previewGrade.horizon)
    }

    fun exportLogLut() {
        ioExecutor.execute {
            val ok = LutLibrary.exportToDownloads(activity, LutLibrary.LOG_LUT_ID)
            mainExecutor.execute {
                toast = if (ok) "Saved ${LutLibrary.LOG_LUT_ID}.cube to Downloads/Zoomify" else "Couldn't save the LUT"
            }
        }
    }

    fun choosePhotoPipeline(value: PhotoPipeline) {
        photoPipeline = value
    }

    fun toggleHardwareAssist() {
        hardwareAssist = !hardwareAssist
        if (!hardwareAssist) {
            if (activeLens != null) {
                activeLens = null
                bind(keepZoom = false)
            }
            camera?.cameraControl?.setZoomRatio(1f)
            lastHardwareZoom = 1f
        }
        updateZoomRange(hardwareMinZoom, hardwareMaxZoom)
        zoomAnimator.animateTo(zoomAnimator.targetZoom)
        toast = if (hardwareAssist) "Hardware zoom on · lenses switch automatically" else "Smooth digital zoom on"
    }

    fun toggleMic() {
        micEnabled = !micEnabled
    }

    // endregion

    // region Focus

    fun focus(offset: Offset) {
        val camera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
        focusPoint = offset
        focusJob?.cancel()
        focusJob = activity.lifecycleScope.launch {
            delay(750)
            focusPoint = null
        }
    }

    // endregion

    // region Capture

    fun shutter() {
        when (mode) {
            CaptureMode.PHOTO -> takePhoto()
            CaptureMode.VIDEO -> if (isRecording) stopRecording() else startRecording()
        }
    }

    private fun buzz(ms: Long) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Haptics are optional.
        }
    }

    private fun newName(prefix: String): String =
        prefix + SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())

    private fun takePhoto() {
        if (photoBusy || imageCapture == null) return
        photoBusy = true
        activity.lifecycleScope.launch {
            for (n in timerSeconds downTo 1) {
                countdown = n
                delay(1000)
            }
            countdown = null
            captureNow()
        }
    }

    private fun captureNow() {
        val capture = imageCapture ?: run { photoBusy = false; return }
        flashWhite = true
        activity.lifecycleScope.launch { delay(110); flashWhite = false }
        buzz(20)
        val name = newName("Zoomify_Photo_")
        val digital = currentDigitalZoom
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT && mirrorFront
        }
        val lut = if (lutTarget == LutTarget.ALL) lutId?.let { LutLibrary.load(activity, it) } else null
        val look = if (photoPipeline == PhotoPipeline.PROCESSED) colorLook else ColorLook.NEUTRAL

        fun saved(callback: (ImageCapture.OutputFileResults) -> Unit) = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = callback(outputFileResults)
            override fun onError(exception: ImageCaptureException) = finishCapture(null, "Could not save photo: ${exception.message}")
        }

        when (photoFormat) {
            PhotoFormat.RAW -> {
                val remaining = AtomicInteger(2)
                val raw = MediaStoreHelper.imageOutputOptions(activity, name, "image/x-adobe-dng", metadata)
                val jpeg = MediaStoreHelper.imageOutputOptions(activity, name, "image/jpeg", metadata)
                capture.takePicture(raw, jpeg, ioExecutor, saved { results ->
                    if (remaining.decrementAndGet() == 0) finishCapture(results.savedUri, "Saved RAW 16-bit DNG + JPEG")
                })
            }
            PhotoFormat.RAW_ONLY -> {
                val raw = MediaStoreHelper.imageOutputOptions(activity, name, "image/x-adobe-dng", metadata)
                capture.takePicture(raw, ioExecutor, saved { finishCapture(it.savedUri, "Saved RAW 16-bit DNG") })
            }
            PhotoFormat.JPEG -> if (digital > 1.01f || look != ColorLook.NEUTRAL || lut != null) {
                // Full-resolution camera JPEG, then Velora's colour + digital zoom on the CPU at full resolution.
                val file = File(activity.cacheDir, "$name.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
                capture.takePicture(options, ioExecutor, saved {
                    val result = try {
                        MediaStoreHelper.saveProcessedJpeg(activity, file, digital, look, lut, name)
                    } catch (e: Throwable) {
                        null
                    }
                    file.delete()
                    if (result != null) finishCapture(result.first, "Saved high-quality JPEG · ${result.second}")
                    else finishCapture(null, "Could not process the photo")
                })
            } else {
                val options = MediaStoreHelper.imageOutputOptions(activity, name, "image/jpeg", metadata)
                capture.takePicture(options, ioExecutor, saved { finishCapture(it.savedUri, "Saved high-quality JPEG · sensor") })
            }
            else -> {
                val options = MediaStoreHelper.imageOutputOptions(activity, name, "image/jpeg", metadata)
                capture.takePicture(options, ioExecutor, saved { finishCapture(it.savedUri, "Saved ${photoFormat.label} photo") })
            }
        }
    }

    /** Called on the IO thread. */
    private fun finishCapture(uri: Uri?, message: String?) {
        val bitmap = uri?.let { MediaStoreHelper.loadThumbnail(activity, it) }
        mainExecutor.execute {
            photoBusy = false
            if (uri != null) lastCapture = LastCapture(uri, isVideo = false)
            if (bitmap != null) thumbnail = bitmap
            if (message != null) toast = message
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        horizonLock.targetRotation = targetRotation
        val pending = capture.output.prepareRecording(activity, MediaStoreHelper.videoOutputOptions(activity, newName("Zoomify_")))
        val hasMic = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val withAudio = if (micEnabled && hasMic) pending.withAudioEnabled() else pending
        if (micEnabled && !hasMic) toast = "Mic permission unavailable · silent recording"
        isRecording = true
        recordingStartedAt = SystemClock.elapsedRealtime()
        buzz(25)
        val size = capture.resolutionInfo?.resolution
        val sizeText = if (size != null) "${size.width}×${size.height}" else videoResolution.label
        toast = "${effectivePath.label} recording · $sizeText · $activeFps fps · ${bitrate.mbps} Mb/s target"
        recording = withAudio.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> recordingStartedAt = SystemClock.elapsedRealtime()
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recording = null
                    val uri = event.outputResults.outputUri
                    if (uri != Uri.EMPTY) {
                        ioExecutor.execute {
                            val bitmap = MediaStoreHelper.loadThumbnail(activity, uri)
                            mainExecutor.execute {
                                lastCapture = LastCapture(uri, isVideo = true)
                                if (bitmap != null) thumbnail = bitmap
                                toast = "Video saved $sizeText · MP4"
                            }
                        }
                    } else if (event.hasError()) {
                        toast = "Recording failed (error ${event.error})"
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        isRecording = false
        buzz(20)
    }

    fun openLastInGallery() {
        val last = lastCapture ?: return
        val intent = Intent(Intent.ACTION_VIEW, last.uri)
            .setDataAndType(last.uri, if (last.isVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast = "No gallery app found"
        }
    }

    // endregion
}
