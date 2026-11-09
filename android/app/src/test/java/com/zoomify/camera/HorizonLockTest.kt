package com.zoomify.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class HorizonMathTest {
    private val deg = PI / 180

    @Test fun rollFromGravity() {
        assertEquals(0.0, HorizonMath.roll(0.0, 9.81), 1e-9)                 // upright portrait
        assertEquals(PI / 2, HorizonMath.roll(-9.81, 0.0), 1e-9)             // rolled 90° clockwise (top to the right)
        assertEquals(-PI / 2, HorizonMath.roll(9.81, 0.0), 1e-9)             // rolled 90° counter-clockwise
        assertEquals(10 * deg, HorizonMath.roll(-9.81 * sin(10 * deg), 9.81 * cos(10 * deg)), 1e-9)
    }

    @Test fun landscapeRecordingIsLevelWhenHeldLandscape() {
        // Phone rolled 90° counter-clockwise = ROTATION_90: relative roll must be 0.
        val rel = HorizonMath.wrap(HorizonMath.roll(9.81, 0.0) - HorizonMath.nominal(Surface.ROTATION_90))
        assertEquals(0.0, rel, 1e-9)
    }

    @Test fun fullLockCropValue() = assertEquals(sqrt(1 + (16.0 / 9) * (16.0 / 9)), HorizonMath.fullLockCrop(16.0 / 9), 1e-12)

    /** Same mapping as the shader: an output point d (aspect units) samples the source at R(-θ)·d / crop. */
    @Test fun fullLockNeverShowsCornersAtAnyAngle() {
        for (aspect in listOf(16.0 / 9, 9.0 / 16, 4.0 / 3, 1.0)) {
            val crop = HorizonMath.fullLockCrop(aspect)
            for (step in 0 until 3600) {
                val theta = step * 0.1 * deg
                for ((x, y) in listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1)) {
                    val dx = x * aspect / 2
                    val dy = y * 0.5
                    val sx = (cos(-theta) * dx - sin(-theta) * dy) / crop
                    val sy = (sin(-theta) * dx + cos(-theta) * dy) / crop
                    assertTrue("aspect $aspect angle ${step / 10.0}", abs(sx) <= aspect / 2 + 1e-9 && abs(sy) <= 0.5 + 1e-9)
                }
            }
        }
    }

    @Test fun fullLockIsTheWorstCase() {
        val full = HorizonMath.fullLockCrop(16.0 / 9)
        var worst = 0.0
        for (step in 0 until 3600) worst = maxOf(worst, HorizonMath.cropFor(step * 0.1 * deg, 16.0 / 9))
        assertEquals(full, worst, 1e-4)
    }

    @Test fun wrap() {
        assertEquals(-179 * deg, HorizonMath.wrap(181 * deg), 1e-9)
        assertEquals(179 * deg, HorizonMath.wrap(-181 * deg), 1e-9)
        assertEquals(10 * deg, HorizonMath.wrap(370 * deg), 1e-9)
    }

    @Test fun outputSigns() {
        assertEquals(1f, HorizonMath.outputSign(frontCamera = false, mirroredOutput = false))
        assertEquals(-1f, HorizonMath.outputSign(frontCamera = true, mirroredOutput = false))
        assertEquals(1f, HorizonMath.outputSign(frontCamera = true, mirroredOutput = true))
        assertEquals(-1f, HorizonMath.outputSign(frontCamera = false, mirroredOutput = true))
    }
}

class OneEuroAngleTest {
    private val deg = PI / 180
    private val dtNs = 2_500_000L   // 400 Hz sensor

    @Test fun removesJitterWhenStill() {
        val f = OneEuroAngle(HorizonLock.GRAVITY_MIN_CUTOFF, HorizonLock.GRAVITY_BETA)
        val rnd = Random(1)
        val outs = ArrayList<Double>()
        for (i in 0 until 4000) {
            val out = f.filter(5 * deg + rnd.nextGaussian() * 0.5 * deg, i * dtNs)
            if (i > 400) outs += out
        }
        val mean = outs.average()
        val std = sqrt(outs.sumOf { (it - mean) * (it - mean) } / outs.size)
        assertEquals(5 * deg, mean, 0.05 * deg)
        assertTrue("jitter ${std / deg}° (input 0.5°)", std < 0.2 * deg)
    }

    private fun maxLag(degPerSecond: Double): Double {
        val f = OneEuroAngle(HorizonLock.GRAVITY_MIN_CUTOFF, HorizonLock.GRAVITY_BETA)
        var lag = 0.0
        for (i in 0 until 1200) {                                  // 3 s of steady turning
            val truth = HorizonMath.wrap(i * dtNs / 1e9 * degPerSecond * deg)
            val out = f.filter(truth, i * dtNs)
            if (i > 400) lag = maxOf(lag, abs(HorizonMath.wrap(out - truth)))
        }
        return lag / deg
    }

    @Test fun littleLagWhenTurning() {
        val normal = maxLag(45.0)
        val fast = maxLag(180.0)
        assertTrue("lag at 45°/s: $normal°", normal < 1.5)
        assertTrue("lag at 180°/s: $fast°", fast < 4.0)
    }

    @Test fun noSpinAcross180() {
        val f = OneEuroAngle(HorizonLock.GRAVITY_MIN_CUTOFF, HorizonLock.GRAVITY_BETA)
        var prev = f.filter(170 * deg, 0)
        for (i in 1..400) {
            val out = f.filter(HorizonMath.wrap((170 + i * 0.05) * deg), i * dtNs)   // slowly passes ±180°
            assertTrue("jump at $i", abs(HorizonMath.wrap(out - prev)) < 1 * deg)
            prev = out
        }
    }
}

class AngleHistoryTest {
    private val deg = PI / 180

    @Test fun interpolatesAtFrameTime() {
        val h = AngleHistory()
        h.add(1_000, 0.0); h.add(2_000, 10 * deg); h.add(3_000, 20 * deg)
        assertEquals(15 * deg, h.at(2_500), 1e-9)
        assertEquals(20 * deg, h.at(9_000), 1e-9)   // newer than history: latest
        assertEquals(0.0, h.at(10), 1e-9)           // older than history: oldest
    }

    @Test fun interpolatesAcross180() {
        val h = AngleHistory()
        h.add(1_000, 179 * deg); h.add(2_000, -179 * deg)
        assertEquals(PI, abs(h.at(1_500)), 1e-9)
    }
}

class HorizonLockEisTuningTest {
    private val deg = PI / 180
    private val dtNs = 2_500_000L

    private fun tremorFollowed(minCutoff: Double, beta: Double): Double {
        val f = OneEuroAngle(minCutoff, beta)
        var peak = 0.0
        for (i in 0 until 4000) {
            val truth = 0.25 * deg * sin(2 * PI * 9 * i * dtNs / 1e9)   // small 9 Hz hand tremor
            val out = f.filter(truth, i * dtNs)
            if (i > 800) peak = maxOf(peak, abs(out))
        }
        return peak / (0.25 * deg)
    }

    private fun rotationLag(minCutoff: Double, beta: Double, degPerSecond: Double): Double {
        val f = OneEuroAngle(minCutoff, beta)
        var lag = 0.0
        for (i in 0 until 1600) {
            val truth = HorizonMath.wrap(i * dtNs / 1e9 * degPerSecond * deg)
            val out = f.filter(truth, i * dtNs)
            if (i > 600) lag = maxOf(lag, abs(HorizonMath.wrap(out - truth)))
        }
        return lag / deg
    }

    @Test fun withEisIgnoresTremorButStillFollowsRotation() {
        val tremor = tremorFollowed(HorizonLock.EIS_MIN_CUTOFF, HorizonLock.EIS_BETA)
        assertTrue("follows ${tremor * 100}% of tremor", tremor < 0.2)
        assertTrue(rotationLag(HorizonLock.EIS_MIN_CUTOFF, HorizonLock.EIS_BETA, 90.0) <
            rotationLag(HorizonLock.GRAVITY_MIN_CUTOFF, HorizonLock.GRAVITY_BETA, 90.0) + 0.1)
    }
}
