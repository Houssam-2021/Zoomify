package com.zoomify.camera

import com.zoomify.camera.FrameRateSupport.Dim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateSupportTest {
    private val fhd = Dim(1920, 1080)
    private val uhd = Dim(3840, 2160)
    private val hd = Dim(1280, 720)
    private val sizes = listOf(Dim(4000, 3000), uhd, Dim(2560, 1440), fhd, hd, Dim(640, 480))

    // A mid-range phone typical of a Galaxy A: 1080p streams at 60, 4K only at 30.
    private val galaxyLikeRanges = listOf(15 to 15, 24 to 24, 10 to 30, 15 to 30, 30 to 30, 60 to 60)
    private fun duration(d: Dim) = if (d.width * d.height > 1920 * 1080) 33_333_333L else 16_666_666L

    @Test fun prefersFixed60() = assertEquals(60 to 60, FrameRateSupport.choose60(listOf(30 to 60, 60 to 60, 15 to 30)))
    @Test fun acceptsHighFloorVariable60() = assertEquals(30 to 60, FrameRateSupport.choose60(listOf(15 to 30, 30 to 60)))
    @Test fun rejectsRangesThatMayDropBelow30() = assertNull(FrameRateSupport.choose60(listOf(15 to 60, 7 to 60, 30 to 30)))
    @Test fun noSixty() = assertNull(FrameRateSupport.choose60(listOf(15 to 30, 30 to 30)))

    @Test fun fhd60SupportedButNot4k60() {
        val fhdResult = FrameRateSupport.decide(galaxyLikeRanges, sizes, ::duration, { true }, fhd, fhd)
        assertEquals(60 to 60, fhdResult.first)
        val uhdResult = FrameRateSupport.decide(galaxyLikeRanges, sizes, ::duration, { true }, uhd, fhd)
        assertNull(uhdResult.first)
        assertTrue(uhdResult.second, uhdResult.second.contains("3840×2160"))
    }

    @Test fun detailPreviewTooBigFor60() {
        val r = FrameRateSupport.decide(galaxyLikeRanges, sizes, ::duration, { true }, fhd, Dim(2560, 1440))
        assertNull(r.first)
    }

    @Test fun encoderLimitBlocks60() {
        val r = FrameRateSupport.decide(galaxyLikeRanges, sizes, ::duration, { false }, fhd, fhd)
        assertNull(r.first)
        assertTrue(r.second.contains("encoder"))
    }

    @Test fun unknownDurationIsNotAssumedFast() {
        val r = FrameRateSupport.decide(galaxyLikeRanges, sizes, { 0L }, { true }, fhd, fhd)
        assertNull(r.first)
    }

    @Test fun noAe60Range() {
        val r = FrameRateSupport.decide(listOf(15 to 30, 30 to 30), sizes, ::duration, { true }, fhd, fhd)
        assertNull(r.first)
        assertTrue(r.second.contains("auto-exposure"))
    }

    @Test fun closestKeepsAspect() {
        assertEquals(fhd, FrameRateSupport.closest(sizes, fhd))
        assertEquals(fhd, FrameRateSupport.closest(listOf(Dim(1440, 1080), Dim(1920, 1088), fhd, hd), Dim(1920, 1080)))
        assertEquals(Dim(1920, 1088), FrameRateSupport.closest(listOf(Dim(1440, 1080), Dim(1920, 1088), hd), Dim(1920, 1080)))
    }
}

class FpsMeterTest {
    private fun feed(meter: FpsMeter, fps: Double, seconds: Double, dup: Boolean = false) {
        val n = (fps * seconds).toInt()
        for (i in 0 until n) {
            val t = 5_000_000_000L + (i * 1e9 / fps).toLong()
            meter.add(t)
            if (dup) meter.add(t)   // the same frame reported twice (e.g. two callbacks) must not count twice
        }
    }

    @Test fun measures60() = FpsMeter().let { feed(it, 60.0, 2.0); assertEquals(60.0, it.fps(), 1.0) }
    @Test fun measures30() = FpsMeter().let { feed(it, 30.0, 2.0); assertEquals(30.0, it.fps(), 1.0) }
    @Test fun duplicatesIgnored() = FpsMeter().let { feed(it, 30.0, 2.0, dup = true); assertEquals(30.0, it.fps(), 1.0) }
    @Test fun notEnoughData() = FpsMeter().let { it.add(1L); assertEquals(0.0, it.fps(), 0.0) }
}
