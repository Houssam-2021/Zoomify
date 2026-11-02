package com.zoomify.camera.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoomify.camera.DIAL_DP_PER_OCTAVE
import com.zoomify.camera.ZoomUiState
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt

val CameraYellow = Color(0xFFFFD60A)

/** Lens buttons (.5 / 1 / 2 / 5 ...) that turn into a zoom dial when you drag sideways across them. */
@Composable
fun ZoomControls(
    state: ZoomUiState,
    onSelect: (Float) -> Unit,
    onDialStart: () -> Unit,
    onDial: (Float) -> Unit,
    onDialEnd: () -> Unit,
) {
    var dragTotal by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragTotal = 0f
                        onDialStart()
                    },
                    onDragEnd = { onDialEnd() },
                    onDragCancel = { onDialEnd() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragTotal += amount
                        onDial(dragTotal.toDp().value)
                    },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Crossfade(targetState = state.dialVisible, animationSpec = tween(200), label = "zoomDial") { dial ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                if (dial) ZoomDial(state) else PresetButtons(state, onSelect)
            }
        }
    }
}

@Composable
private fun PresetButtons(state: ZoomUiState, onSelect: (Float) -> Unit) {
    var active = 0
    state.presets.forEachIndexed { index, preset -> if (state.zoom >= preset * 0.995f) active = index }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.presets.forEachIndexed { index, preset ->
            val isActive = index == active
            val size by animateDpAsState(if (isActive) 38.dp else 30.dp, label = "presetSize")
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(preset) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isActive) zoomLabel(state.zoom, active = true) else zoomLabel(preset, active = false),
                        color = if (isActive) CameraYellow else Color.White,
                        fontSize = if (isActive) 13.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Logarithmic ruler: every doubling of zoom is the same distance. */
@Composable
private fun ZoomDial(state: ZoomUiState) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = zoomLabel(state.zoom, active = true),
            color = CameraYellow,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            val centerX = size.width / 2
            val pxPerOctave = DIAL_DP_PER_OCTAVE.dp.toPx()
            val current = log2(max(state.zoom, 0.01f))
            val lowest = log2(max(state.minZoom, 0.01f))
            val highest = log2(max(state.maxZoom, 0.01f))

            for (tick in ceil(lowest * 10).toInt()..floor(highest * 10).toInt()) {
                val x = centerX + (tick / 10f - current) * pxPerOctave
                if (x < -4f || x > size.width + 4f) continue
                val major = tick % 5 == 0
                val length = if (major) 16.dp.toPx() else 9.dp.toPx()
                drawLine(
                    color = Color.White.copy(alpha = if (major) 0.9f else 0.45f),
                    start = Offset(x, size.height - length),
                    end = Offset(x, size.height),
                    strokeWidth = if (major) 1.5.dp.toPx() else 1.dp.toPx(),
                )
            }

            val marks = (state.presets + listOf(10f, 25f, 50f).filter { it <= state.maxZoom + 0.01f }).distinct()
            for (mark in marks) {
                val x = centerX + (log2(mark) - current) * pxPerOctave
                if (x < -20f || x > size.width + 20f) continue
                val layout = measurer.measure(zoomLabel(mark, active = false), labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x - layout.size.width / 2f,
                        size.height - 28.dp.toPx() - layout.size.height / 2f,
                    ),
                )
            }

            drawLine(
                color = CameraYellow,
                start = Offset(centerX, size.height - 22.dp.toPx()),
                end = Offset(centerX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/** "0.5×", "1×", "1.7×", "12×" for the active button; ".5", "1", "2" for the others. */
fun zoomLabel(zoom: Float, active: Boolean): String {
    val rounded = if (zoom >= 10f) zoom.roundToInt().toFloat() else (zoom * 10).roundToInt() / 10f
    var text = if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(Locale.US, "%.1f", rounded)
    if (!active && text.startsWith("0.")) text = text.substring(1)
    return if (active) "$text×" else text
}
