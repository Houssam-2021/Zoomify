package com.zoomify.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps the horizon level: reads the phone's roll from the gravity sensor and counter-rotates the frame on the GPU.
 *
 * The frame is cropped by a fixed amount, enough to fully correct up to [MAX_DEGREES] of tilt without showing
 * corners (a fixed crop, so the picture doesn't "breathe" as you tilt). Bigger tilts are corrected up to that limit.
 */
class HorizonLock(context: Context, private val onUpdate: (angle: Float, crop: Float) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false
    private var smoothed = 0.0
    private var initialised = false

    /** The orientation the video is being recorded in (Surface.ROTATION_*). */
    @Volatile var targetRotation = Surface.ROTATION_0

    /** Width / height of the recorded frame as it is played back (e.g. 9/16 for portrait). */
    @Volatile var outputAspect = 9f / 16f

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = true
        initialised = false
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        onUpdate(0f, 1f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0].toDouble()
        val gy = event.values[1].toDouble()
        if (abs(gx) + abs(gy) < 2.0) return   // phone lying flat: roll is undefined, keep the last value
        // Clockwise roll of the phone, seen from the screen side (0 = upright portrait).
        val roll = atan2(-gx, gy)
        val nominal = when (targetRotation) {
            Surface.ROTATION_90 -> -PI / 2
            Surface.ROTATION_180 -> PI
            Surface.ROTATION_270 -> PI / 2
            else -> 0.0
        }
        var relative = roll - nominal
        while (relative > PI) relative -= 2 * PI
        while (relative < -PI) relative += 2 * PI

        // Low-pass to remove hand tremor in the sensor; the camera's own stabilisation handles the rest.
        smoothed = if (!initialised) relative else smoothed + (relative - smoothed) * 0.25
        initialised = true

        val limit = MAX_DEGREES * PI / 180
        val corrected = smoothed.coerceIn(-limit, limit)
        // The phone rolled clockwise by `corrected`, so the scene appears rotated counter-clockwise: undo it.
        onUpdate((-corrected).toFloat(), cropFor(limit, outputAspect.toDouble()).toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val MAX_DEGREES = 15.0

        /** Zoom needed so a frame of this aspect, rotated by [angle], still covers the whole output. */
        fun cropFor(angle: Double, aspect: Double): Double {
            val ratio = if (aspect >= 1) aspect else 1 / aspect
            return cos(abs(angle)) + sin(abs(angle)) * ratio
        }
    }
}
