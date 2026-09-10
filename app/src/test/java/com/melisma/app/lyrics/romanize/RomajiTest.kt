package com.melisma.app.lyrics.romanize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiTest {

    @Test
    fun `basic kana`() {
        assertEquals("konnichiha", Romaji.fromKana("コンニチハ"))
        assertEquals("sakura", Romaji.fromKana("さくら"))
    }

    @Test
    fun `hiragana and katakana produce the same reading`() {
        assertEquals(Romaji.fromKana("かたかな"), Romaji.fromKana("カタカナ"))
    }

    @Test
    fun `digraphs stay together`() {
        assertEquals("kyou", Romaji.fromKana("キョウ"))
        assertEquals("shashin", Romaji.fromKana("シャシン"))
        assertEquals("jaanaru", Romaji.fromKana("ジャーナル"))
    }

    @Test
    fun `sokuon doubles the following consonant`() {
        assertEquals("kitto", Romaji.fromKana("キット"))
        assertEquals("kissaten", Romaji.fromKana("キッサテン"))
    }

    @Test
    fun `sokuon before chi is written tchi, not cchi`() {
        assertEquals("matchi", Romaji.fromKana("マッチ"))
    }

    @Test
    fun `long vowel mark repeats the vowel so the held beat is visible`() {
        assertEquals("raamen", Romaji.fromKana("ラーメン"))
        assertEquals("koohii", Romaji.fromKana("コーヒー"))
    }

    @Test
    fun `characters with no reading pass through untouched`() {
        assertEquals("a!", Romaji.fromKana("ア!"))
    }

    @Test
    fun `pure kana is recognised so the dictionary can be skipped`() {
        assertTrue(Romaji.isPureKana("ラーメン"))
        assertTrue(Romaji.isPureKana("さくら、"))
        assertFalse(Romaji.isPureKana("桜"))
    }
}
