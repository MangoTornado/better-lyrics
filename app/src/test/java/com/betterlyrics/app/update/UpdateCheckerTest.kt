package com.betterlyrics.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version comparison, which decides whether the app offers to replace itself.
 *
 * Wrong in one direction and a fix never reaches anybody; wrong in the other and the app
 * nags about an update that does not exist. A string comparison gets `0.10.0` against
 * `0.9.0` backwards, which is the case that arrives quietly after nine releases.
 */
class UpdateCheckerTest {

    @Test
    fun `a later version is newer`() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun `double digits are compared as numbers`() {
        // The one a string comparison gets wrong.
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.10.0"))
        assertFalse(UpdateChecker.isNewer("2.0.0", "10.0.0"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        // Trailing zeros are the same version, not a later one.
        assertFalse(UpdateChecker.isNewer("0.1", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1"))
    }

    @Test
    fun `an earlier version is never offered`() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `a leading v is ignored`() {
        // Tags carry one; the app's own version does not.
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0"))
    }

    @Test
    fun `a suffix is read as its release`() {
        assertTrue(UpdateChecker.isNewer("0.2.0-beta1", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0-beta1", "0.1.0"))
    }

    @Test
    fun `an unreadable version is never newer`() {
        // Offering an update the user cannot make sense of is worse than staying quiet.
        assertFalse(UpdateChecker.isNewer("", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("latest", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "nightly"))
    }
}
