package com.zoomify.camera

import android.graphics.Bitmap

/** CPU version of the shader's colour look (+ LUT), for full-resolution photos. Works in strips to limit memory. */
object ColorLookProcessor {
    fun apply(bitmap: Bitmap, look: ColorLook, lut: Lut? = null) {
        if (look == ColorLook.NEUTRAL && lut == null) return
        val rgb = FloatArray(3)
        val width = bitmap.width
        val rows = 64
        val pixels = IntArray(width * rows)
        val sat = look.saturation
        var y = 0
        while (y < bitmap.height) {
            val h = minOf(rows, bitmap.height - y)
            bitmap.getPixels(pixels, 0, width, 0, y, width, h)
            for (i in 0 until width * h) {
                val p = pixels[i]
                var r = ((p shr 16) and 0xFF) / 255f
                var g = ((p shr 8) and 0xFF) / 255f
                var b = (p and 0xFF) / 255f
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = lum + (r - lum) * sat
                g = lum + (g - lum) * sat
                b = lum + (b - lum) * sat
                if (look.shaped) {
                    r *= 1.004f; g *= 0.998f; b *= 0.992f
                    r = shape(r); g = shape(g); b = shape(b)
                    r *= 1.014f; g *= 1.004f; b *= 0.991f
                }
                if (lut != null) {
                    rgb[0] = r; rgb[1] = g; rgb[2] = b
                    lut.apply(rgb)
                    r = rgb[0]; g = rgb[1]; b = rgb[2]
                }
                pixels[i] = (p and 0xFF000000.toInt()) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            }
            bitmap.setPixels(pixels, 0, width, 0, y, width, h)
            y += h
        }
    }

    /** mix(c, c * (0.93 + 0.12c), 0.26): gentle highlight shaping. */
    private fun shape(c: Float): Float = c + (c * (0.93f + 0.12f * c) - c) * 0.26f

    private fun to8(c: Float): Int = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
