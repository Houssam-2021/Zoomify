package com.zoomify.camera

import android.view.Choreographer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

/**
 * Critically damped spring on log2(zoom), ticked every display frame (up to 120 Hz).
 *
 * In log space every doubling takes the same time, so 0.5x -> 1x feels like 1x -> 2x or 25x -> 50x, which is how
 * the iPhone Camera zoom feels. A critically damped spring eases in and out without overshoot, and lets taps,
 * pinches and dial drags take over from each other mid-flight without jumps.
 */
class ZoomAnimator(private val onUpdate: (zoom: Float, velocityOctavesPerSecond: Float) -> Unit) :
    Choreographer.FrameCallback {

    enum class Motion { TAP, PINCH, DIAL }

    private var minZoom = 1.0
    private var maxZoom = 1.0
    private var position = 0.0
    private var velocity = 0.0
    private var target = 0.0
    private var omega = 15.0
    private var running = false
    private var lastFrameNanos = 0L

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

    fun animateTo(zoom: Float, motion: Motion) {
        target = clampLog(zoom)
        val response = when (motion) {
            Motion.TAP -> 0.42
            Motion.PINCH -> 0.14
            Motion.DIAL -> 0.10
        }
        omega = 2 * PI / response
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
        else ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(1.0 / 240.0, 1.0 / 30.0)
        lastFrameNanos = frameTimeNanos

        // Exact critically damped spring step (stable for any dt).
        val delta = position - target
        val temp = (velocity + omega * delta) * dt
        val decay = exp(-omega * dt)
        velocity = (velocity - omega * temp) * decay
        position = target + (delta + temp) * decay

        if (abs(position - target) < 0.0008 && abs(velocity) < 0.01) {
            position = target
            velocity = 0.0
            running = false
        } else {
            Choreographer.getInstance().postFrameCallback(this)
        }
        onUpdate(2.0.pow(position).toFloat(), velocity.toFloat())
    }
}
