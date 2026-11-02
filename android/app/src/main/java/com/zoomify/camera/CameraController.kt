package com.zoomify.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Owns CameraX: binds the use cases for the current mode, drives the zoom, and captures photos and video.
 * All public methods are called on the main thread; Compose observes the state properties directly.
 */
class CameraController(private val activity: ComponentActivity) {

    val previewView = PreviewView(activity).apply {
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val zoomUi = ZoomUiState()

    var mode by mutableStateOf(CaptureMode.PHOTO); private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK); private set
    var flash by mutableStateOf(FlashSetting.OFF); private set
    var photoFormat by mutableStateOf(PhotoFormat.JPEG); private set
    var availablePhotoFormats by mutableStateOf(listOf(PhotoFormat.JPEG)); private set
    var hdrVideo by mutableStateOf(true); private set
    var hdrSupported by mutableStateOf(false); private set
    var hdrActive by mutableStateOf(false); private set
    var bitrate by mutableStateOf(VideoBitrate.HIGH); private set
    var videoQualityLabel by mutableStateOf("4K"); private set
    var isRecording by mutableStateOf(false); private set
    var recordingStartedAt by mutableLongStateOf(0L); private set
    var capturesInFlight by mutableIntStateOf(0); private set
    var shutterBlink by mutableStateOf(false); private set
    var thumbnail by mutableStateOf<Bitmap?>(null); private set
    var focusPoint by mutableStateOf<Offset?>(null); private set
    var toast by mutableStateOf<String?>(null)

    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val processor = ZoomBlurProcessor()
    private val zoomAnimator = ZoomAnimator { zoom, velocity -> onZoomFrame(zoom, velocity) }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastMediaUri: Uri? = null
    private var started = false

    private var hardwareMinZoom = 1f
    private var hardwareMaxZoom = 1f
    private var effectActive = false
    private var digitalZoomAllowed = false
    private var currentDigitalZoom = 1f
    private var lastHardwareZoom = -1f
    private var gestureStartZoom = 1f
    private var targetRotation = Surface.ROTATION_0
    @Volatile private var lastPhysicalCameraId: String? = null

    private var focusJob: Job? = null
    private var dialJob: Job? = null

    /** Watches which physical lens the logical camera is streaming from, to cross-fade lens switches. */
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

    /** The UI is locked to portrait like the iPhone camera, so rotate photos/videos from the sensor instead. */
    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation == targetRotation) return
            targetRotation = rotation
            imageCapture?.targetRotation = rotation
            if (!isRecording) videoCapture?.targetRotation = rotation
        }
    }

    // region Lifecycle

    fun start() {
        if (started) return
        started = true
        orientationListener.enable()
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                provider = future.get()
                bind(keepZoom = false)
            } catch (e: Exception) {
                toast = "Couldn't open the camera: ${e.message}"
            }
        }, mainExecutor)
    }

    fun stopRecordingIfNeeded() {
        if (isRecording) stopRecording()
    }

    fun release() {
        orientationListener.disable()
        zoomAnimator.stop()
        recording?.stop()
        provider?.unbindAll()
        processor.release()
        ioExecutor.shutdown()
    }

    // endregion

    // region Binding

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(keepZoom: Boolean) {
        val provider = provider ?: return
        if (isRecording) return
        val previousZoom = zoomAnimator.targetZoom
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            toast = "This camera isn't available."
            return
        }
        val cameraInfo = provider.getCameraInfo(selector)
        val aspect = if (mode == CaptureMode.PHOTO) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(lensSwitchCallback)
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val useCases = mutableListOf<UseCase>(preview)
        var effectTargets = CameraEffect.PREVIEW
        imageCapture = null
        videoCapture = null

        when (mode) {
            CaptureMode.PHOTO -> {
                val formats = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats
                availablePhotoFormats = buildList {
                    add(PhotoFormat.JPEG)
                    add(PhotoFormat.HIGH_RES)
                    if (ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in formats) add(PhotoFormat.ULTRA_HDR)
                    if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats) add(PhotoFormat.RAW)
                }
                if (photoFormat !in availablePhotoFormats) photoFormat = PhotoFormat.JPEG

                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .apply {
                        if (photoFormat == PhotoFormat.HIGH_RES) {
                            setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        }
                    }
                    .build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setFlashMode(flash.mode)
                    .setTargetRotation(targetRotation)
                    .setResolutionSelector(resolution)
                    .apply {
                        when (photoFormat) {
                            PhotoFormat.ULTRA_HDR -> setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                            PhotoFormat.RAW -> setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                            else -> Unit
                        }
                    }
                    .build()
                imageCapture = capture
                useCases += capture
                hdrActive = false
                digitalZoomAllowed = photoFormat == PhotoFormat.JPEG || photoFormat == PhotoFormat.HIGH_RES
            }

            CaptureMode.VIDEO -> {
                val capabilities = Recorder.getVideoCapabilities(cameraInfo)
                hdrSupported = DynamicRange.HLG_10_BIT in capabilities.supportedDynamicRanges
                val wantHdr = hdrVideo && hdrSupported
                val range = if (wantHdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR
                val qualities = capabilities.getSupportedQualities(range)
                val quality = listOf(Quality.UHD, Quality.FHD, Quality.HD).firstOrNull { it in qualities }
                    ?: Quality.HIGHEST
                videoQualityLabel = when (quality) {
                    Quality.UHD -> "4K"
                    Quality.FHD -> "1080p"
                    Quality.HD -> "720p"
                    else -> "HD"
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                    .setTargetVideoEncodingBitRate(bitrate.mbps * 1_000_000)
                    .build()
                val capture = VideoCapture.Builder(recorder)
                    .setDynamicRange(range)
                    .setTargetFrameRate(Range(30, 30))
                    .setTargetRotation(targetRotation)
                    .apply { if (capabilities.isStabilizationSupported) setVideoStabilizationEnabled(true) }
                    .build()
                videoCapture = capture
                useCases += capture
                hdrActive = wantHdr
                digitalZoomAllowed = !wantHdr
                effectTargets = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
            }
        }

        // The GL effect is 8-bit, so 10-bit HDR video goes straight from the camera to the encoder.
        val wantEffect = !hdrActive
        val bound = bindGroup(provider, selector, useCases, if (wantEffect) effectTargets else null)
            ?: if (wantEffect) bindGroup(provider, selector, useCases, null) else null
        if (bound == null) {
            toast = "Couldn't start the camera."
            return
        }
        onCameraBound(bound, keepZoom, previousZoom)
    }

    private fun bindGroup(
        provider: ProcessCameraProvider, selector: CameraSelector, useCases: List<UseCase>, effectTargets: Int?,
    ): Camera? = try {
        val group = UseCaseGroup.Builder()
        useCases.forEach { group.addUseCase(it) }
        if (effectTargets != null) group.addEffect(ZoomBlurEffect(effectTargets, processor))
        provider.unbindAll()
        val camera = provider.bindToLifecycle(activity, selector, group.build())
        effectActive = effectTargets != null
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
        if (!effectActive) digitalZoomAllowed = false

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
    }

    private fun updateZoomRange(lo: Float, hi: Float) {
        hardwareMinZoom = lo
        hardwareMaxZoom = hi
        // Up to 50x: the camera's own zoom first, then a GPU crop on top when the camera stops short of 50x.
        val maxZoom = if (digitalZoomAllowed) 50f else min(50f, hi)
        val info = camera?.cameraInfo
        zoomUi.presets = if (info != null) ZoomPresets.compute(activity, info, lo, maxZoom) else listOf(1f)
        zoomUi.minZoom = lo
        zoomUi.maxZoom = maxZoom
        zoomAnimator.setRange(lo, maxZoom)
    }

    // endregion

    // region Zoom

    private fun onZoomFrame(zoom: Float, velocity: Float) {
        zoomUi.zoom = zoom
        val hardware = zoom.coerceIn(hardwareMinZoom, max(hardwareMinZoom, hardwareMaxZoom))
        val digital = if (effectActive && digitalZoomAllowed) max(1f, zoom / hardware) else 1f
        currentDigitalZoom = digital
        if (abs(hardware - lastHardwareZoom) > hardware * 0.0005f) {
            camera?.cameraControl?.setZoomRatio(hardware)
            lastHardwareZoom = hardware
        }
        processor.updateZoom(zoom, velocity, digital)
    }

    fun selectPreset(value: Float) = zoomAnimator.animateTo(value, ZoomAnimator.Motion.TAP)

    fun pinchBy(scale: Float) = zoomAnimator.animateTo(zoomAnimator.targetZoom * scale, ZoomAnimator.Motion.PINCH)

    fun dialBegan() {
        gestureStartZoom = zoomAnimator.targetZoom
        dialJob?.cancel()
        zoomUi.dialVisible = true
    }

    /** Dragging left zooms in, one doubling every [DIAL_DP_PER_OCTAVE] dp. */
    fun dialChanged(totalDragDp: Float) {
        val octaves = -totalDragDp / DIAL_DP_PER_OCTAVE
        zoomAnimator.animateTo(gestureStartZoom * 2f.pow(octaves), ZoomAnimator.Motion.DIAL)
    }

    fun dialEnded() {
        dialJob?.cancel()
        dialJob = activity.lifecycleScope.launch {
            delay(1500)
            zoomUi.dialVisible = false
        }
    }

    // endregion

    // region Settings

    fun setMode(newMode: CaptureMode) {
        if (newMode == mode || isRecording) return
        mode = newMode
        bind(keepZoom = true)
    }

    fun flipCamera() {
        if (isRecording) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        bind(keepZoom = false)
    }

    fun cycleFlash() {
        val all = FlashSetting.entries
        flash = all[(flash.ordinal + 1) % all.size]
        imageCapture?.flashMode = flash.mode
    }

    fun cyclePhotoFormat() {
        val options = availablePhotoFormats
        val next = options[(options.indexOf(photoFormat) + 1).mod(options.size)]
        if (next == photoFormat) return
        photoFormat = next
        bind(keepZoom = true)
    }

    fun toggleHdr() {
        if (isRecording || !hdrSupported) return
        hdrVideo = !hdrVideo
        bind(keepZoom = true)
    }

    fun cycleBitrate() {
        if (isRecording) return
        val all = VideoBitrate.entries
        bitrate = all[(bitrate.ordinal + 1) % all.size]
        bind(keepZoom = true)
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
            delay(1200)
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

    private fun newName(): String =
        "Zoomify_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun takePhoto() {
        val capture = imageCapture ?: return
        capturesInFlight++
        blink()
        val name = newName()
        val digital = currentDigitalZoom

        when {
            photoFormat == PhotoFormat.RAW -> {
                val remaining = AtomicInteger(2)
                val raw = MediaStoreHelper.imageOutputOptions(activity, name, "image/x-adobe-dng")
                val jpeg = MediaStoreHelper.imageOutputOptions(activity, name, "image/jpeg")
                capture.takePicture(raw, jpeg, ioExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val uri = outputFileResults.savedUri
                        if (remaining.decrementAndGet() == 0) finishCapture(uri, null)
                        else if (uri != null) loadThumbnailAsync(uri)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finishCapture(null, exception.message)
                    }
                })
            }

            digital > 1.01f -> {
                // Past the camera's own zoom limit: capture full quality, then crop.
                val file = File(activity.cacheDir, "$name.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val uri = try {
                            MediaStoreHelper.saveCroppedJpeg(activity, file, digital, name)
                        } catch (e: Exception) {
                            null
                        }
                        file.delete()
                        finishCapture(uri, if (uri == null) "Couldn't save the photo." else null)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finishCapture(null, exception.message)
                    }
                })
            }

            else -> {
                val options = MediaStoreHelper.imageOutputOptions(activity, name, "image/jpeg")
                capture.takePicture(options, ioExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        finishCapture(outputFileResults.savedUri, null)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        finishCapture(null, exception.message)
                    }
                })
            }
        }
    }

    /** Called on the IO thread. */
    private fun finishCapture(uri: Uri?, error: String?) {
        val bitmap = uri?.let { MediaStoreHelper.loadThumbnail(activity, it) }
        mainExecutor.execute {
            capturesInFlight = max(0, capturesInFlight - 1)
            if (uri != null) lastMediaUri = uri
            if (bitmap != null) thumbnail = bitmap
            if (error != null) toast = error
        }
    }

    private fun loadThumbnailAsync(uri: Uri) {
        ioExecutor.execute {
            val bitmap = MediaStoreHelper.loadThumbnail(activity, uri)
            mainExecutor.execute {
                lastMediaUri = uri
                if (bitmap != null) thumbnail = bitmap
            }
        }
    }

    private fun blink() {
        shutterBlink = true
        activity.lifecycleScope.launch {
            delay(90)
            shutterBlink = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        capture.targetRotation = targetRotation
        val pending = capture.output.prepareRecording(activity, MediaStoreHelper.videoOutputOptions(activity, newName()))
        val hasMic = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val withAudio = if (hasMic) pending.withAudioEnabled() else pending
        isRecording = true
        recordingStartedAt = SystemClock.elapsedRealtime()
        recording = withAudio.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> recordingStartedAt = SystemClock.elapsedRealtime()
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    recording = null
                    val uri = event.outputResults.outputUri
                    if (uri != Uri.EMPTY) {
                        loadThumbnailAsync(uri)
                    } else if (event.hasError()) {
                        toast = "Recording failed (error ${event.error})."
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        isRecording = false
    }

    fun openLastMedia() {
        val uri = lastMediaUri ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast = "No gallery app found."
        }
    }

    // endregion
}
