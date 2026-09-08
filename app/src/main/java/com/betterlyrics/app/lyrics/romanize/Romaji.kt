package com.betterlyrics.app.lyrics.romanize

/**
 * Katakana → Hepburn romaji.
 *
 * Long vowels are written as a repeated vowel (`raamen`) rather than with a macron
 * (`rāmen`): you are meant to sing along to this, and a doubled vowel makes the extra
 * beat obvious where a macron hides it.
 */
object Romaji {

    private val DIGRAPHS: Map<String, String> = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo", "キェ" to "kye",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo", "ギェ" to "gye",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho", "シェ" to "she",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo", "ジェ" to "je",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "チェ" to "che",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo", "ニェ" to "nye",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo", "ヒェ" to "hye",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo", "ビェ" to "bye",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo", "ピェ" to "pye",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo", "ミェ" to "mye",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo", "リェ" to "rye",
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo", "フュ" to "fyu",
        "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo", "ヴュ" to "vyu",
        "ティ" to "ti", "トゥ" to "tu", "テュ" to "tyu",
        "ディ" to "di", "ドゥ" to "du", "デュ" to "dyu",
        "ツァ" to "tsa", "ツィ" to "tsi", "ツェ" to "tse", "ツォ" to "tso",
        "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
        "クヮ" to "kwa", "クァ" to "kwa", "クィ" to "kwi", "クェ" to "kwe", "クォ" to "kwo",
        "グヮ" to "gwa", "グァ" to "gwa",
        "スィ" to "si", "ズィ" to "zi",
        "シィ" to "shi", "チィ" to "chi",
        "イェ" to "ye",
    )

    private val MONOGRAPHS: Map<Char, String> = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヰ' to "i", 'ヱ' to "e", 'ヲ' to "o",
        'ン' to "n", 'ヴ' to "vu",
        // Small vowels standing alone (rare, but they appear in stylised titles).
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo", 'ヮ' to "wa",
    )

    private const val SOKUON = 'ッ'
    private const val CHOONPU = 'ー'

    /** Hiragana to katakana, so one table covers both. */
    private fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when {
                    c in 'ぁ'..'ゖ' -> c + 0x60 // hiragana block -> katakana
                    c == 'ゝ' -> 'ヽ'
                    c == 'ゞ' -> 'ヾ'
                    else -> c
                },
            )
        }
    }

    fun fromKana(kana: String): String {
        val text = normalize(kana)
        val out = StringBuilder(text.length * 2)
        var index = 0

        while (index < text.length) {
            val c = text[index]

            if (c == SOKUON) {
                // Gemination: repeat the first consonant of whatever comes next.
                val next = peekSyllable(text, index + 1)
                if (next != null && next.second.isNotEmpty()) {
                    val first = next.second.first()
                    // Hepburn writes っち as "tchi", not "cchi".
                    out.append(if (next.second.startsWith("ch")) 't' else first)
                } else {
                    out.append('t')
                }
                index++
                continue
            }

            if (c == CHOONPU) {
                val lastVowel = out.lastOrNull { it in "aiueo" }
                if (lastVowel != null) out.append(lastVowel)
                index++
                continue
            }

            val syllable = peekSyllable(text, index)
            if (syllable != null) {
                out.append(syllable.second)
                index += syllable.first
            } else {
                out.append(c)
                index++
            }
        }
        return out.toString()
    }

    /** Returns (charsConsumed, romaji) for the syllable starting at [index]. */
    private fun peekSyllable(text: String, index: Int): Pair<Int, String>? {
        if (index >= text.length) return null
        if (index + 1 < text.length) {
            DIGRAPHS[text.substring(index, index + 2)]?.let { return 2 to it }
        }
        MONOGRAPHS[text[index]]?.let { return 1 to it }
        return null
    }

    /** True when [text] is entirely kana / punctuation and needs no dictionary lookup. */
    fun isPureKana(text: String): Boolean = text.all { c ->
        c in '぀'..'ヿ' || c == CHOONPU || !c.isLetter()
    }
}
