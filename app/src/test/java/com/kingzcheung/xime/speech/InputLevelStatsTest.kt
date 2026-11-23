package com.kingzcheung.xime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class InputLevelStatsTest {
    private fun tone(hz: Double, amplitude: Double, secs: Double = 1.0): ShortArray =
        ShortArray((16000 * secs).toInt()) { i -> (sin(2 * PI * hz * i / 16000) * amplitude * 32767).toInt().toShort() }

    @Test fun `a 1 kHz tone at a tenth of full scale reads about -23 dBFS with little low end`() {
        val stats = InputLevelStats()
        val t = tone(1000.0, 0.1)
        stats.add(t, t.size)
        assertEquals(-23.0, stats.speechDb()!!, 0.3)
        assertEquals(-23.0, stats.floorDb()!!, 0.3)
        assertEquals(-20.0, stats.peakDb(), 0.2)
        assertTrue("low share ${stats.lowShare()}", stats.lowShare() < 0.15)
        assertEquals(0L, stats.clippedSamples())
    }

    @Test fun `rumble below 300 Hz shows up as a high low-frequency share`() {
        val stats = InputLevelStats()
        val t = tone(80.0, 0.05)
        stats.add(t, t.size)
        assertTrue("low share ${stats.lowShare()}", stats.lowShare() > 0.8)
    }

    @Test fun `speech level and floor come from loud and quiet stretches`() {
        val stats = InputLevelStats()
        val quiet = tone(1000.0, 0.001, 2.0)
        val loud = tone(1000.0, 0.3, 0.5)
        stats.add(quiet, quiet.size)
        stats.add(loud, loud.size)
        assertEquals(-13.5, stats.speechDb()!!, 0.5)
        assertEquals(-63.0, stats.floorDb()!!, 0.5)
    }

    @Test fun `clipping is counted and nothing heard means no level`() {
        val stats = InputLevelStats()
        assertNull(stats.speechDb())
        val clip = ShortArray(1600) { Short.MAX_VALUE }
        stats.add(clip, clip.size)
        assertEquals(1600L, stats.clippedSamples())
        assertTrue(stats.summary().contains("clipped=1600"))
    }
}
