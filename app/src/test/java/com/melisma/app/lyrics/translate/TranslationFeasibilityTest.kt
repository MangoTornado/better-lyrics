package com.melisma.app.lyrics.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether the translate button should be offered at all.
 *
 * It has been wrong in both directions. Offering it when nothing can come of it makes a
 * button that does nothing; hiding it because the alphabet was Latin made every Spanish,
 * French and German song untranslatable — which is most songs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationFeasibilityTest {

    @Test
    fun `a different language can be translated`() {
        assertTrue(LyricsTranslator.canTranslate("ja", "en"))
        assertTrue(LyricsTranslator.canTranslate("es", "en"))
        assertTrue(LyricsTranslator.canTranslate("en", "ja"))
    }

    @Test
    fun `the same language cannot`() {
        // Nothing to do, so nothing should be offered.
        assertFalse(LyricsTranslator.canTranslate("en", "en"))
        // Regional variants are the same language for this purpose.
        assertFalse(LyricsTranslator.canTranslate("en-GB", "en"))
        assertFalse(LyricsTranslator.canTranslate("zh-CN", "zh"))
    }

    @Test
    fun `an unknown language cannot`() {
        // Identification failed or the lyrics were empty; do not promise anything.
        assertFalse(LyricsTranslator.canTranslate(null, "en"))
        assertFalse(LyricsTranslator.canTranslate("und", "en"))
    }

    @Test
    fun `a language ML Kit does not support cannot`() {
        assertFalse(LyricsTranslator.canTranslate("ja", "xx"))
        assertFalse(LyricsTranslator.canTranslate("xx", "en"))
    }
}
