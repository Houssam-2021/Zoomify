package com.zoomify.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Horizon lock in the style of Samsung's: the horizon stays level however far the phone is rolled, a full 360°.
 *
 * - The phone's roll comes from the gravity sensor (gyro-fused on most phones), read at the sensor's fastest rate on
 *   its own thread and cleaned by a One Euro filter: steady when the phone is still, no lag when it turns.
 * - Every camera frame is levelled with the roll measured at the moment that frame was captured (from a short
 *   timestamped history), not the latest reading, so the picture doesn't wobble during fast turns.
 * - The crop is fixed at the size that never shows a corner at any angle (≈2.04× for 16:9), so the picture doesn't
 *   "breathe" as you rotate. Zoomify switches to the ultra-wide when horizon lock is turned on to compensate, like
 *   Samsung does.
 * - The recording's orientation is fixed when recording starts; the horizon is held level relative to it.
 */
class HorizonLock(context: Context, private val onCrop: (crop: Float) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gravity: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val sensor: Sensor? = gravity ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    @Volatile private var running = false

    // The raw accelerometer also measures hand movement, so it gets stronger smoothing than the fused gravity sensor.
    private val filter = if (gravity != null) OneEuroAngle(minCutoffHz = GRAVITY_MIN_CUTOFF, beta = GRAVITY_BETA)
    else OneEuroAngle(minCutoffHz = 1.5, beta = 0.8)
    private val history = AngleHistory()

    /** The orientation the video is being recorded in (Surface.ROTATION_*). */
    @Volatile var targetRotation = Surface.ROTATION_0

    /** True if the camera's frame timestamps use the same clock as sensor events (elapsedRealtime). */
    @Volatile var cameraClockIsRealtime = true

    /**
     * True while the camera's own EIS is stabilising the frames. EIS already removes small, fast shakes (including
     * some roll), so horizon lock then follows only the slower, larger rotation instead of re-adding that jitter.
     */
    @Volatile var cooperativeWithEis = false
    private var nominalNow = 0.0
    private var lastEventNs = 0L

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = true
        filter.reset()
        history.clear()
        nominalNow = HorizonMath.nominal(targetRotation)
        lastEventNs = 0L
        onCrop(HorizonMath.fullLockCrop(16.0 / 9.0).toFloat())
        val t = HandlerThread("ZoomifyHorizon").also { it.start() }
        thread = t
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
    }

    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        history.clear()
        onCrop(1f)
    }

    /**
     * Counter-rotation (radians, counter-clockwise in the image as seen by the viewer of an un-mirrored back-camera
     * picture) for a camera frame captured at [frameTimestampNs] (SurfaceTexture / sensor timestamp).
     */
    fun angleAt(frameTimestampNs: Long): Float {
        if (!running) return 0f
        val t = if (cameraClockIsRealtime) frameTimestampNs
        else frameTimestampNs + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        return history.at(t).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0].toDouble()
        val gy = event.values[1].toDouble()
        val gz = event.values[2].toDouble()
        // Pointing straight up/down (or lying flat): roll is undefined, so hold the last value.
        if (hypot(gx, gy) < 0.3 * sqrt(gx * gx + gy * gy + gz * gz)) return
        val roll = HorizonMath.roll(gx, gy)
        // A deliberate portrait <-> landscape change turns the reference smoothly (~0.35 s for 90°), never a jump.
        val dt = if (lastEventNs == 0L) 0.0 else ((event.timestamp - lastEventNs) / 1e9).coerceIn(0.0, 0.1)
        lastEventNs = event.timestamp
        nominalNow = HorizonMath.slew(nominalNow, HorizonMath.nominal(targetRotation), NOMINAL_SLEW_RAD_S * dt)
        val relative = HorizonMath.wrap(roll - nominalNow)
        if (gravity != null) {
            if (cooperativeWithEis) filter.tune(EIS_MIN_CUTOFF, EIS_BETA) else filter.tune(GRAVITY_MIN_CUTOFF, GRAVITY_BETA)
        }
        val smoothed = filter.filter(relative, event.timestamp)
        // The phone rolled clockwise, so the scene appears rotated counter-clockwise: undo it.
        history.add(event.timestamp, -smoothed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** One Euro tuning for the (gyro-fused) gravity sensor: ~5 Hz when still, faster the quicker you turn. */
        const val GRAVITY_MIN_CUTOFF = 5.0
        const val GRAVITY_BETA = 2.0
        /** With hardware EIS active: ignore small fast roll jitter (EIS has it), still follow real rotation. */
        const val EIS_MIN_CUTOFF = 0.8
        const val EIS_BETA = 4.0
        const val NOMINAL_SLEW_RAD_S = 4.5
    }
}

object HorizonMath {
    /** Clockwise roll of the phone seen from the screen (0 = upright portrait), from gravity in device axes. */
    fun roll(gx: Double, gy: Double): Double = atan2(-gx, gy)

    /** The roll at which a recording in this orientation is level. */
    fun nominal(rotation: Int): Double = when (rotation) {
        Surface.ROTATION_90 -> -PI / 2
        Surface.ROTATION_180 -> PI
        Surface.ROTATION_270 -> PI / 2
        else -> 0.0
    }

    /** Moves [from] towards [to] by at most [maxStep] along the shorter way round. */
    fun slew(from: Double, to: Double, maxStep: Double): Double = wrap(from + wrap(to - from).coerceIn(-maxStep, maxStep))

    fun wrap(a: Double): Double {
        var x = a % (2 * PI)
        if (x > PI) x -= 2 * PI
        if (x < -PI) x += 2 * PI
        return x
    }

    /** Zoom needed so a frame of this aspect, rotated by [angle], still covers the whole output. */
    fun cropFor(angle: Double, aspect: Double): Double {
        val ratio = if (aspect >= 1) aspect else 1 / aspect
        val a = abs(wrap(angle))
        val folded = if (a > PI / 2) PI - a else a
        return cos(folded) + sin(folded) * ratio
    }

    /** The smallest fixed zoom that covers the output at every angle: √(1 + r²), the worst case of [cropFor]. */
    fun fullLockCrop(aspect: Double): Double {
        val r = if (aspect >= 1) aspect else 1 / aspect
        return sqrt(1 + r * r)
    }

    /**
     * Sign to apply in an output's own coordinates. The raw back-camera picture behaves like a window (+1); the raw
     * front-camera picture is seen from the opposite side (-1); a mirrored output flips it again.
     */
    fun outputSign(frontCamera: Boolean, mirroredOutput: Boolean): Float =
        (if (frontCamera) -1f else 1f) * (if (mirroredOutput) -1f else 1f)
}

/**
 * One Euro filter (Casiez et al.) on an angle, unwrapped so passing ±180° doesn't make it spin the long way round.
 * Low cutoff when still (removes sensor noise), higher cutoff as the angle changes faster (no lag when turning).
 */
class OneEuroAngle(private var minCutoffHz: Double, private var beta: Double, private val dCutoffHz: Double = 1.0) {
    fun tune(minCutoffHz: Double, beta: Double) { this.minCutoffHz = minCutoffHz; this.beta = beta }

    private var x = 0.0
    private var dx = 0.0
    private var lastNs = 0L
    private var ready = false

    fun reset() { ready = false }

    /** Returns the filtered angle, wrapped to (-π, π]. */
    fun filter(angle: Double, timestampNs: Long): Double {
        if (!ready || timestampNs <= lastNs) {
            if (!ready) { x = angle; dx = 0.0; ready = true; lastNs = timestampNs }
            return HorizonMath.wrap(x)
        }
        val dt = ((timestampNs - lastNs) / 1e9).coerceIn(1e-4, 0.25)
        lastNs = timestampNs
        val target = x + HorizonMath.wrap(angle - x)   // unwrap next to the current estimate
        val rawDx = (target - x) / dt
        dx += alpha(dCutoffHz, dt) * (rawDx - dx)
        val cutoff = minCutoffHz + beta * abs(dx)
        x += alpha(cutoff, dt) * (target - x)
        return HorizonMath.wrap(x)
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double = 1.0 - exp(-2 * PI * cutoffHz * dt)
}

/** Timestamped angles (unwrapped internally), interpolated at a camera frame's timestamp. */
class AngleHistory(capacity: Int = 1024) {
    private val times = LongArray(capacity)
    private val angles = DoubleArray(capacity)
    private var head = 0
    private var count = 0

    @Synchronized fun clear() { head = 0; count = 0 }

    @Synchronized fun add(timestampNs: Long, angle: Double) {
        if (count > 0) {
            val last = (head - 1 + times.size) % times.size
            if (timestampNs <= times[last]) return
            angles[head] = angles[last] + HorizonMath.wrap(angle - angles[last])
        } else angles[head] = angle
        times[head] = timestampNs
        head = (head + 1) % times.size
        if (count < times.size) count++
    }

    /** Angle at [t]: interpolated between samples, the newest one if [t] is later, the oldest one if earlier. */
    @Synchronized fun at(t: Long): Double {
        if (count == 0) return 0.0
        val newest = (head - 1 + times.size) % times.size
        if (t >= times[newest]) return HorizonMath.wrap(angles[newest])
        for (i in 1 until count) {
            val b = (newest - i + 1 + times.size) % times.size
            val a = (newest - i + times.size) % times.size
            if (times[a] <= t) {
                val f = (t - times[a]).toDouble() / (times[b] - times[a]).coerceAtLeast(1)
                return HorizonMath.wrap(angles[a] + (angles[b] - angles[a]) * f)
            }
        }
        return HorizonMath.wrap(angles[(newest - count + 1 + times.size) % times.size])
    }
}
