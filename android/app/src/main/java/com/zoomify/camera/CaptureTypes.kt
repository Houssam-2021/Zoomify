package com.zoomify.camera

import androidx.camera.core.ImageCapture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class CaptureMode(val label: String) { VIDEO("VIDEO"), PHOTO("PHOTO") }

enum class PhotoFormat(val label: String) {
    /** Full-quality JPEG (quality 100) from the phone's own image pipeline. */
    JPEG("JPEG"),
    /** Full-sensor resolution (e.g. 50 MP) where the phone allows it. */
    HIGH_RES("MAX RES"),
    /** Ultra HDR JPEG (gain map), like the HDR photos on iPhone. */
    ULTRA_HDR("HDR"),
    /** Uncompressed DNG plus a JPEG. */
    RAW("RAW+JPEG"),
}

enum class FlashSetting(val label: String, val mode: Int) {
    OFF("FLASH OFF", ImageCapture.FLASH_MODE_OFF),
    AUTO("FLASH AUTO", ImageCapture.FLASH_MODE_AUTO),
    ON("FLASH ON", ImageCapture.FLASH_MODE_ON),
}

/** Target video bitrates. Stock Android cameras record 4K30 at roughly 40-50 Mb/s. */
enum class VideoBitrate(val mbps: Int) {
    STANDARD(50), HIGH(100), EXTREME(150);

    val label: String get() = "$mbps Mb/s"
}

/** Zoom state is kept separate so 120 Hz zoom updates only recompose the zoom controls. */
class ZoomUiState {
    var zoom by mutableFloatStateOf(1f)
    var presets by mutableStateOf(listOf(1f))
    var minZoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(1f)
    var dialVisible by mutableStateOf(false)
}

const val DIAL_DP_PER_OCTAVE = 110f
