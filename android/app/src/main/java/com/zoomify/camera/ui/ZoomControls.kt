package com.zoomify.camera.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoomify.camera.ZoomUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

val Accent = Color(0xFFF5CB69)
val Glass = Color(0x8019191E)
val GlassStroke = Color(0x2EFFFFFF)

/** Velora's zoom block: caption, big zoom readout, preset pills and the log-scale dial. */
@Composable
fun ZoomBlock(
    state: ZoomUiState, compact: Boolean, glass: LiquidGlass?, onZoom: (Float) -> Unit, onPreset: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) {
            Text("FLUID MOTION ZOOM", color = Accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
        }
        ZoomReadout(state, if (compact) 40 else 52)
        ZoomGlassBar(state, glass, onZoom = onZoom, onPreset = onPreset)
        ZoomDial(state, if (compact) 49.dp else 64.dp, showHint = !compact, onZoom = onZoom)
    }
}

@Composable
private fun ZoomReadout(state: ZoomUiState, size: Int) {
    val text = String.format(Locale.US, "%.1f", state.zoom)
    Text(
        text = buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(fontSize = (size * 0.6f).sp, color = Color.White.copy(alpha = 0.85f))) { append(" ×") }
        },
        color = Color.White,
        fontSize = size.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2).sp,
        modifier = Modifier.padding(bottom = if (size > 45) 10.dp else 4.dp),
    )
}

fun presetLabel(value: Float): String {
    val text = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value).removePrefix("0")
    return "$text×"
}

/** Absolute log-scale slider like Velora's: touch or drag anywhere to set the zoom. */
@Composable
private fun ZoomDial(state: ZoomUiState, height: Dp, showHint: Boolean, onZoom: (Float) -> Unit) {
    val lo = ln(state.minZoom.coerceAtLeast(0.1f))
    val hi = ln(state.maxZoom.coerceAtLeast(state.minZoom + 0.01f))
    Box(
        modifier = Modifier
            .widthIn(max = 425.dp)
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 12.dp)
            .pointerInput(lo, hi) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun set(x: Float) {
                        val p = (x / size.width).coerceIn(0f, 1f)
                        onZoom(exp(lo + p * (hi - lo)))
                    }
                    set(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        set(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val top = 10.dp.toPx()
            val bottom = size.height - 15.dp.toPx()
            val middle = (top + bottom) / 2
            val count = 52
            for (i in 0..count) {
                val x = w * i / count
                val big = i == 0 || i == count || i == 17 || i == 27 || i == 40
                val mid = !big && i % 4 == 0
                val h = when { big -> 19.dp.toPx(); mid -> 14.dp.toPx(); else -> 9.dp.toPx() }
                drawLine(
                    color = Color.White.copy(alpha = if (big) 0.73f else 0.34f),
                    start = Offset(x, middle - h / 2), end = Offset(x, middle + h / 2),
                    strokeWidth = if (big) 2.dp.toPx() else 1.dp.toPx(),
                )
            }
            val p = ((ln(state.zoom.coerceAtLeast(0.1f)) - lo) / (hi - lo)).coerceIn(0f, 1f)
            val thumbX = p * w
            val thumbW = 3.dp.toPx()
            drawRoundRect(
                color = Accent.copy(alpha = 0.25f),
                topLeft = Offset(thumbX - thumbW * 2.5f, 1.dp.toPx()),
                size = Size(thumbW * 5, 45.dp.toPx().coerceAtMost(size.height - 4.dp.toPx())),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            drawRoundRect(
                color = Accent,
                topLeft = Offset(thumbX - thumbW / 2, 3.dp.toPx()),
                size = Size(thumbW, 41.dp.toPx().coerceAtMost(size.height - 8.dp.toPx())),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        if (showHint) {
            Text(
                "DRAG TO ZOOM · PINCH THE VIEWFINDER",
                color = Color.White.copy(alpha = 0.38f),
                fontSize = 9.sp,
                letterSpacing = 1.3.sp,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Rounded to tenths, e.g. for toasts. */
fun zoomText(zoom: Float): String = String.format(Locale.US, "%.1f×", (zoom * 10).roundToInt() / 10f)
