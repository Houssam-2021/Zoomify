package com.zoomify.camera.ui

import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zoomify.camera.CameraController
import com.zoomify.camera.CaptureMode
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun CameraScreen(controller: CameraController) {
    var settingsOpen by remember { mutableStateOf(false) }
    var galleryOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050506))
    ) {
        val compact = maxHeight < 700.dp
        Stage(controller)
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            TopBar(controller, onSettings = { settingsOpen = true })
            StatusArea(controller, compact)
            Spacer(Modifier.weight(1f))
            BottomUi(controller, compact, onGallery = {
                if (controller.lastCapture == null) controller.toast = "Take a photo or record a video first"
                else galleryOpen = true
            })
        }
        ToastHost(controller)
    }
    if (settingsOpen) SettingsSheet(controller) { settingsOpen = false }
    if (galleryOpen) GallerySheet(controller) { galleryOpen = false }
}

// region Viewfinder

@Composable
private fun Stage(controller: CameraController) {
    val density = LocalDensity.current
    val tapSlop = with(density) { 14.dp.toPx() }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                (controller.previewView.parent as? ViewGroup)?.removeView(controller.previewView)
                controller.previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Lens hop on phones with a separate ultra-wide camera: blurred freeze-frame that fades into the new lens.
        AnimatedVisibility(
            visible = controller.freezeFrame != null,
            enter = fadeIn(tween(0)),
            exit = fadeOut(tween(260)),
        ) {
            // Remembered so the frame stays on screen while it fades out.
            val frame = remember { controller.freezeFrame }
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(14.dp) else Modifier),
                )
            }
        }

        // Velora's veil: darkens the top and bottom so the controls stay legible.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.52f),
                        0.22f to Color.Transparent,
                        0.53f to Color.Transparent,
                        0.73f to Color.Black.copy(alpha = 0.32f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    )
                )
        )
        if (controller.gridOn) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Color.White.copy(alpha = 0.42f * 0.55f)
                for (i in 1..2) {
                    drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1f)
                    drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
                }
            }
        }

        // Gestures: Velora's pinch (ratio^1.4), tap = focus, double tap = 1x <-> 2x.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downAt = SystemClock.uptimeMillis()
                        var pinching = false
                        var multiTouch = false
                        var moved = false
                        var startDistance = 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                multiTouch = true
                                val distance = (pressed[0].position - pressed[1].position).getDistance()
                                if (!pinching) {
                                    pinching = true
                                    startDistance = max(1f, distance)
                                    controller.pinchBegan()
                                } else {
                                    controller.pinchChanged(max(0.15f, distance) / startDistance)
                                }
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && (pressed[0].position - down.position).getDistance() > tapSlop) {
                                moved = true
                            }
                            if (pressed.isEmpty()) break
                        }
                        val now = SystemClock.uptimeMillis()
                        if (!multiTouch && !moved && now - downAt < 300) {
                            if (now - lastTapAt < 300) {
                                controller.doubleTap()
                                lastTapAt = 0L
                            } else {
                                lastTapAt = now
                                controller.focus(down.position)
                            }
                        }
                    }
                }
        )

        controller.focusPoint?.let { FocusRing(it) }

        val flashAlpha by animateFloatAsState(
            if (controller.flashWhite) 0.92f else 0f,
            animationSpec = tween(if (controller.flashWhite) 40 else 200), label = "flash",
        )
        if (flashAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))

        controller.countdown?.let { n ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, fontSize = 115.sp, fontWeight = FontWeight.ExtraLight)
            }
        }
    }
}

@Composable
private fun FocusRing(point: Offset) {
    val half = with(LocalDensity.current) { 35.5.dp.toPx() }
    var shown by remember(point) { mutableStateOf(false) }
    LaunchedEffect(point) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 1.18f, tween(280), label = "ringScale")
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(300), label = "ringAlpha")
    Box(
        Modifier
            .offset { IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt()) }
            .size(71.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .border(1.5.dp, Accent, RoundedCornerShape(3.dp))
    )
}

// endregion

// region Top

@Composable
private fun TopBar(controller: CameraController, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 19.dp, end = 19.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(35.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.27f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("◉", color = Color.White, fontSize = 19.sp) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("ZOOMIFY", color = Color.White, fontSize = 14.sp, letterSpacing = 2.6.sp, fontWeight = FontWeight.SemiBold)
            Text("CINEMATIC CAMERA", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(LineIcon.TIMER, active = controller.timerSeconds != 0) { controller.cycleTimer() }
            IconButton(LineIcon.GRID, active = controller.gridOn) { controller.toggleGrid() }
            IconButton(LineIcon.TORCH, active = controller.torchOn) { controller.toggleTorch() }
            IconButton(LineIcon.SETTINGS, active = false, onClick = onSettings)
        }
    }
}

@Composable
private fun IconButton(icon: LineIcon, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "iconScale")
    val background by animateColorAsState(if (active) Accent.copy(alpha = 0.24f) else Color(0x7D1B1C23), label = "iconBg")
    Box(
        Modifier
            .size(39.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, if (active) Accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.17f), CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LineIconView(icon, if (active) Accent else Color.White) }
}

@Composable
private fun StatusArea(controller: CameraController, compact: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 10.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF8DF4AC))
                )
                Spacer(Modifier.width(7.dp))
                PillText(controller.cameraStatus)
            }
            Pill { PillText(controller.engineStatus) }
        }
        if (!compact) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.Black.copy(alpha = 0.47f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(controller.sourceInfo, color = Color(0xFFE8EDF6), fontSize = 10.sp, letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        if (controller.isRecording) RecordBadge(controller.recordingStartedAt)
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(24.dp))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun PillText(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.83f), fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun RecordBadge(startedAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val blink = rememberInfiniteTransition(label = "blink")
    val dot by blink.animateFloat(1f, 0.25f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot")
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    Row(
        Modifier
            .clip(RoundedCornerShape(30.dp))
            .background(Color(0xA6171717))
            .border(1.dp, Color.White.copy(alpha = 0.17f), RoundedCornerShape(30.dp))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF3B4E).copy(alpha = dot))
        )
        Spacer(Modifier.width(8.dp))
        Text(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60), color = Color.White, fontSize = 13.sp,
            letterSpacing = 1.3.sp)
    }
}

@Composable
private fun ToastHost(controller: CameraController) {
    val message = controller.toast
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            delay(2700)
            if (controller.toast == message) controller.toast = null
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(top = 116.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 3 },
        ) {
            Text(
                shown ?: "",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xD41D1E24))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

// endregion

// region Bottom

@Composable
private fun BottomUi(controller: CameraController, compact: Boolean, onGallery: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 23.dp, end = 23.dp, bottom = if (compact) 8.dp else 17.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZoomBlock(controller.zoomUi, compact, onZoom = controller::setZoom, onPreset = controller::selectPreset)
        Row(
            Modifier.padding(top = if (compact) 4.dp else 8.dp, bottom = if (compact) 12.dp else 22.dp),
            horizontalArrangement = Arrangement.spacedBy(35.dp),
        ) {
            for (item in CaptureMode.entries) {
                Text(
                    item.label,
                    color = if (item == controller.mode) Accent else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    letterSpacing = 2.3.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { controller.switchMode(item) }
                        .padding(horizontal = 5.dp, vertical = 7.dp),
                )
            }
        }
        Row(
            Modifier
                .widthIn(max = 350.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GalleryButton(controller, onGallery)
            ShutterButton(controller, if (compact) 69.dp else 80.dp)
            Box(
                Modifier
                    .size(47.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                    .clickable { controller.flipCamera() },
                contentAlignment = Alignment.Center,
            ) { LineIconView(LineIcon.SWITCH, Color.White, 24.dp) }
        }
        Text(
            "50× ZOOM · 4K/30 · HIGH-BITRATE VIDEO",
            color = Color.White.copy(alpha = 0.33f),
            fontSize = 10.sp,
            letterSpacing = 1.1.sp,
            modifier = Modifier.padding(top = if (compact) 8.dp else 15.dp),
        )
    }
}

@Composable
private fun GalleryButton(controller: CameraController, onClick: () -> Unit) {
    Box(
        Modifier
            .size(47.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = controller.thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Last capture",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            LineIconView(LineIcon.GALLERY, Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun ShutterButton(controller: CameraController, size: androidx.compose.ui.unit.Dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(140), label = "shutterScale")
    val video = controller.mode == CaptureMode.VIDEO
    val recording = controller.isRecording
    val inner by animateDpAsState(
        when {
            recording -> size * 0.33f
            video -> size * 0.66f
            else -> size * 0.79f
        },
        tween(280), label = "inner",
    )
    val corner by animateDpAsState(if (recording) 7.dp else size / 2, tween(280), label = "corner")
    val innerColor by animateColorAsState(if (video) Color(0xFFFF4254) else Color.White, tween(280), label = "innerColor")
    val ring by animateColorAsState(if (recording) Color(0xFFFF4254) else Color.White, label = "ring")
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.23f))
            .border(3.dp, ring, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = !controller.photoBusy || video) {
                controller.shutter()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(inner)
                .clip(RoundedCornerShape(corner))
                .background(innerColor.copy(alpha = if (controller.photoBusy && !video) 0.55f else 1f))
        )
    }
}

// endregion
