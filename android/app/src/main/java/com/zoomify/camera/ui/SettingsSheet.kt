package com.zoomify.camera.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zoomify.camera.CameraController
import com.zoomify.camera.ColorLook
import com.zoomify.camera.LutLibrary
import com.zoomify.camera.LutTarget
import com.zoomify.camera.MediaStoreHelper
import com.zoomify.camera.PhotoFormat
import com.zoomify.camera.PhotoPipeline
import com.zoomify.camera.PreviewPerformance
import com.zoomify.camera.Sharpening
import com.zoomify.camera.Stabilization
import com.zoomify.camera.VideoBitrate
import com.zoomify.camera.VideoPath
import com.zoomify.camera.VideoProfile
import com.zoomify.camera.VideoResolution
import java.util.Locale
import kotlin.math.roundToInt

private val SheetColor = Color(0xFF1C1C21)
private val Dim = Color.White.copy(alpha = 0.46f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(c: CameraController, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val lutTitles = remember {
        LutLibrary.creativeIds.associateWith { id -> LutLibrary.load(context, id)?.title ?: id }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = SheetColor) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 25.dp, end = 25.dp, bottom = 32.dp)
        ) {
            Text("Cinematic controls", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)

            SliderSetting(
                "Motion blur strength", "${(c.blurStrength * 100).roundToInt()}%", c.blurStrength, 0f..1f,
                c::updateBlurStrength, "Radial multi-sample blur appears only while zooming, then clears when you stop.",
            )
            SliderSetting(
                "Zoom smoothness",
                when { c.smoothness < 0.38f -> "Cinematic"; c.smoothness > 0.80f -> "Responsive"; else -> "Balanced" },
                c.smoothness, 0.15f..1f, c::updateSmoothness,
                "Lower = gentle cinematic glide. Higher = more responsive following.",
            )
            SliderSetting(
                "Exposure", String.format(Locale.US, "%+.1f EV", c.exposureEv), c.exposureEv, -1f..1f, c::updateExposure,
                "Real camera exposure compensation, so it applies to photos and video without clipping.",
            )

            Section("VIDEO")
            Choices("Capture source resolution", c.videoResolution.label, VideoResolution.entries, c.videoResolution,
                { it.label }, onSelect = c::chooseResolution,
                note = "Native 4K: the camera streams straight to the GPU and the hardware encoder.")
            Choices("Video bitrate target", c.bitrate.label, VideoBitrate.entries, c.bitrate, { it.label },
                onSelect = c::chooseBitrate,
                note = "50 Mb/s ≈ 375 MB/minute. The phone's encoder may cap very high targets.")
            Choices("Frame rate", "${c.activeFps} fps", listOf(30, 60), c.frameRate, { "$it fps" },
                enabled = { it == 30 || c.fps60Supported }, onSelect = c::chooseFrameRate,
                note = if (c.fps60Supported) "60 fps is supported by this camera at ${c.videoResolution.label} in this mode."
                else "60 fps isn't supported by this camera at ${c.videoResolution.label} in this mode. " +
                    "Try 1080p, Sensor HQ or turning Horizon lock off.")
            Choices("Video recording path", c.effectivePath.label, VideoPath.entries, c.videoPath, { it.label },
                onSelect = c::chooseVideoPath,
                note = "Cinematic records zoom past the lens, colour, LUT and motion blur at full resolution (including 4K). " +
                    "Sensor HQ records the camera's own stream (zoom limited to the camera's real zoom) and allows 10-bit HDR.")
            Choices("Video profile", c.videoProfile.label, VideoProfile.entries, c.videoProfile, { it.label },
                enabled = { it != VideoProfile.HDR || c.hdrSupported },
                onSelect = c::chooseVideoProfile,
                note = if (c.logHardware) "Zoomify Log uses a hardware log tone curve, recorded 1 EV under for highlight headroom. " +
                    "HDR records 10-bit HLG (Sensor HQ only)."
                else "This camera has no custom tone curve, so Log is emulated on the GPU (8-bit, Cinematic path). " +
                    "HDR records 10-bit HLG where supported.")
            Choices("Stabilization", c.stabilization.label, Stabilization.entries, c.stabilization, { it.label },
                onSelect = c::chooseStabilization,
                note = "Horizon lock keeps the horizon level while you tilt the phone (up to ±15°) with a fixed crop. " +
                    "It uses the Cinematic path." + if (c.stabilizationSupported) "" else " Standard EIS isn't offered by this camera.")
            if (c.videoProfile == VideoProfile.LOG) {
                Toggle("Log view assist", "Shows Log converted to Rec.709 in the viewfinder. Recordings stay Log.",
                    c.logViewAssist, c::toggleLogViewAssist)
                ActionButton("Save Zoomify Log → Rec709 LUT (.cube)", c::exportLogLut)
            }

            Section("COLOR")
            Choices("LUT", c.lutId?.let { lutTitles[it] } ?: "None", listOf<String?>(null) + LutLibrary.creativeIds, c.lutId,
                { id -> if (id == null) "None" else lutTitles[id] ?: id }, onSelect = c::chooseLut,
                note = "Built-in 33-point 3D LUTs, applied on the GPU in real time.")
            Choices("LUT applies to", c.lutTarget.label, LutTarget.entries, c.lutTarget, { it.label },
                onSelect = c::chooseLutTarget)
            Choices("Color science", c.colorLook.label, ColorLook.entries, c.colorLook, { it.label },
                onSelect = c::chooseLook,
                note = "Natural uses gentle highlight shaping, warmer skin-friendly tones and controlled saturation.")
            Choices("Sharpening", c.sharpening.label, Sharpening.entries, c.sharpening, { it.label },
                onSelect = c::chooseSharpening,
                note = "Natural turns off the camera's preview/video edge enhancement (no halos when zooming) and " +
                    "keeps its gentler high-quality sharpening for photos. Off disables it everywhere.")

            Section("PHOTO")
            Choices("Photo quality", c.photoPipeline.label, PhotoPipeline.entries, c.photoPipeline, { it.label },
                onSelect = c::choosePhotoPipeline,
                note = "Full-res colors applies the look to the camera's full-resolution JPEG. Native JPEG keeps " +
                    "the camera's colours (digital zoom is still cropped to match the viewfinder).")
            Choices("Photo format", c.photoFormat.label, c.availablePhotoFormats, c.photoFormat, { it.label },
                enabled = { it !in c.unsupportedPhotoFormats },
                onSelect = c::choosePhotoFormat,
                note = "RAW saves 16-bit DNG files to DCIM/Zoomify. ${c.rawStatus}")

            Section("CAMERA")
            Choices("Preview performance", c.previewPerformance.label, PreviewPerformance.entries, c.previewPerformance,
                { it.label }, onSelect = c::choosePreviewPerformance,
                note = "Preview stream size. All modes run at the camera's full frame rate.")
            Toggle("Hardware zoom assist",
                "Camera zoom ${c.hardwareZoomRange}, including automatic lens switching (0.5× ultra-wide ↔ 1× ↔ tele). " +
                    "GPU zoom fills the rest up to 50×.",
                c.hardwareAssist, c::toggleHardwareAssist)
            Toggle("Mirror front camera", "Mirror selfies and front-camera recordings.", c.mirrorFront, c::toggleMirror)
            Toggle("Record microphone", "Record audio with videos.", c.micEnabled, c::toggleMic)

            Text(
                "50× is largely digital zoom and gets soft past the telephoto lens. Phones stream RAW photos only: " +
                    "16-bit RAW video would need ~700 MB/s, more than phones can store, so video uses Log or 10-bit HDR instead.",
                color = Color.White.copy(alpha = 0.34f), fontSize = 11.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = Accent, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 26.dp, bottom = 2.dp))
}

@Composable
private fun Header(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(value, color = Accent, fontSize = 12.sp)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Dim, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SliderSetting(
    title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, note: String,
) {
    Column(Modifier.padding(top = 19.dp)) {
        Header(title, value)
        Slider(
            value = current, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent,
                inactiveTrackColor = Color(0xFF48484C)),
        )
        Note(note)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    title: String, value: String, options: List<T>, selected: T, label: (T) -> String,
    enabled: (T) -> Boolean = { true }, onSelect: (T) -> Unit, note: String? = null,
) {
    Column(Modifier.padding(top = 19.dp)) {
        Header(title, value)
        Spacer(Modifier.width(1.dp))
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (option in options) {
                val isSelected = option == selected
                val isEnabled = enabled(option)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.065f))
                        .border(1.dp, if (isSelected) Accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp))
                        .clickable(enabled = isEnabled) { onSelect(option) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(
                        label(option),
                        color = when { isSelected -> Accent; isEnabled -> Color.White; else -> Color.White.copy(alpha = 0.3f) },
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (note != null) Note(note)
    }
}

@Composable
private fun Toggle(title: String, note: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Note(note)
        }
        Spacer(Modifier.width(18.dp))
        Switch(
            checked = checked, onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF4C4C52), uncheckedThumbColor = Color.White),
        )
    }
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** Velora's "Last capture" sheet. Captures are already in the gallery, so the action opens them there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySheet(c: CameraController, onDismiss: () -> Unit) {
    val last = c.lastCapture ?: return
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = SheetColor) {
        Column(Modifier.padding(start = 25.dp, end = 25.dp, bottom = 32.dp)) {
            Text("Last capture", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(1.dp))
            Box(
                Modifier
                    .padding(vertical = 19.dp)
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF050505)),
                contentAlignment = Alignment.Center,
            ) {
                if (last.isVideo) {
                    AndroidView(factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(last.uri)
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setOnPreparedListener { start() }
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                } else {
                    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(last.uri) {
                        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            MediaStoreHelper.loadThumbnail(context, last.uri, 1440)
                        }
                    }
                    bitmap?.let {
                        Image(it.asImageBitmap(), "Last photo", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                SheetButton("Close", Color.White.copy(alpha = 0.12f), Color.White, Modifier.weight(1f), onDismiss)
                SheetButton("Open in Gallery", Accent, Color(0xFF151515), Modifier.weight(1f)) { c.openLastInGallery() }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, background: Color, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontWeight = FontWeight.SemiBold) }
}

@Suppress("unused")
private fun uriLabel(uri: Uri) = uri.lastPathSegment ?: ""
