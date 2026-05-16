package top.iwesley.lyn.music.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class EqualizerTest {
    @Test
    fun `formats frequency labels`() {
        assertEquals("31", formatEqualizerFrequencyLabel(31))
        assertEquals("125", formatEqualizerFrequencyLabel(125))
        assertEquals("1k", formatEqualizerFrequencyLabel(1_000))
        assertEquals("12k", formatEqualizerFrequencyLabel(12_000))
        assertEquals("1.5k", formatEqualizerFrequencyLabel(1_500))
    }

    @Test
    fun `converts millibels to decibels`() {
        assertEquals(0f, equalizerMillibelsToDecibels(0))
        assertEquals(12f, equalizerMillibelsToDecibels(1_200))
        assertEquals(-3.5f, equalizerMillibelsToDecibels(-350))
    }

    @Test
    fun `clamps level to range`() {
        assertEquals(1_200, clampEqualizerLevel(1_500, -1_200, 1_200))
        assertEquals(-1_200, clampEqualizerLevel(-1_500, -1_200, 1_200))
        assertEquals(400, clampEqualizerLevel(400, -1_200, 1_200))
    }
}
