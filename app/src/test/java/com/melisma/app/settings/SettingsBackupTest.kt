package com.melisma.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings out to a file and back.
 *
 * Two things have to hold, and they pull in opposite directions. A restore must give back exactly
 * what was there — a font scale that comes back as an integer, or an enabled-source set that comes
 * back as a string, is a setting silently reset on a new phone. And credentials must not leave the
 * device in a form anybody can read, because the app tells the user they never do.
 */
class SettingsBackupTest {

    /** One of each type SharedPreferences can hold, since JSON distinguishes none of them. */
    private val settings = mapOf(
        "keep_screen_on" to true,
        "sync_offset_ms" to -250,
        "last_update_check" to 1_759_000_000_000L,
        "font_scale" to 1.15f,
        "provider_order" to "local,amll,netease",
        "providers_on" to setOf("local", "amll", "netease"),
    )

    private val credentials = mapOf(
        "sp_dc" to "AQD-not-a-real-cookie",
        "apple_dev_token" to "eyJ-not-a-real-token",
        "sp_access_token_expiry" to 1_759_003_600_000L,
    )

    @Test
    fun `settings survive the round trip with their types`() {
        val file = SettingsBackup.write(settings)
        val restored = SettingsBackup.read(file)

        assertTrue(restored is SettingsBackup.Restore.Ready)
        val ready = restored as SettingsBackup.Restore.Ready
        assertEquals(settings, ready.settings)
        // Types, not just values: 1.15f read back as a Double or an Int would compare unequal above,
        // but spelling it out says which mistake this is guarding against.
        assertTrue(ready.settings["font_scale"] is Float)
        assertTrue(ready.settings["sync_offset_ms"] is Int)
        assertTrue(ready.settings["last_update_check"] is Long)
        assertTrue(ready.settings["providers_on"] is Set<*>)
    }

    @Test
    fun `a settings-only file needs no passphrase and carries no credentials`() {
        val file = SettingsBackup.write(settings)
        assertFalse(SettingsBackup.holdsCredentials(file))
        val ready = SettingsBackup.read(file) as SettingsBackup.Restore.Ready
        assertTrue(ready.credentials.isEmpty())
    }

    @Test
    fun `tokens are never in the file in the clear`() {
        val file = SettingsBackup.write(settings, credentials, passphrase = "correct horse battery")
        // The whole point. If this ever fails, a live Spotify cookie is sitting in Downloads.
        assertFalse(file.contains("AQD-not-a-real-cookie"))
        assertFalse(file.contains("eyJ-not-a-real-token"))
        assertTrue(SettingsBackup.holdsCredentials(file))
    }

    @Test
    fun `the right passphrase opens them`() {
        val file = SettingsBackup.write(settings, credentials, passphrase = "correct horse battery")
        val ready = SettingsBackup.read(file, "correct horse battery") as SettingsBackup.Restore.Ready
        assertEquals(credentials, ready.credentials)
        assertEquals(settings, ready.settings)
    }

    @Test
    fun `a wrong passphrase is refused rather than half-read`() {
        val file = SettingsBackup.write(settings, credentials, passphrase = "correct horse battery")
        assertEquals(
            SettingsBackup.Restore.WrongPassphrase,
            SettingsBackup.read(file, "correct horse batteru"),
        )
    }

    @Test
    fun `a file with tokens says so before it says anything else`() {
        val file = SettingsBackup.write(settings, credentials, passphrase = "correct horse battery")
        // Asked for without a passphrase: the settings are readable, but the app must ask rather than
        // silently restoring half of what the user chose.
        assertEquals(SettingsBackup.Restore.NeedsPassphrase, SettingsBackup.read(file))
    }

    @Test
    fun `credentials cannot be written without a passphrase`() {
        // A caller that let this through would be writing live tokens to a plain file.
        val failed = runCatching { SettingsBackup.write(settings, credentials, passphrase = null) }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { SettingsBackup.write(settings, credentials, "  ") }.isFailure)
    }

    @Test
    fun `two exports of the same tokens do not look alike`() {
        val a = SettingsBackup.write(settings, credentials, passphrase = "same passphrase")
        val b = SettingsBackup.write(settings, credentials, passphrase = "same passphrase")
        // A fresh salt and nonce each time. Identical ciphertext would leak that nothing changed
        // between two backups, and reusing a GCM nonce under one key is a break, not a smell.
        assertNotEquals(a, b)
    }

    @Test
    fun `anything that is not one of ours is refused`() {
        assertEquals(SettingsBackup.Restore.NotABackup, SettingsBackup.read(""))
        assertEquals(SettingsBackup.Restore.NotABackup, SettingsBackup.read("not json at all"))
        assertEquals(SettingsBackup.Restore.NotABackup, SettingsBackup.read("""{"hello":"world"}"""))
        // A file from a later version: some of it would restore and the rest would vanish, which is
        // worse than refusing.
        assertEquals(
            SettingsBackup.Restore.NotABackup,
            SettingsBackup.read("""{"format":"melisma-settings","version":99,"settings":{}}"""),
        )
    }

    @Test
    fun `an absurd iteration count is refused rather than run`() {
        val file = SettingsBackup.write(settings, credentials, passphrase = "correct horse battery")
        val hostile = file.replace("\"iterations\": 600000", "\"iterations\": 999999999")
        assertNotEquals("the fixture must actually have been edited", file, hostile)
        assertEquals(
            SettingsBackup.Restore.WrongPassphrase,
            SettingsBackup.read(hostile, "correct horse battery"),
        )
    }

    @Test
    fun `an unknown key rides along rather than breaking the file`() {
        // A backup written by a later version, restored onto this one: the keys it does not know are
        // stored and never read, which is harmless, and refusing the whole file would not be.
        val file = SettingsBackup.write(settings + ("something_new" to "value"))
        val ready = SettingsBackup.read(file) as SettingsBackup.Restore.Ready
        assertEquals("value", ready.settings["something_new"])
    }
}
