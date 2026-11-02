package com.zoomify.camera.ui

import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zoomify.camera.CameraController
import com.zoomify.camera.CaptureMode
import com.zoomify.camera.FlashSetting
import com.zoomify.camera.PhotoFormat
import com.zoomify.camera.VideoBitrate
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private val TopBarHeight = 56.dp

@Composable
fun CameraScreen(controller: CameraController) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        val isPhoto = controller.mode == CaptureMode.PHOTO
        val aspect = if (isPhoto) 4f / 3f else 16f / 9f
        val previewHeight = minOf(maxWidth * aspect, maxHeight)
        val previewTop = if (isPhoto) TopBarHeight else ((maxHeight - previewHeight) / 2).coerceAtLeast(0.dp)
        val animatedTop by animateDpAsState(previewTop, label = "previewTop")
        val animatedHeight by animateDpAsState(previewHeight, label = "previewHeight")

        Box(
            Modifier
                .padding(top = animatedTop)
                .width(maxWidth)
                .height(animatedHeight)
        ) {
            PreviewArea(controller)
        }

        Column(Modifier.fillMaxSize()) {
            TopBar(controller)
            controller.toast?.let { message ->
                Toast(message) { controller.toast = null }
            }
            Spacer(Modifier.weight(1f))
            BottomControls(controller)
        }
    }
}

@Composable
private fun PreviewArea(controller: CameraController) {
    val density = LocalDensity.current
    val halfSquare = with(density) { 36.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        AndroidView(
            factory = {
                (controller.previewView.parent as? ViewGroup)?.removeView(controller.previewView)
                controller.previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) controller.pinchBy(zoom) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> controller.focus(offset) }
                }
        )
        controller.focusPoint?.let { point ->
            Box(
                Modifier
                    .offset { IntOffset((point.x - halfSquare).roundToInt(), (point.y - halfSquare).roundToInt()) }
                    .size(72.dp)
                    .border(1.5.dp, CameraYellow)
            )
        }
        val blinkAlpha by animateFloatAsState(if (controller.shutterBlink) 0.85f else 0f, label = "blink")
        if (blinkAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = blinkAlpha))
            )
        }
    }
}

@Composable
private fun TopBar(controller: CameraController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (controller.mode == CaptureMode.PHOTO) {
            Chip(controller.flash.label, highlighted = controller.flash != FlashSetting.OFF) { controller.cycleFlash() }
            Spacer(Modifier.weight(1f))
            Chip(controller.photoFormat.label, highlighted = controller.photoFormat != PhotoFormat.JPEG) {
                controller.cyclePhotoFormat()
            }
        } else {
            if (controller.hdrSupported) {
                Chip("HDR", highlighted = controller.hdrActive, strikethrough = !controller.hdrVideo) {
                    controller.toggleHdr()
                }
            }
            Spacer(Modifier.weight(1f))
            if (controller.isRecording) RecordingTimer(controller.recordingStartedAt)
            Spacer(Modifier.weight(1f))
            Chip(controller.bitrate.label, highlighted = controller.bitrate == VideoBitrate.EXTREME) {
                controller.cycleBitrate()
            }
            Chip("${controller.videoQualityLabel} · 30", highlighted = false, onClick = null)
        }
    }
}

@Composable
private fun Chip(text: String, highlighted: Boolean, strikethrough: Boolean = false, onClick: (() -> Unit)?) {
    Text(
        text = text,
        color = if (highlighted) Color.Black else Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) CameraYellow else Color.White.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun Toast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(2500)
        onDismiss()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    Text(
        text = String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60),
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFFFF3B30))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun BottomControls(controller: CameraController) {
    val fade = if (controller.mode == CaptureMode.VIDEO) 0.55f else 0.9f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = fade))))
            .padding(top = 8.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZoomControls(
            state = controller.zoomUi,
            onSelect = controller::selectPreset,
            onDialStart = controller::dialBegan,
            onDial = controller::dialChanged,
            onDialEnd = controller::dialEnded,
        )
        ModeSelector(controller.mode, hidden = controller.isRecording) { controller.setMode(it) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(controller)
            Spacer(Modifier.weight(1f))
            ShutterButton(
                mode = controller.mode,
                isRecording = controller.isRecording,
                busy = controller.capturesInFlight > 0,
                onClick = controller::shutter,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (controller.isRecording) 0f else 0.14f))
                    .then(if (controller.isRecording) Modifier else Modifier.clickable { controller.flipCamera() }),
                contentAlignment = Alignment.Center,
            ) {
                if (!controller.isRecording) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Switch camera", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: CaptureMode, hidden: Boolean, onSelect: (CaptureMode) -> Unit) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .pointerInput(hidden) {
                if (hidden) return@pointerInput
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total > 60f) onSelect(CaptureMode.VIDEO)
                        if (total < -60f) onSelect(CaptureMode.PHOTO)
                    },
                ) { _, amount -> total += amount }
            },
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!hidden) {
            CaptureMode.entries.forEach { item ->
                Text(
                    text = item.label,
                    color = if (item == mode) CameraYellow else Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.clickable { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(mode: CaptureMode, isRecording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, label = "shutterScale")
    val recordingShape = mode == CaptureMode.VIDEO && isRecording
    val innerSize by animateDpAsState(if (recordingShape) 30.dp else 64.dp, label = "shutterSize")
    val corner by animateDpAsState(if (recordingShape) 7.dp else 32.dp, label = "shutterCorner")
    val color by animateColorAsState(
        when {
            mode == CaptureMode.VIDEO -> Color(0xFFFF3B30)
            busy -> Color.White.copy(alpha = 0.55f)
            else -> Color.White
        },
        label = "shutterColor",
    )
    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(78.dp)
                .border(4.dp, Color.White, CircleShape)
        )
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(corner))
                .background(color)
        )
    }
}

@Composable
private fun Thumbnail(controller: CameraController) {
    val bitmap = controller.thumbnail
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .clickable { controller.openLastMedia() },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Last capture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Zoomify needs camera access", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Allow the camera (and microphone for video) to start shooting.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Allow access",
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(CameraYellow)
                .clickable(onClick = onRequest)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}
