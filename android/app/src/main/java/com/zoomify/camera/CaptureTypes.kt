package com.zoomify.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.camera.video.Quality

enum class CaptureMode(val label: String) { PHOTO("PHOTO"), VIDEO("VIDEO") }

/** Velora "Color science". Same maths as Velora's shader (saturation, warm tint, highlight shaping). */
enum class ColorLook(val label: String, val saturation: Float, val shaped: Boolean) {
    NEUTRAL("Neutral", 1.0f, false),
    NATURAL("Natural", 1.065f, true),
    VIVID("Vivid", 1.19f, true),
}

/** Velora "Video recording path". */
enum class VideoPath(val label: String) {
    /** Zoom (incl. digital zoom past the lens), colour look and motion blur are baked into the video. */
    CINEMATIC("Cinematic"),
    /** The camera's own stream, untouched. Allows 10-bit HDR. Zoom limited to the camera's real zoom. */
    SENSOR_HQ("Sensor HQ"),
}

/** Velora "Photo quality". */
enum class PhotoPipeline(val label: String) {
    /** Full-resolution camera JPEG with the colour look and digital zoom applied. */
    PROCESSED("Full-res colors"),
    /** The camera's original JPEG (digital zoom is still cropped to match the viewfinder). */
    NATIVE("Native JPEG"),
}

/** Extra still formats (not in Velora). Anything but JPEG is saved natively. */
enum class PhotoFormat(val label: String) {
    JPEG("JPEG"),
    HIGH_RES("Max res"),
    ULTRA_HDR("Ultra HDR"),
    RAW("RAW + JPEG"),
    /** 16-bit DNG only. */
    RAW_ONLY("RAW DNG"),
}

enum class VideoResolution(val label: String, val quality: Quality) {
    UHD("4K / 30", Quality.UHD),
    FHD("1080p", Quality.FHD),
    HD("720p", Quality.HD),
}

/** Velora's bitrate choices plus a very high one. */
enum class VideoBitrate(val mbps: Int) {
    B12(12), B30(30), B50(50), B80(80), B150(150);

    val label: String get() = "$mbps Mb/s"
}

/** Velora "Preview performance": here it picks the preview stream size (the GPU work is tiny either way). */
enum class PreviewPerformance(val label: String, val width: Int, val height: Int) {
    SMOOTH("Smooth", 1280, 720),
    AUTO("Auto", 1920, 1080),
    DETAIL("Detail", 2560, 1440),
}

/** New: controls the camera's edge enhancement, the cause of Velora's oversharpened look. */
enum class Sharpening(val label: String) {
    OFF("Off"),
    NATURAL("Natural"),
    CAMERA("Camera"),
}

/** Zoom state lives on its own so per-frame zoom updates only recompose the zoom UI. */
class ZoomUiState {
    var zoom by mutableFloatStateOf(1f)
    var target by mutableFloatStateOf(1f)
    var presets by mutableStateOf(listOf(1f, 2f, 5f, 10f, 20f, 50f))
    var minZoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(50f)
}
