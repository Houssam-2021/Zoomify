package com.zoomify.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size

/**
 * Decides whether a camera can really record 60 fps at a given size, from the phone's own Camera2 capabilities
 * (not a guess): the auto-exposure frame-rate ranges, the minimum frame duration of the camera stream at that
 * size, and the hardware video encoder's limits. The rate is then confirmed at runtime by [FpsMeter].
 */
object FrameRateSupport {
    /** 1/60 s, with a little slack for rounding in the camera's reported durations. */
    const val FRAME_60_NS = 16_700_000L

    data class Check(val supported: Boolean, val aeRange: Range<Int>?, val reason: String)

    /** Plain width x height, so the decision logic runs in JVM unit tests. */
    data class Dim(val width: Int, val height: Int)

    /**
     * The AE range to request for 60 fps: a fixed [60, 60] if offered, else the 60-capped range with the highest
     * floor of at least 30 (never one that may drop to 15/24 fps). Null if the camera has no such range.
     */
    fun choose60(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? =
        ranges.filter { (lo, hi) -> hi == 60 && lo in 30..60 }.maxByOrNull { it.first }

    /** Pure decision, testable without a phone. */
    fun decide(
        aeRanges: List<Pair<Int, Int>>,
        streamSizes: List<Dim>,
        minFrameDurationNs: (Dim) -> Long,
        encoderSupports60: (Dim) -> Boolean,
        videoSize: Dim,
        previewSize: Dim,
    ): Pair<Pair<Int, Int>?, String> {
        val range = choose60(aeRanges)
            ?: return null to "the camera has no 60 fps auto-exposure mode (ranges: ${aeRanges.joinToString { "${it.first}-${it.second}" }})"
        val video = closest(streamSizes, videoSize) ?: return null to "no camera stream size for video"
        val preview = closest(streamSizes, previewSize) ?: return null to "no camera stream size for preview"
        for ((name, size) in listOf("video" to video, "preview" to preview)) {
            val d = minFrameDurationNs(size)
            if (d <= 0L || d > FRAME_60_NS) {
                return null to "the camera can't stream ${size.width}×${size.height} ($name) at 60 fps"
            }
        }
        if (!encoderSupports60(video)) return null to "the video encoder can't encode ${video.width}×${video.height} at 60 fps"
        return range to "Camera2: AE ${range.first}-${range.second} fps, ${video.width}×${video.height} stream and encoder OK"
    }

    /** The stream size CameraX would pick: the exact size if offered, else the closest one with the same aspect. */
    fun closest(sizes: List<Dim>, wanted: Dim): Dim? {
        if (sizes.isEmpty()) return null
        sizes.firstOrNull { it.width == wanted.width && it.height == wanted.height }?.let { return it }
        val aspect = wanted.width.toDouble() / wanted.height
        val sameAspect = sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - aspect) < 0.02 }
        val pool = sameAspect.ifEmpty { sizes }
        val area = wanted.width.toLong() * wanted.height
        return pool.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - area) }
    }

    fun check(context: Context, cameraId: String, videoSize: Size, previewSize: Size): Check = try {
        val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val ae = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .map { it.lower to it.upper }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val (range, reason) = decide(
            aeRanges = ae,
            streamSizes = sizes.map { Dim(it.width, it.height) },
            minFrameDurationNs = { d ->
                map?.getOutputMinFrameDuration(SurfaceTexture::class.java, Size(d.width, d.height)) ?: 0L
            },
            encoderSupports60 = ::avcEncoderSupports60,
            videoSize = Dim(videoSize.width, videoSize.height),
            previewSize = Dim(previewSize.width, previewSize.height),
        )
        Check(range != null, range?.let { Range(it.first, it.second) }, reason)
    } catch (e: Exception) {
        Check(false, null, "couldn't read the camera's capabilities (${e.message})")
    }

    /** CameraX's Recorder encodes H.264 by default. */
    private fun avcEncoderSupports60(size: Dim): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                runCatching {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    caps.areSizeAndRateSupported(size.width, size.height, 60.0) ||
                        caps.areSizeAndRateSupported(size.height, size.width, 60.0)
                }.getOrDefault(false)
        }
}

/**
 * Measures the real camera frame rate from capture timestamps (one per frame the sensor actually delivered),
 * so "60 fps" is only shown and kept when the camera genuinely produces 60 distinct frames per second.
 */
class FpsMeter(private val windowNs: Long = 1_000_000_000L) {
    private val stamps = LongArray(512)
    private var head = 0
    private var count = 0

    @Synchronized fun reset() { head = 0; count = 0 }

    @Synchronized fun add(timestampNs: Long) {
        if (count > 0 && timestampNs <= stamps[(head - 1 + stamps.size) % stamps.size]) return  // duplicate/out of order
        stamps[head] = timestampNs
        head = (head + 1) % stamps.size
        if (count < stamps.size) count++
    }

    /** Frames per second over the last [windowNs] of timestamps, or 0 if there isn't enough data yet. */
    @Synchronized fun fps(): Double {
        if (count < 2) return 0.0
        val newest = stamps[(head - 1 + stamps.size) % stamps.size]
        var n = 0
        var oldest = newest
        for (i in 1 until count) {
            val t = stamps[(head - 1 - i + 2 * stamps.size) % stamps.size]
            if (newest - t > windowNs) break
            oldest = t
            n++
        }
        if (n < 5 || newest == oldest) return 0.0
        return n * 1e9 / (newest - oldest)
    }
}
