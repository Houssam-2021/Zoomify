package com.zoomify.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Checks the stabiliser's geometry against an independent simulated world: real points on the horizon must come out
 * level in the upright picture, east on the right and up on top, for any roll, yaw, pitch and sensor mounting.
 */
class GyroStabilizerTest {
    // World: x = east, y = north, z = up. Phone upright in portrait, camera looking north:
    // device x = east, y = up, z = south.
    private val worldFromDevice0 by lazy {
        fromColumns(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0))
    }

    private fun fromColumns(c0: DoubleArray, c1: DoubleArray, c2: DoubleArray): Quat {
        val m00 = c0[0]; val m10 = c0[1]; val m20 = c0[2]
        val m01 = c1[0]; val m11 = c1[1]; val m21 = c1[2]
        val m02 = c2[0]; val m12 = c2[1]; val m22 = c2[2]
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            Quat(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            Quat((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            Quat((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s)
        }.normalized()
    }

    /** Camera -> world for a device pose (roll clockwise as seen by the user, plus yaw/pitch) and sensor mounting. */
    private fun cameraPose(rollCw: Double, yaw: Double, pitch: Double, sensorDeg: Int): Quat {
        val device = worldFromDevice0 * Quat.axisAngle(0.0, 1.0, 0.0, yaw) * Quat.axisAngle(1.0, 0.0, 0.0, pitch) *
            Quat.axisAngle(0.0, 0.0, 1.0, -rollCw)
        // Camera axes expressed in device coordinates (columns).
        val xc = StabMath.deviceToCamera(doubleArrayOf(1.0, 0.0, 0.0), sensorDeg)
        val yc = StabMath.deviceToCamera(doubleArrayOf(0.0, 1.0, 0.0), sensorDeg)
        val zc = StabMath.deviceToCamera(doubleArrayOf(0.0, 0.0, 1.0), sensorDeg)
        // deviceToCamera gives rows of D->C; device-from-camera columns are those rows transposed.
        val deviceFromCamera = fromColumns(
            doubleArrayOf(xc[0], yc[0], zc[0]), doubleArrayOf(xc[1], yc[1], zc[1]), doubleArrayOf(xc[2], yc[2], zc[2]),
        )
        return device * deviceFromCamera
    }

    private fun project(cam: Quat, p: DoubleArray, f: Double = 1000.0): DoubleArray {
        val c = cam.conj().rotate(p)
        return doubleArrayOf(f * c[0] / c[2], f * c[1] / c[2])
    }

    /** Sensor image vector -> upright (portrait) picture, y down. */
    private fun upright(v: DoubleArray, sensorDeg: Int): DoubleArray {
        val t = Math.toRadians(sensorDeg.toDouble())
        return doubleArrayOf(cos(t) * v[0] - sin(t) * v[1], sin(t) * v[0] + cos(t) * v[1])
    }

    @Test fun levelledHorizonForAnyRollYawPitchAndMounting() {
        for (sensor in listOf(90, 270, 0)) for (rollDeg in listOf(0, 10, -35, 120, 179, -150)) {
            val real = cameraPose(Math.toRadians(rollDeg.toDouble()), 0.1, -0.05, sensor)
            val v = StabMath.level(real, sensor, StabMath.nominal(Surface.ROTATION_0))!!
            val west = project(v, doubleArrayOf(-50.0, 1000.0, 0.0))
            val east = project(v, doubleArrayOf(50.0, 1000.0, 0.0))
            val h = upright(doubleArrayOf(east[0] - west[0], east[1] - west[1]), sensor)
            assertTrue("sensor $sensor roll $rollDeg: horizon not level ${h.toList()}", abs(h[1]) < 1e-6 * abs(h[0]))
            assertTrue("sensor $sensor roll $rollDeg: east not on the right", h[0] > 0)
            val low = project(v, doubleArrayOf(0.0, 1000.0, 0.0))
            val high = project(v, doubleArrayOf(0.0, 1000.0, 30.0))
            val up = upright(doubleArrayOf(high[0] - low[0], high[1] - low[1]), sensor)
            assertTrue("sensor $sensor roll $rollDeg: up is not up", up[1] < 0)
        }
    }

    @Test fun landscapeRecordingLevelsAlongTheLongSide() {
        val real = cameraPose(Math.toRadians(-90.0 + 7), 0.0, 0.0, 90)
        val v = StabMath.level(real, 90, StabMath.nominal(Surface.ROTATION_90))!!
        val west = project(v, doubleArrayOf(-50.0, 1000.0, 0.0))
        val east = project(v, doubleArrayOf(50.0, 1000.0, 0.0))
        val h = upright(doubleArrayOf(east[0] - west[0], east[1] - west[1]), 90)
        assertTrue(abs(h[0]) < 1e-6 * abs(h[1]) && h[1] > 0)
    }

    @Test fun warpMapsVirtualPixelsToWherePointsAreInTheRealFrame() {
        val f = 1400.0; val w = 1920.0; val hgt = 1080.0
        val real = cameraPose(0.3, 0.02, -0.04, 90)
        val virtual = cameraPose(0.25, -0.01, 0.0, 90)
        val warp = StabMath.warp(real, virtual, f, w, hgt)
        for (p in listOf(doubleArrayOf(10.0, 1000.0, 5.0), doubleArrayOf(-120.0, 900.0, -60.0), doubleArrayOf(80.0, 1100.0, 90.0))) {
            val pv = project(virtual, p, f); val pr = project(real, p, f)
            val tv = floatArrayOf(((pv[0] + w / 2) / w).toFloat(), ((pv[1] + hgt / 2) / hgt).toFloat())
            val out = StabMath.apply(warp, tv[0], tv[1])
            assertEquals((pr[0] + w / 2) / w, out[0].toDouble(), 1e-4)
            assertEquals((pr[1] + hgt / 2) / hgt, out[1].toDouble(), 1e-4)
        }
    }

    @Test fun identityWarpWhenVirtualEqualsReal() {
        val q = cameraPose(0.7, 0.1, 0.2, 90)
        val warp = StabMath.warp(q, q, 1500.0, 1920.0, 1080.0)
        val p = StabMath.apply(warp, 0.3f, 0.8f)
        assertEquals(0.3f, p[0], 1e-5f); assertEquals(0.8f, p[1], 1e-5f)
    }

    @Test fun gyroIntegrationInCameraAxesMatchesTheTruePose() {
        val sensor = 90
        val wDevice = doubleArrayOf(0.3, -0.7, 0.4)
        val wCam = StabMath.deviceToCamera(wDevice, sensor)
        var q = cameraPose(0.2, 0.0, 0.0, sensor)
        val dt = 0.001
        repeat(1000) { q = (q * Quat.fromRotationVector(wCam[0] * dt, wCam[1] * dt, wCam[2] * dt)).normalized() }
        // Truth: the device rotated about its own wDevice axis for 1 s.
        val n = sqrt(wDevice.sumOf { it * it })
        val truthDevice = worldFromDevice0 * Quat.axisAngle(0.0, 0.0, 1.0, -0.2) *
            Quat.axisAngle(wDevice[0], wDevice[1], wDevice[2], n)
        val start = cameraPose(0.2, 0.0, 0.0, sensor)
        val deviceFromCamera = (worldFromDevice0 * Quat.axisAngle(0.0, 0.0, 1.0, -0.2)).conj() * start
        assertTrue(Quat.angleBetween(q, truthDevice * deviceFromCamera) < 1e-6)
    }

    @Test fun historySlerpsBetweenSamples() {
        val h = QuatHistory()
        h.add(0, Quat.IDENTITY)
        h.add(1_000_000, Quat.axisAngle(0.0, 0.0, 1.0, 0.2))
        val mid = h.at(500_000)!!
        assertEquals(0.1, Quat.angleBetween(Quat.IDENTITY, mid), 1e-9)
    }

    @Test fun nominalSlewNeverJumps() {
        var a = 0.0
        val target = -PI / 2
        var steps = 0
        while (abs(HorizonMath.wrap(target - a)) > 1e-9) {
            val next = HorizonMath.slew(a, target, 0.05)
            assertTrue(abs(HorizonMath.wrap(next - a)) <= 0.05 + 1e-12)
            a = next; steps++
        }
        assertEquals(32, steps)   // 90° in 0.05 rad steps
        // Across ±180° it takes the short way round.
        assertEquals(-PI + 0.03, HorizonMath.slew(PI - 0.02, -PI + 0.1, 0.05), 1e-9)
    }
}
