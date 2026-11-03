package com.zoomify.camera.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zoomify.camera.ZoomUiState
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val LensBlue = Color(0xFF2E9BFF)

/**
 * Liquid Glass zoom bar. Drag the lens along the bar to zoom continuously (log-interpolated between the presets),
 * tap a preset to glide there, and pinching on the viewfinder moves the lens too. While touched the lens lifts,
 * swells with a springy wobble and stretches with speed; on release it settles, snapping to a preset when close.
 */
@Composable
fun ZoomGlassBar(state: ZoomUiState, glass: LiquidGlass?, onZoom: (Float) -> Unit, onPreset: (Float) -> Unit) {
    val presets = state.presets
    val count = presets.size.coerceAtLeast(1)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val barHeight = with(density) { 46.dp.toPx() }
    val inset = with(density) { 3.dp.toPx() }

    var bounds by remember { mutableStateOf(Rect.Zero) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var fingerX by remember { mutableFloatStateOf(0f) }

    val slot = widthPx / count
    fun slotCenter(i: Int) = (i + 0.5f) * slot
    fun zoomToX(z: Float): Float {
        if (count == 1 || z <= presets[0]) return slotCenter(0)
        for (i in 0 until count - 1) {
            if (z <= presets[i + 1]) {
                val t = ln(z / presets[i]) / ln(presets[i + 1] / presets[i])
                return slotCenter(i) + t * slot
            }
        }
        return slotCenter(count - 1)
    }
    fun xToZoom(x: Float): Float {
        if (count == 1) return presets[0]
        val f = (x / slot - 0.5f).coerceIn(0f, count - 1f)
        val i = floor(f).toInt().coerceAtMost(count - 2)
        val t = f - i
        return presets[i] * (presets[i + 1] / presets[i]).pow(t)
    }

    val targetX = if (dragging) fingerX else zoomToX(state.zoom)
    val lensX by animateFloatAsState(
        targetX, spring(dampingRatio = 0.78f, stiffness = if (dragging) 1600f else 700f), label = "lensX",
    )
    val lift by animateFloatAsState(
        if (touching) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "lift",
    )
    // Liquid stretch: the further the lens lags behind its target, the faster it's moving.
    val stretch = (abs(targetX - lensX) / slot.coerceAtLeast(1f)).coerceIn(0f, 0.6f) * 0.55f

    val baseHalfW = (slot / 2 - inset).coerceAtLeast(1f)
    val baseHalfH = barHeight / 2 - inset
    val halfW = baseHalfW * (1f + 0.22f * lift) * (1f + stretch)
    val halfH = baseHalfH * (1f + 0.32f * lift) * (1f - 0.35f * stretch)

    Canvas(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth()
            .height(62.dp)
            .onGloballyPositioned { coordinates ->
                bounds = coordinates.boundsInRoot()
                widthPx = coordinates.size.width.toFloat().coerceAtLeast(1f)
            }
            .pointerInput(presets) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slotPx = size.width.toFloat() / count
                    val half = slotPx / 2
                    touching = true
                    var moved = false
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!moved && abs(change.position.x - down.position.x) > slop) {
                            moved = true
                            dragging = true
                        }
                        if (moved) {
                            fingerX = change.position.x.coerceIn(half, size.width - half)
                            onZoom(xToZoom(fingerX))
                        }
                        change.consume()
                    }
                    if (!moved) {
                        val i = (down.position.x / slotPx).toInt().coerceIn(0, count - 1)
                        onPreset(presets[i])
                    } else {
                        // Magnetic snap when released near a preset.
                        val i = ((fingerX / slotPx) - 0.5f).roundToInt().coerceIn(0, count - 1)
                        if (abs(fingerX - (i + 0.5f) * slotPx) < slotPx * 0.2f) onPreset(presets[i])
                    }
                    dragging = false
                    touching = false
                }
            },
    ) {
        val cy = size.height / 2
        val barTop = cy - barHeight / 2
        val barRect = RoundRect(0f, barTop, size.width, barTop + barHeight, CornerRadius(barHeight / 2))
        val lensRect = Rect(lensX - halfW, cy - halfH, lensX + halfW, cy + halfH)
        val lensShape = RoundRect(lensRect, CornerRadius(minOf(halfW, halfH)))

        if (glass?.supported != true) {
            // Flat fallback (Android 12 and older): tinted glass with rim highlights.
            drawRoundRect(Color(0x8C1E2025), Offset(0f, barTop), Size(size.width, barHeight), CornerRadius(barHeight / 2))
            drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(0f, barTop), Size(size.width, barHeight),
                CornerRadius(barHeight / 2), style = Stroke(1.dp.toPx()))
            drawRoundRect(Color.White.copy(alpha = 0.16f + 0.08f * lift), lensRect.topLeft, lensRect.size,
                CornerRadius(minOf(halfW, halfH)))
            drawRoundRect(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.08f)),
                    start = lensRect.topLeft, end = lensRect.bottomRight),
                lensRect.topLeft, lensRect.size, CornerRadius(minOf(halfW, halfH)), style = Stroke(1.2.dp.toPx()),
            )
        } else {
            drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(0f, barTop), Size(size.width, barHeight),
                CornerRadius(barHeight / 2), style = Stroke(1.dp.toPx()))
        }

        // Labels: white outside the lens, magnified and blue inside it.
        val lensPath = Path().apply { addRoundRect(lensShape) }
        val normal = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val active = TextStyle(color = LensBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        clipPath(lensPath, clipOp = ClipOp.Difference) {
            presets.forEachIndexed { i, preset ->
                val layout = measurer.measure(presetLabel(preset), normal)
                drawText(layout, topLeft = Offset((i + 0.5f) * slot - layout.size.width / 2f, cy - layout.size.height / 2f))
            }
        }
        clipPath(lensPath) {
            withTransform({ scale(1.18f + 0.14f * lift, 1.18f + 0.14f * lift, Offset(lensX, cy)) }) {
                presets.forEachIndexed { i, preset ->
                    val layout = measurer.measure(presetLabel(preset), active)
                    drawText(layout, topLeft = Offset((i + 0.5f) * slot - layout.size.width / 2f, cy - layout.size.height / 2f))
                }
            }
        }
    }

    // Hand the bar + lens geometry (in root pixels) to the viewfinder shader.
    SideEffect {
        if (glass != null && bounds.width > 0f) {
            val cy = bounds.center.y
            glass.update(
                bar = Rect(bounds.left, cy - barHeight / 2, bounds.right, cy + barHeight / 2),
                lensCenter = Offset(bounds.left + lensX, cy),
                lensHalf = Offset(halfW, halfH),
                lift = lift,
            )
        }
    }
    DisposableEffect(glass) { onDispose { glass?.hide() } }
}
