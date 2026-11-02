package com.zoomify.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.roundToInt

/** Works out the lens buttons (.5, 1, 2, 3/5 ...) from the zoom range and the physical lenses' focal lengths. */
object ZoomPresets {
    @OptIn(ExperimentalCamera2Interop::class)
    fun compute(context: Context, info: CameraInfo, minZoom: Float, maxZoom: Float): List<Float> {
        val values = mutableListOf(1f)
        if (minZoom < 0.95f) values += minZoom      // ultra-wide
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val logical = manager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId)
            val base = focalPerSensorWidth(logical)
            if (base != null) {
                for (id in logical.physicalCameraIds) {
                    val ratio = focalPerSensorWidth(manager.getCameraCharacteristics(id))?.div(base) ?: continue
                    if (ratio > 1.4f) values += ratio  // telephoto lenses inside the logical camera
                }
            }
        } catch (e: Exception) {
            // Some devices hide their physical cameras; fall back to the generic buttons.
        }
        if (maxZoom >= 2f) values += 2f

        val cleaned = mutableListOf<Float>()
        for (value in values.map { (it * 10).roundToInt() / 10f }.sorted()) {
            if (value < minZoom - 0.05f || value > maxZoom + 0.05f) continue
            if (cleaned.isNotEmpty() && value / cleaned.last() < 1.15f) continue
            cleaned += value
        }
        return cleaned.ifEmpty { listOf(1f) }
    }

    private fun focalPerSensorWidth(chars: CameraCharacteristics): Float? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (sensor.width <= 0f) return null
        return focal / sensor.width
    }
}
