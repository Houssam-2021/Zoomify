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
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (w, x, y, z); as a rotation it maps camera coordinates to world coordinates. */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )
    fun conj() = Quat(w, -x, -y, -z)
    fun normalized(): Quat { val n = sqrt(w * w + x * x + y * y + z * z); return Quat(w / n, x / n, y / n, z / n) }
    fun rotate(v: DoubleArray): DoubleArray {
        val q = this * Quat(0.0, v[0], v[1], v[2]) * conj()
        return doubleArrayOf(q.x, q.y, q.z)
    }
    /** Row-major 3x3 rotation matrix. */
    fun matrix(): DoubleArray = doubleArrayOf(
        1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
        2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
        2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
    )

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)
        fun axisAngle(ax: Double, ay: Double, az: Double, angle: Double): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az)
            if (n < 1e-12) return IDENTITY
            val s = sin(angle / 2) / n
            return Quat(cos(angle / 2), ax * s, ay * s, az * s)
        }
        /** Rotation by a rotation vector (axis * angle). */
        fun fromRotationVector(rx: Double, ry: Double, rz: Double): Quat =
            axisAngle(rx, ry, rz, sqrt(rx * rx + ry * ry + rz * rz))
        fun slerp(a: Quat, b0: Quat, t: Double): Quat {
            var b = b0
            var d = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            if (d < 0) { b = Quat(-b.w, -b.x, -b.y, -b.z); d = -d }
            if (d > 0.9995) {
                return Quat(a.w + (b.w - a.w) * t, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
                    .normalized()
            }
            val th = acos(d.coerceIn(-1.0, 1.0))
            val sa = sin((1 - t) * th) / sin(th)
            val sb = sin(t * th) / sin(th)
            return Quat(sa * a.w + sb * b.w, sa * a.x + sb * b.x, sa * a.y + sb * b.y, sa * a.z + sb * b.z)
        }
        fun angleBetween(a: Quat, b: Quat): Double {
            val d = abs(a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z).coerceAtMost(1.0)
            return 2 * acos(d)
        }
        /** Shortest rotation taking unit vector [from] onto unit vector [to]. */
        fun between(from: DoubleArray, to: DoubleArray): Quat {
            val cx = from[1] * to[2] - from[2] * to[1]
            val cy = from[2] * to[0] - from[0] * to[2]
            val cz = from[0] * to[1] - from[1] * to[0]
            val dot = from[0] * to[0] + from[1] * to[1] + from[2] * to[2]
            if (dot < -0.999999) return axisAngle(1.0, 0.0, 0.0, PI).let { if (abs(from[0]) > 0.9) axisAngle(0.0, 1.0, 0.0, PI) else it }
            return Quat(1 + dot, cx, cy, cz).normalized()
        }
    }
}

/**
 * Geometry of gyro stabilisation, in the camera sensor's own image coordinates (x right, y down, z = optical axis
 * into the scene), which are also the camera texture's coordinates. Pure maths, unit-tested.
 */
object StabMath {
    /**
     * Back camera: a device-frame vector (Android sensor axes: x right, y up, z out of the screen) in camera
     * coordinates, for a sensor mounted with SENSOR_ORIENTATION [sensorDeg] (clockwise rotation to upright).
     */
    fun deviceToCamera(v: DoubleArray, sensorDeg: Int): DoubleArray {
        val t = Math.toRadians(sensorDeg.toDouble())
        val c = cos(t); val s = sin(t)
        return doubleArrayOf(c * v[0] - s * v[1], -s * v[0] - c * v[1], -v[2])
    }

    /** The roll (Surface.ROTATION_*) of the recording, as the clockwise roll at which it is upright. */
    fun nominal(rotation: Int): Double = when (rotation) {
        Surface.ROTATION_90 -> -PI / 2
        Surface.ROTATION_180 -> PI
        Surface.ROTATION_270 -> PI / 2
        else -> 0.0
    }

    /**
     * Rolls the virtual camera [v] about its optical axis so world-down appears as "down" for a recording upright at
     * clockwise roll [phi]. Returns null when the camera looks straight up/down (the horizon is undefined).
     */
    fun level(v: Quat, sensorDeg: Int, phi: Double): Quat? {
        val g = v.conj().rotate(doubleArrayOf(0.0, 0.0, -1.0))
        if (hypot(g[0], g[1]) < 0.2) return null
        val t = phi + Math.toRadians(sensorDeg.toDouble())
        val delta = atan2(g[1], g[0]) - atan2(cos(t), sin(t))
        return v * Quat.axisAngle(0.0, 0.0, 1.0, delta)
    }

    /**
     * Homography from virtual-camera texture coordinates to real-camera texture coordinates (both normalised 0..1),
     * column-major 3x3 for GL: H = N · K · R_real⁻¹ · V · K⁻¹ · N⁻¹.
     */
    fun warp(real: Quat, virtual: Quat, f: Double, width: Double, height: Double): FloatArray {
        val m = (real.conj() * virtual).matrix()   // row-major: virtual ray -> real ray
        val cx = width / 2; val cy = height / 2
        // K·M·K⁻¹ in pixels.
        val kInv = doubleArrayOf(1 / f, 0.0, -cx / f, 0.0, 1 / f, -cy / f, 0.0, 0.0, 1.0)
        val k = doubleArrayOf(f, 0.0, cx, 0.0, f, cy, 0.0, 0.0, 1.0)
        val px = mul(k, mul(m, kInv))
        // Normalise: pixels = diag(W, H, 1) · tex.
        val n = doubleArrayOf(1 / width, 0.0, 0.0, 0.0, 1 / height, 0.0, 0.0, 0.0, 1.0)
        val nInv = doubleArrayOf(width, 0.0, 0.0, 0.0, height, 0.0, 0.0, 0.0, 1.0)
        val h = mul(n, mul(px, nInv))
        return floatArrayOf(   // column-major
            h[0].toFloat(), h[3].toFloat(), h[6].toFloat(),
            h[1].toFloat(), h[4].toFloat(), h[7].toFloat(),
            h[2].toFloat(), h[5].toFloat(), h[8].toFloat(),
        )
    }

    fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    /** Applies a column-major homography to a point. */
    fun apply(h: FloatArray, x: Float, y: Float): FloatArray {
        val u = h[0] * x + h[3] * y + h[6]
        val v = h[1] * x + h[4] * y + h[7]
        val w = h[2] * x + h[5] * y + h[8]
        return floatArrayOf(u / w, v / w)
    }

    /** Same as the shader: homography for the row the sample lands on, between the top and bottom rows' ones. */
    fun applyRolling(top: FloatArray, bottom: FloatArray, x: Float, y: Float): FloatArray {
        val mid = FloatArray(9) { (top[it] + bottom[it]) / 2 }
        val a = apply(mid, x, y)
        val f = a[1].coerceIn(0f, 1f)
        val h = FloatArray(9) { top[it] * (1 - f) + bottom[it] * f }
        return apply(h, x, y)
    }
}

/**
 * Gyro-based 3-axis stabilisation with horizon lock (GoPro / Samsung style), done on the GPU.
 *
 * - Orientation: gyroscope integrated at the sensor's full rate, corrected towards gravity (so the horizon reference
 *   doesn't drift), kept as a timestamped history.
 * - Each frame is matched to the orientation at its own capture time, row by row (rolling shutter: the top and bottom
 *   rows are read out at different times), using the frame's timestamp, exposure and readout skew.
 * - A smoothed "virtual camera" follows intentional motion (adaptive cutoff: steady when held still, quicker when
 *   you pan) and is always rolled level with the horizon.
 * - Margin limit: the virtual camera is pulled towards the real one just enough that no frame edge ever shows.
 * - Hardware EIS/OIS are turned off while this runs, so the frames match the gyro (they'd fight otherwise).
 *
 * Phones can't measure sideways movement (translation) precisely, so this corrects rotation, which is most of
 * hand shake; walking bounce is reduced only as far as it shows up as rotation.
 */
class GyroStabilizer(context: Context) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravity: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val available: Boolean get() = gyro != null && gravity != null

    private var thread: HandlerThread? = null
    @Volatile var running = false; private set

    // Camera geometry (set on bind).
    @Volatile var sensorOrientation = 90
    @Volatile var cameraClockIsRealtime = true
    /** Focal length in active-array pixels at zoom 1, and the active array size. */
    @Volatile var focalActivePx = 0.0
    @Volatile var activeWidth = 4000
    @Volatile var activeHeight = 3000
    /** Gyro-to-camera timing offset (positive = camera timestamps are late). */
    @Volatile var timeOffsetNs = 0L

    // Per-frame camera metadata (from capture results).
    @Volatile var zoomRatio = 1f
    @Volatile var rollingShutterSkewNs = 0L
    @Volatile var exposureNs = 0L

    /** The orientation the video is recorded in (Surface.ROTATION_*). */
    @Volatile var targetRotation = Surface.ROTATION_0

    // Sensor thread state.
    private var orientation = Quat.IDENTITY
    private var hasOrientation = false
    private var lastGyroNs = 0L
    private var lastGravityNs = 0L
    private val history = QuatHistory()

    // GL thread state.
    private var virtual = Quat.IDENTITY
    private var virtualReady = false
    private var lastFrameNs = 0L
    private var lastReal = Quat.IDENTITY
    private var phi = 0.0
    private var lastLevelled = Quat.IDENTITY

    fun start() {
        if (running || !available) return
        running = true
        hasOrientation = false
        virtualReady = false
        history.clear()
        val t = HandlerThread("ZoomifyStabilizer").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_FASTEST, handler)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        history.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            if (!hasOrientation) return
            if (lastGyroNs != 0L) {
                val dt = ((event.timestamp - lastGyroNs) / 1e9).coerceIn(0.0, 0.05)
                val w = StabMath.deviceToCamera(v, sensorOrientation)
                orientation = (orientation * Quat.fromRotationVector(w[0] * dt, w[1] * dt, w[2] * dt)).normalized()
                history.add(event.timestamp, orientation)
            }
            lastGyroNs = event.timestamp
        } else {
            // Gravity sensor reads "up" in device axes; expressed in camera axes it must map to world +z.
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (n < 1e-3) return
            val upCam = StabMath.deviceToCamera(doubleArrayOf(v[0] / n, v[1] / n, v[2] / n), sensorOrientation)
            if (!hasOrientation) {
                orientation = Quat.between(upCam, doubleArrayOf(0.0, 0.0, 1.0))
                hasOrientation = true
            } else {
                // Complementary filter: slowly remove tilt drift (time constant ~0.8 s; the gyro handles the rest).
                val dt = ((event.timestamp - lastGravityNs) / 1e9).coerceIn(0.0, 0.1)
                val predicted = orientation.rotate(upCam)
                val ax = predicted[1]; val ay = -predicted[0]   // predicted × (0,0,1)
                val s = hypot(ax, ay)
                if (s > 1e-9) {
                    val err = atan2(s, predicted[2])
                    val k = 1 - exp(-dt / 0.8)
                    orientation = (Quat.axisAngle(ax, ay, 0.0, err * k) * orientation).normalized()
                }
            }
            lastGravityNs = event.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Called on the GL thread once per camera frame. [corners] are the texture coordinates the output's edge points
     * would sample without stabilisation (x0, y0, x1, y1, ...). Returns the top-row and bottom-row homographies
     * (2 × 9 floats, column-major), or null if there's no orientation data yet.
     */
    fun frameWarp(frameTimestampNs: Long, texWidth: Int, texHeight: Int, corners: FloatArray): FloatArray? {
        if (!running || focalActivePx <= 0 || texWidth <= 0 || texHeight <= 0) return null
        val base = (if (cameraClockIsRealtime) frameTimestampNs
        else frameTimestampNs + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())) - timeOffsetNs
        // Which sensor rows the stream covers (a 16:9 stream is a centred band of a 4:3 sensor).
        val used = min(1.0, activeWidth.toDouble() * texHeight / texWidth / activeHeight)
        val skew = rollingShutterSkewNs.toDouble()
        val start = base + exposureNs / 2.0 + skew * (1 - used) / 2
        val tTop = start.toLong()
        val tBottom = (start + skew * used).toLong()
        val tMid = (start + skew * used / 2).toLong()
        val realTop = history.at(tTop) ?: return null
        val realBottom = history.at(tBottom) ?: return null
        val real = history.at(tMid) ?: return null

        val f = focalActivePx * texWidth * zoomRatio / activeWidth   // focal length in stream pixels
        val target = StabMath.nominal(targetRotation)
        // Re-orienting (a deliberate portrait <-> landscape change) turns smoothly instead of jumping.
        val dPhi = HorizonMath.wrap(target - phi)
        phi = if (!virtualReady) target else phi + dPhi.coerceIn(-0.12, 0.12)

        val dt = if (lastFrameNs == 0L) 1 / 30.0 else ((tMid - lastFrameNs) / 1e9).coerceIn(1e-3, 0.2)
        lastFrameNs = tMid
        if (!virtualReady) { virtual = real; virtualReady = true; lastReal = real }
        // Adaptive smoothing: a low cutoff holds the view steady; turning faster raises it so pans follow.
        val speed = Quat.angleBetween(lastReal, real) / dt
        lastReal = real
        val cutoff = MIN_CUTOFF_HZ + BETA * speed
        val a = 1 - exp(-2 * PI * cutoff * dt)
        var smoothed = Quat.slerp(virtual, real, a)

        // Margin limit: pull towards the real orientation just enough that every edge point stays inside the frame.
        fun levelled(q: Quat) = StabMath.level(q, sensorOrientation, phi) ?: (q * lastRollOf(q))
        fun fits(q: Quat): Boolean {
            val v = levelled(q)
            val top = StabMath.warp(realTop, v, f, texWidth.toDouble(), texHeight.toDouble())
            val bottom = StabMath.warp(realBottom, v, f, texWidth.toDouble(), texHeight.toDouble())
            for (i in corners.indices step 2) {
                val p = StabMath.applyRolling(top, bottom, corners[i], corners[i + 1])
                if (p[0] < 0f || p[0] > 1f || p[1] < 0f || p[1] > 1f || p[0].isNaN()) return false
            }
            return true
        }
        if (!fits(smoothed)) {
            var lo = 0.0; var hi = 1.0
            repeat(10) {
                val mid = (lo + hi) / 2
                if (fits(Quat.slerp(smoothed, real, mid))) hi = mid else lo = mid
            }
            smoothed = Quat.slerp(smoothed, real, hi)
        }
        virtual = smoothed
        val v = levelled(smoothed)
        lastLevelled = smoothed.conj() * v
        val top = StabMath.warp(realTop, v, f, texWidth.toDouble(), texHeight.toDouble())
        val bottom = StabMath.warp(realBottom, v, f, texWidth.toDouble(), texHeight.toDouble())
        return top + bottom
    }

    /** When the horizon is undefined (camera pointing straight up/down), keep the last roll correction. */
    private fun lastRollOf(@Suppress("UNUSED_PARAMETER") q: Quat): Quat = lastLevelled

    companion object {
        /** Steady-hold cutoff and how fast it rises with rotation speed (Hz per rad/s). */
        const val MIN_CUTOFF_HZ = 0.35
        const val BETA = 1.2
    }
}

/** Timestamped orientations, interpolated (slerp) at any time inside the recent history. */
class QuatHistory(capacity: Int = 2048) {
    private val times = LongArray(capacity)
    private val quats = arrayOfNulls<Quat>(capacity)
    private var head = 0
    private var count = 0

    @Synchronized fun clear() { head = 0; count = 0 }

    @Synchronized fun add(t: Long, q: Quat) {
        if (count > 0 && t <= times[(head - 1 + times.size) % times.size]) return
        times[head] = t; quats[head] = q
        head = (head + 1) % times.size
        if (count < times.size) count++
    }

    /** Orientation at [t]; extrapolates up to 50 ms past the newest sample by holding it. Null if empty. */
    @Synchronized fun at(t: Long): Quat? {
        if (count == 0) return null
        val newest = (head - 1 + times.size) % times.size
        if (t >= times[newest]) return quats[newest]
        for (i in 1 until count) {
            val b = (newest - i + 1 + times.size) % times.size
            val a = (newest - i + times.size) % times.size
            if (times[a] <= t) {
                val f = (t - times[a]).toDouble() / max(1L, times[b] - times[a])
                return Quat.slerp(quats[a]!!, quats[b]!!, f)
            }
        }
        return quats[(newest - count + 1 + times.size) % times.size]
    }
}
