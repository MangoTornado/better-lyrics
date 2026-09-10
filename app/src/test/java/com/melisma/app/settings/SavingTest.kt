package com.melisma.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which power-saving measures are in force.
 *
 * Worth testing away from a phone because the failures are invisible from the inside: a measure
 * applied when the phone is not saving is a feature quietly switched off for everybody, and one
 * that never applies is a setting that does nothing. Neither looks like a bug — it just looks like
 * the app.
 */
class SavingTest {

    private val defaults = Settings()

    @Test
    fun `nothing is given up while the phone is not saving`() {
        assertEquals(Saving.NONE, Saving.of(defaults, systemSaverOn = false))
        assertFalse(Saving.of(defaults, systemSaverOn = false).any)
    }

    @Test
    fun `the system saver alone does nothing when the app is told not to follow it`() {
        val settings = defaults.copy(followBatterySaver = false)
        assertEquals(Saving.NONE, Saving.of(settings, systemSaverOn = true))
    }

    @Test
    fun `by default the background stops and the screen stays on`() {
        val saving = Saving.of(defaults, systemSaverOn = true)
        assertTrue(saving.stillBackground)
        assertTrue(saving.skipPrefetch)
        // The one measure that would put the lyrics behind a dark screen mid-song has to be asked
        // for, never assumed.
        assertFalse(saving.releaseScreen)
        assertTrue(saving.any)
    }

    @Test
    fun `each measure can be turned down on its own`() {
        val settings = defaults.copy(
            saverStillBackground = false,
            saverReleaseScreen = true,
            saverSkipPrefetch = false,
        )
        val saving = Saving.of(settings, systemSaverOn = true)
        assertFalse(saving.stillBackground)
        assertTrue(saving.releaseScreen)
        assertFalse(saving.skipPrefetch)
    }

    @Test
    fun `following with every measure off is the same as not following`() {
        // Which matters for the wording on screen: "battery saver is on now, and these are in
        // force" would be a lie.
        val settings = defaults.copy(
            saverStillBackground = false,
            saverReleaseScreen = false,
            saverSkipPrefetch = false,
        )
        assertFalse(Saving.of(settings, systemSaverOn = true).any)
    }
}
