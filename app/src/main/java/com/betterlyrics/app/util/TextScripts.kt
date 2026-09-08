package com.betterlyrics.app.util

/**
 * Which writing system a chunk of lyrics is in — the input to both romanization
 * and translation. Deliberately character-range based rather than statistical: song
 * lyrics are short, and a single line of kana is all the evidence we need.
 */
enum class Script { JAPANESE, CHINESE, KOREAN, CYRILLIC, GREEK, LATIN, OTHER }

private fun Char.isHiraganaOrKatakana(): Boolean =
    this in '぀'..'ゟ' || this in '゠'..'ヿ' || this in 'ㇰ'..'ㇿ'

private fun Char.isHan(): Boolean =
    this in '一'..'鿿' || this in '㐀'..'䶿' || this in '豈'..'﫿'

private fun Char.isHangul(): Boolean =
    this in '가'..'힣' || this in 'ᄀ'..'ᇿ' ||
        this in '㄰'..'㆏' || this in 'ꥠ'..'꥿' ||
        this in 'ힰ'..'퟿'

private fun Char.isCyrillic(): Boolean =
    this in 'Ѐ'..'ӿ' || this in 'Ԁ'..'ԯ' ||
        this in 'ⷠ'..'ⷿ' || this in 'Ꙁ'..'ꚟ'

private fun Char.isGreek(): Boolean =
    this in 'Ͱ'..'Ͽ' || this in 'ἀ'..'῿'

private fun Char.isRtlChar(): Boolean =
    this in '֐'..'׿' || // Hebrew
        this in '؀'..'ۿ' || // Arabic
        this in '܀'..'ݏ' || // Syriac
        this in 'ݐ'..'ݿ' ||
        this in 'ࢠ'..'ࣿ' ||
        this in 'יִ'..'﷿' ||
        this in 'ﹰ'..'﻿'

/** True when the line should be laid out right-to-left. */
fun String.isRtlText(): Boolean {
    var rtl = 0
    var latin = 0
    for (c in this) {
        when {
            c.isRtlChar() -> rtl++
            c.isLetter() -> latin++
        }
    }
    return rtl > 0 && rtl >= latin
}

/**
 * The dominant romanizable script in [text].
 *
 * CJK is resolved to exactly one of Japanese / Chinese — kana anywhere means the
 * kanji are Japanese and must be read with Japanese readings, never pinyin. This is
 * the same rule Spicy Lyrics applies before it picks a romanizer.
 */
fun detectScript(text: String): Script {
    var kana = 0
    var han = 0
    var hangul = 0
    var cyrillic = 0
    var greek = 0
    var latin = 0

    for (c in text) {
        when {
            c.isHiraganaOrKatakana() -> kana++
            c.isHan() -> han++
            c.isHangul() -> hangul++
            c.isCyrillic() -> cyrillic++
            c.isGreek() -> greek++
            c.code < 0x250 && c.isLetter() -> latin++
        }
    }

    return when {
        kana > 0 -> Script.JAPANESE
        hangul > 0 && hangul >= han -> Script.KOREAN
        han > 0 -> Script.CHINESE
        hangul > 0 -> Script.KOREAN
        cyrillic >= 2 -> Script.CYRILLIC
        greek >= 2 -> Script.GREEK
        latin > 0 -> Script.LATIN
        else -> Script.OTHER
    }
}

/** Best-effort BCP-47 tag for a script, for handing to a translator. */
fun Script.languageTag(): String? = when (this) {
    Script.JAPANESE -> "ja"
    Script.CHINESE -> "zh"
    Script.KOREAN -> "ko"
    Script.CYRILLIC -> "ru"
    Script.GREEK -> "el"
    Script.LATIN, Script.OTHER -> null
}

fun Script.needsRomanization(): Boolean = when (this) {
    Script.JAPANESE, Script.CHINESE, Script.KOREAN, Script.CYRILLIC, Script.GREEK -> true
    Script.LATIN, Script.OTHER -> false
}

/**
 * True when [text] is written in a script that runs without spaces between words.
 *
 * Matters for romanization: the original has no word gaps to inherit, so the Latin
 * transcription of consecutive syllables would otherwise read as `kiminokoega`.
 */
fun isSpacelessScript(text: String): Boolean = text.any { c ->
    c.isHiraganaOrKatakana() || c.isHan() || c.isHangul()
}

/** True when [text] still contains characters the romanizer was supposed to convert. */
fun containsNonLatinScript(text: String): Boolean = text.any { c ->
    c.isHiraganaOrKatakana() || c.isHan() || c.isHangul() || c.isCyrillic() || c.isGreek()
}
