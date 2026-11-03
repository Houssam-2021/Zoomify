package com.zoomify.camera

import android.hardware.camera2.params.TonemapCurve
import kotlin.math.ln
import kotlin.math.pow

/**
 * Zoomify Log: y = 0.06 + 0.88 * (log2(x + C) - log2(C)) / (log2(1 + C) - log2(C)), C = 0.03.
 * Recorded 1 EV under, mid grey lands at ~0.40, clip at 0.94, black at 0.06 (like other camera log curves).
 * Must match tools/generate_luts.py and the shader in ZoomBlurProcessor.
 */
object ZoomifyLog {
    const val C = 0.03
    const val EXPOSURE_OFFSET_EV = -1f
    private val L0 = log2(C)
    private val L1 = log2(1 + C)

    private fun log2(x: Double) = ln(x) / ln(2.0)

    fun encode(linear: Double): Double = 0.06 + 0.88 * (log2(linear + C) - L0) / (L1 - L0)

    /** The curve as a camera tonemap (applied by the ISP before encoding, so it keeps the sensor's range). */
    fun tonemapCurve(maxPoints: Int): TonemapCurve {
        val count = maxPoints.coerceIn(2, 64)
        val points = FloatArray(count * 2)
        for (i in 0 until count) {
            // Denser points in the shadows, where the log curve bends the most.
            val t = i / (count - 1.0)
            val x = (2.0.pow(t * 10) - 1) / (2.0.pow(10.0) - 1)
            points[i * 2] = x.toFloat()
            points[i * 2 + 1] = encode(x).toFloat().coerceIn(0f, 1f)
        }
        return TonemapCurve(points, points.copyOf(), points.copyOf())
    }
}
