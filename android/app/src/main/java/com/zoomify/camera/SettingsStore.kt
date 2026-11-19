package com.zoomify.camera

import android.content.Context

/**
 * The user's choices, saved when they change and when the app goes to the background, and restored on launch.
 * Stored as plain strings so a value from an older or newer version can never crash the app: anything unknown
 * falls back to the default.
 */
data class SavedSettings(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val frontCamera: Boolean = false,
    val videoResolution: VideoResolution = VideoResolution.UHD,
    val bitrate: VideoBitrate = VideoBitrate.B50,
    val frameRate: Int = 30,
    val videoPath: VideoPath = VideoPath.CINEMATIC,
    val videoProfile: VideoProfile = VideoProfile.STANDARD,
    val stabilization: Stabilization = Stabilization.STANDARD,
    val gyroStabilizer: Boolean = false,
    val colorLook: ColorLook = ColorLook.NATURAL,
    val lutId: String? = null,
    val lutTarget: LutTarget = LutTarget.ALL,
    val logViewAssist: Boolean = true,
    val photoPipeline: PhotoPipeline = PhotoPipeline.PROCESSED,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val previewPerformance: PreviewPerformance = PreviewPerformance.AUTO,
    val sharpening: Sharpening = Sharpening.NATURAL,
    val hardwareAssist: Boolean = true,
    val mirrorFront: Boolean = true,
    val micEnabled: Boolean = true,
    val blurStrength: Float = 0.65f,
    val smoothness: Float = 0.65f,
    val timerSeconds: Int = 0,
    val gridOn: Boolean = false,
) {
    fun toMap(): Map<String, String> = buildMap {
        put("mode", mode.name)
        put("frontCamera", frontCamera.toString())
        put("videoResolution", videoResolution.name)
        put("bitrate", bitrate.name)
        put("frameRate", frameRate.toString())
        put("videoPath", videoPath.name)
        put("videoProfile", videoProfile.name)
        put("stabilization", stabilization.name)
        put("gyroStabilizer", gyroStabilizer.toString())
        put("colorLook", colorLook.name)
        lutId?.let { put("lutId", it) }
        put("lutTarget", lutTarget.name)
        put("logViewAssist", logViewAssist.toString())
        put("photoPipeline", photoPipeline.name)
        put("photoFormat", photoFormat.name)
        put("previewPerformance", previewPerformance.name)
        put("sharpening", sharpening.name)
        put("hardwareAssist", hardwareAssist.toString())
        put("mirrorFront", mirrorFront.toString())
        put("micEnabled", micEnabled.toString())
        put("blurStrength", blurStrength.toString())
        put("smoothness", smoothness.toString())
        put("timerSeconds", timerSeconds.toString())
        put("gridOn", gridOn.toString())
    }

    companion object {
        fun fromMap(m: Map<String, String?>): SavedSettings {
            val d = SavedSettings()
            fun bool(key: String, default: Boolean) = m[key]?.toBooleanStrictOrNull() ?: default
            fun unit(key: String, default: Float) = m[key]?.toFloatOrNull()?.takeIf { it in 0f..1f } ?: default
            return SavedSettings(
                mode = enumOr(m["mode"], d.mode),
                frontCamera = bool("frontCamera", d.frontCamera),
                videoResolution = enumOr(m["videoResolution"], d.videoResolution),
                bitrate = enumOr(m["bitrate"], d.bitrate),
                frameRate = m["frameRate"]?.toIntOrNull()?.takeIf { it == 30 || it == 60 } ?: d.frameRate,
                videoPath = enumOr(m["videoPath"], d.videoPath),
                videoProfile = enumOr(m["videoProfile"], d.videoProfile),
                stabilization = enumOr(m["stabilization"], d.stabilization),
                gyroStabilizer = bool("gyroStabilizer", d.gyroStabilizer),
                colorLook = enumOr(m["colorLook"], d.colorLook),
                lutId = m["lutId"]?.takeIf { it.isNotBlank() },
                lutTarget = enumOr(m["lutTarget"], d.lutTarget),
                logViewAssist = bool("logViewAssist", d.logViewAssist),
                photoPipeline = enumOr(m["photoPipeline"], d.photoPipeline),
                photoFormat = enumOr(m["photoFormat"], d.photoFormat),
                previewPerformance = enumOr(m["previewPerformance"], d.previewPerformance),
                sharpening = enumOr(m["sharpening"], d.sharpening),
                hardwareAssist = bool("hardwareAssist", d.hardwareAssist),
                mirrorFront = bool("mirrorFront", d.mirrorFront),
                micEnabled = bool("micEnabled", d.micEnabled),
                blurStrength = unit("blurStrength", d.blurStrength),
                smoothness = unit("smoothness", d.smoothness),
                timerSeconds = m["timerSeconds"]?.toIntOrNull()?.takeIf { it in setOf(0, 3, 10) } ?: d.timerSeconds,
                gridOn = bool("gridOn", d.gridOn),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("zoomify_settings", Context.MODE_PRIVATE)

    fun load(): SavedSettings = SavedSettings.fromMap(prefs.all.mapValues { it.value?.toString() })

    fun save(settings: SavedSettings) {
        val editor = prefs.edit().clear()
        settings.toMap().forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }
}
