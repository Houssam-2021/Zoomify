package com.zoomify.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {
    @Test fun roundTripKeepsEveryChoice() {
        val s = SavedSettings(
            mode = CaptureMode.VIDEO, frontCamera = true, videoResolution = VideoResolution.FHD,
            bitrate = VideoBitrate.B80, frameRate = 60, videoPath = VideoPath.SENSOR_HQ, videoProfile = VideoProfile.LOG,
            stabilization = Stabilization.HORIZON_LOCK, gyroStabilizer = true, colorLook = ColorLook.entries.last(),
            lutId = "teal_orange", lutTarget = LutTarget.entries.last(), logViewAssist = false,
            photoPipeline = PhotoPipeline.entries.last(), photoFormat = PhotoFormat.entries.last(),
            previewPerformance = PreviewPerformance.SMOOTH, sharpening = Sharpening.OFF, hardwareAssist = false,
            mirrorFront = false, micEnabled = false, blurStrength = 0.2f, smoothness = 0.9f, timerSeconds = 10, gridOn = true,
        )
        assertEquals(s, SavedSettings.fromMap(s.toMap()))
    }

    @Test fun emptyStorageGivesDefaults() = assertEquals(SavedSettings(), SavedSettings.fromMap(emptyMap()))

    @Test fun badOrUnknownValuesFallBackSafely() {
        val restored = SavedSettings.fromMap(
            mapOf(
                "videoResolution" to "EIGHT_K", "frameRate" to "120", "blurStrength" to "7", "timerSeconds" to "5",
                "gridOn" to "maybe", "mode" to null, "lutId" to " ",
            )
        )
        assertEquals(SavedSettings(), restored)
    }
}
