package com.zoomify.camera.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Liquid Glass for the zoom bar (iOS 26 style), drawn by refracting the live viewfinder itself.
 *
 * An AGSL shader runs as a RenderEffect over the viewfinder layer. Inside the bar it shows frosted, darkened
 * glass with a bent rim. Inside the lens it magnifies, bends the rim outward like a thick lens, splits colours
 * slightly at the edge (chromatic aberration), and adds a specular rim light. Android 13+ only; older versions
 * get [ZoomGlassBar]'s flat fallback.
 */
class LiquidGlass {
    /** RenderEffect for the viewfinder layer (null = no glass). Read inside graphicsLayer, so updates skip recomposition. */
    var effect by mutableStateOf<androidx.compose.ui.graphics.RenderEffect?>(null)
        private set

    private val shader: Any? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(SHADER) else null

    val supported: Boolean get() = shader != null

    /** All values in viewfinder pixels. [lensHalf] is half the lens size; [lift] is 0 (resting) .. 1 (touched). */
    fun update(bar: Rect, lensCenter: Offset, lensHalf: Offset, lift: Float) {
        if (Build.VERSION.SDK_INT >= 33) apply33(bar, lensCenter, lensHalf, lift)
    }

    fun hide() {
        effect = null
    }

    @RequiresApi(33)
    private fun apply33(bar: Rect, lensCenter: Offset, lensHalf: Offset, lift: Float) {
        val s = shader as RuntimeShader
        s.setFloatUniform("uBar", bar.left, bar.top, bar.right, bar.bottom)
        s.setFloatUniform("uLens", lensCenter.x, lensCenter.y, lensHalf.x, lensHalf.y)
        s.setFloatUniform("uLift", lift)
        effect = RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }

    private companion object {
        const val SHADER = """
uniform shader content;
uniform float4 uBar;    // left, top, right, bottom
uniform float4 uLens;   // centre x, centre y, half width, half height
uniform float uLift;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float barDist(float2 p) {
    float2 c = (uBar.xy + uBar.zw) * 0.5;
    float2 h = (uBar.zw - uBar.xy) * 0.5;
    return sdRound(p - c, h, h.y);
}

float lensDist(float2 p) {
    return sdRound(p - uLens.xy, uLens.zw, min(uLens.z, uLens.w));
}

float2 barNormal(float2 p) {
    float2 e = float2(1.0, 0.0);
    return normalize(float2(barDist(p + e.xy) - barDist(p - e.xy), barDist(p + e.yx) - barDist(p - e.yx)) + 1e-4);
}

float2 lensNormal(float2 p) {
    float2 e = float2(1.0, 0.0);
    return normalize(float2(lensDist(p + e.xy) - lensDist(p - e.xy), lensDist(p + e.yx) - lensDist(p - e.yx)) + 1e-4);
}

half3 frosted(float2 p) {
    const float R = 9.0;
    half3 c = content.eval(p).rgb * 0.2;
    c += content.eval(p + float2(R, 0.0)).rgb * 0.1;
    c += content.eval(p - float2(R, 0.0)).rgb * 0.1;
    c += content.eval(p + float2(0.0, R)).rgb * 0.1;
    c += content.eval(p - float2(0.0, R)).rgb * 0.1;
    c += content.eval(p + float2(R, R) * 0.7).rgb * 0.1;
    c += content.eval(p - float2(R, R) * 0.7).rgb * 0.1;
    c += content.eval(p + float2(R, -R) * 0.7).rgb * 0.1;
    c += content.eval(p - float2(R, -R) * 0.7).rgb * 0.1;
    return c;
}

half3 rimLight(float d, float2 n, float strength) {
    float rim = 1.0 - smoothstep(0.0, 3.0, abs(d + 1.5));
    float light = clamp(dot(n, normalize(float2(-0.55, -0.85))), 0.0, 1.0);
    float back = clamp(dot(n, normalize(float2(0.55, 0.85))), 0.0, 1.0);
    return half3(rim * strength * (0.22 + 0.6 * light + 0.25 * back));
}

half4 glassBar(float2 p, float d) {
    float h = (uBar.w - uBar.y) * 0.5;
    float inside = clamp(-d / (h * 0.8), 0.0, 1.0);
    float k = (1.0 - inside) * (1.0 - inside);
    float2 n = barNormal(p);
    half3 c = frosted(p + n * k * 10.0);
    c = mix(c, half3(0.09, 0.095, 0.11), 0.38);
    c += rimLight(d, n, 0.7);
    return half4(c, 1.0);
}

half4 glassLens(float2 p, float d) {
    float2 c = uLens.xy;
    float r = min(uLens.z, uLens.w);
    float inside = clamp(-d / (r * 0.95), 0.0, 1.0);
    float k = (1.0 - inside) * (1.0 - inside);
    float2 n = lensNormal(p);
    float mag = 1.12 + 0.12 * uLift;
    // Magnify the centre; bend the rim outward like the edge of a thick lens.
    float2 q = c + (p - c) / mag + n * k * r * 0.55;
    float ca = k * (2.0 + 2.5 * uLift);
    half3 col = half3(content.eval(q - n * ca).r, content.eval(q).g, content.eval(q + n * ca).b);
    col = mix(col, half3(1.0), 0.07 + 0.05 * uLift);
    col += rimLight(d, n, 1.0 + 0.4 * uLift);
    col += half3(k * 0.05);
    return half4(col, 1.0);
}

half4 main(float2 p) {
    float dBar = barDist(p);
    float dLens = lensDist(p);
    if (dBar > 1.0 && dLens > 1.0) return content.eval(p);
    half4 base = content.eval(p);
    if (dBar <= 1.0) base = mix(base, glassBar(p, dBar), 1.0 - smoothstep(-1.0, 1.0, dBar));
    if (dLens <= 1.0) base = mix(base, glassLens(p, dLens), 1.0 - smoothstep(-1.0, 1.0, dLens));
    return base;
}
"""
    }
}
