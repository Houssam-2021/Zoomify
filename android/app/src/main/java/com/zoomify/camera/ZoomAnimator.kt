package com.zoomify.camera

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Velora's zoom spring: critically damped on log2(zoom) with omega = 11 + smoothness * 12.
 * Unlike Velora (a 26/33 ms timer on requestAnimationFrame), it is ticked by Choreographer on every vsync
 * with the real frame time, so the motion stays even at 60, 90 or 120 Hz.
 */
class ZoomAnimator(private val onUpdate: (zoom: Float, velocityOctavesPerSecond: Float) -> Unit) :
    Choreographer.FrameCallback {

    private var minZoom = 1.0
    private var maxZoom = 1.0
    private var position = 0.0
    private var velocity = 0.0
    private var target = 0.0
    private var running = false
    private var lastFrameNanos = 0L

    /** 0.15 (gentle cinematic glide) ... 1.0 (responsive). */
    var smoothness = 0.65

    val targetZoom: Float get() = 2.0.pow(target).toFloat()

    fun setRange(lo: Float, hi: Float) {
        minZoom = lo.toDouble()
        maxZoom = max(lo, hi).toDouble()
    }

    private fun clampLog(zoom: Float): Double = log2(zoom.toDouble().coerceIn(minZoom, maxZoom))

    fun snapTo(zoom: Float) {
        stop()
        target = clampLog(zoom)
        position = target
        velocity = 0.0
        onUpdate(2.0.pow(position).toFloat(), 0f)
    }

    fun animateTo(zoom: Float) {
        val next = clampLog(zoom)
        if (abs(next - target) < 0.0004 && !running) return
        target = next
        if (!running) {
            running = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (running) {
            Choreographer.getInstance().removeFrameCallback(this)
            running = false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 1.0 / 120.0
        else ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(1.0 / 240.0, 1.0 / 20.0)
        lastFrameNanos = frameTimeNanos
        val omega = 11.0 + smoothness * 12.0

        // Exact critically damped spring step (stable for any dt).
        val delta = position - target
        val temp = (velocity + omega * delta) * dt
        val decay = exp(-omega * dt)
        velocity = (velocity - omega * temp) * decay
        position = target + (delta + temp) * decay

        if (abs(position - target) < 0.0001 && abs(velocity) < 0.001) {
            position = target
            velocity = 0.0
            running = false
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
        onUpdate(2.0.pow(position).toFloat(), velocity.toFloat())
    }
}
