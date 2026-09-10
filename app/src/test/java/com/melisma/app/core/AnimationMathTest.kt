package com.melisma.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplineTest {

    /** The word-scale curve from Spicy Lyrics: rest, overshoot at 70%, settle. */
    private val wordScale = Spline(listOf(0f to 0.95f, 0.7f to 1.0505f, 1f to 1f))

    @Test
    fun `passes through its control points`() {
        assertEquals(0.95f, wordScale.at(0f), 1e-4f)
        assertEquals(1.0505f, wordScale.at(0.7f), 1e-4f)
        assertEquals(1f, wordScale.at(1f), 1e-4f)
    }

    @Test
    fun `overshoots between the peak and the end`() {
        // The whole point of the curve: a word swells past its resting size before
        // settling, so it reads as being struck rather than fading in.
        assertTrue(wordScale.at(0.5f) > 0.95f)
        assertTrue(wordScale.at(0.8f) > 1f)
    }

    @Test
    fun `clamps rather than extrapolating outside the range`() {
        assertEquals(wordScale.at(0f), wordScale.at(-3f), 1e-5f)
        assertEquals(wordScale.at(1f), wordScale.at(4f), 1e-5f)
    }
}

class SpringTest {

    @Test
    fun `settles on its goal`() {
        val spring = Spring(startPosition = 0f, frequency = 2f, dampingRatio = 1f, goal = 1f)
        repeat(240) { spring.step(1f / 60f) }
        assertEquals(1f, spring.value, 1e-3f)
        assertTrue(spring.canSleep())
    }

    @Test
    fun `an underdamped spring overshoots on the way`() {
        val spring = Spring(startPosition = 0f, frequency = 2f, dampingRatio = 0.3f, goal = 1f)
        var peak = 0f
        repeat(120) { peak = maxOf(peak, spring.step(1f / 60f)) }
        assertTrue("expected overshoot, peaked at $peak", peak > 1f)
    }

    @Test
    fun `a critically damped spring never overshoots`() {
        val spring = Spring(startPosition = 0f, frequency = 2f, dampingRatio = 1f, goal = 1f)
        repeat(240) {
            assertTrue(spring.step(1f / 60f) <= 1f + 1e-4f)
        }
    }

    @Test
    fun `one big step lands where many small ones do`() {
        // The solution is analytic, so a dropped frame must not change the outcome —
        // this is what keeps a stutter from desyncing the animation from the audio.
        val fine = Spring(0f, 1.5f, 0.7f, goal = 1f)
        repeat(30) { fine.step(1f / 60f) }

        val coarse = Spring(0f, 1.5f, 0.7f, goal = 1f)
        coarse.step(0.5f)

        assertEquals(fine.value, coarse.value, 1e-3f)
    }

    @Test
    fun `snapping clears velocity`() {
        val spring = Spring(0f, 2f, 0.4f, goal = 1f)
        repeat(10) { spring.step(1f / 60f) }
        spring.snapTo(5f)
        assertEquals(5f, spring.value, 0f)
        assertEquals(5f, spring.step(1f / 60f), 1e-6f)
        assertTrue(spring.canSleep())
    }
}
