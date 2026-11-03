package com.zoomify.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class LineIcon { TIMER, GRID, TORCH, SETTINGS, SWITCH, GALLERY }

/** Stroke icons drawn on a 24-unit grid, after Velora's SVG set. */
@Composable
fun LineIconView(icon: LineIcon, tint: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        when (icon) {
            LineIcon.TIMER -> {
                drawCircle(tint, radius = 8 * u, center = p(12f, 13f), style = stroke)
                line(12f, 13f, 12f, 8f); line(9f, 2f, 15f, 2f); line(12f, 5f, 12f, 2f); line(18f, 7f, 20f, 5f)
            }
            LineIcon.GRID -> {
                drawRoundRect(tint, p(3f, 3f), Size(18 * u, 18 * u), CornerRadius(2 * u), style = stroke)
                line(9f, 3f, 9f, 21f); line(15f, 3f, 15f, 21f); line(3f, 9f, 21f, 9f); line(3f, 15f, 21f, 15f)
            }
            LineIcon.TORCH -> {
                drawPath(path(u, listOf(9f to 3f, 15f to 3f, 14f to 10f, 10f to 10f), close = true), tint, style = stroke)
                drawPath(path(u, listOf(10f to 10f, 8f to 14f, 12f to 21f, 16f to 14f, 14f to 10f)), tint, style = stroke)
            }
            LineIcon.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f)
                drawCircle(Color(0xFF272830), radius = 2 * u, center = p(9f, 7f))
                drawCircle(tint, radius = 2 * u, center = p(9f, 7f), style = stroke)
                drawCircle(Color(0xFF272830), radius = 2 * u, center = p(15f, 17f))
                drawCircle(tint, radius = 2 * u, center = p(15f, 17f), style = stroke)
            }
            LineIcon.SWITCH -> {
                drawPath(path(u, listOf(4f to 9f, 4f to 5f, 8f to 5f)), tint, style = stroke)
                drawPath(path(u, listOf(20f to 15f, 20f to 19f, 16f to 19f)), tint, style = stroke)
                drawArc(tint, 200f, 130f, false, p(4f, 4f), Size(16 * u, 16 * u), style = stroke)
                drawArc(tint, 20f, 130f, false, p(4f, 4f), Size(16 * u, 16 * u), style = stroke)
                drawCircle(tint, radius = 3 * u, center = p(12f, 12f), style = stroke)
            }
            LineIcon.GALLERY -> {
                drawRoundRect(tint, p(3f, 3f), Size(18 * u, 18 * u), CornerRadius(3 * u), style = stroke)
                drawCircle(tint, radius = 1.5f * u, center = p(8.5f, 8.5f), style = stroke)
                drawPath(path(u, listOf(3f to 16f, 8f to 11f, 12f to 15f, 15f to 12f, 21f to 18f)), tint, style = stroke)
            }
        }
    }
}

private fun DrawScope.path(u: Float, points: List<Pair<Float, Float>>, close: Boolean = false): Path = Path().apply {
    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
    if (close) close()
}
