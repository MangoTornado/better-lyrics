package com.betterlyrics.app.lyrics.parse

import android.util.Xml
import com.betterlyrics.app.lyrics.model.LineRole
import com.betterlyrics.app.lyrics.model.LyricLine
import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.lyrics.model.LyricsKind
import com.betterlyrics.app.lyrics.model.Syllable
import com.betterlyrics.app.lyrics.model.withInterludes
import com.betterlyrics.app.util.isRtlText
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * TTML parser for the Apple Music / Spicy Lyrics dialect — the only widely used
 * format that carries true per-syllable timings, duet agents, background vocals,
 * official romanizations and official translations in one file.
 *
 * Everything it does not recognise is skipped rather than treated as an error, so a
 * file with extra vendor metadata still loads.
 */
object TtmlParser {

    private const val ROLE_BACKGROUND = "x-bg"

    /**
     * Untimed sibling spans carrying a line's romanization and translation.
     *
     * The community TTML corpus puts them at the end of the `<p>`, like this:
     *
     * ```
     * <span begin="…" end="…">僕</span><span begin="…" end="…">を</span>
     * <span ttm:role="x-translation" xml:lang="zh-CN">…</span>
     * <span ttm:role="x-roman">bo ku wo</span>
     * ```
     *
     * They have no `begin`, so without recognising them they would be read as syllables at
     * time zero and the romaji would be spliced into the sung line.
     */
    private const val ROLE_ROMANIZATION = "x-roman"
    private const val ROLE_TRANSLATION = "x-translation"

    fun parse(
        xml: String,
        providerName: String,
        providerId: String,
    ): LyricsDocument? = runCatching { parseOrThrow(xml, providerName, providerId) }.getOrNull()

    private fun parseOrThrow(
        xml: String,
        providerName: String,
        providerId: String,
    ): LyricsDocument? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var wordTiming = false
        val agentTypes = LinkedHashMap<String, String>()
        val songWriters = ArrayList<String>()
        // itunes:key -> alternate text, for whole-line entries.
        val transliterationByKey = HashMap<String, String>()
        val translationByKey = HashMap<String, String>()
        // itunes:key of a span -> alternate text, for per-syllable entries.
        val transliterationBySpanKey = HashMap<String, String>()
        val lines = ArrayList<RawLine>()
        var language: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.localName()) {
                    "tt" -> {
                        wordTiming = parser.attr("itunes:timing")
                            ?.equals("Word", ignoreCase = true) == true
                        language = parser.attr("xml:lang") ?: parser.attr("lang")
                    }

                    "agent" -> {
                        val id = parser.attr("xml:id") ?: parser.attr("id")
                        val type = parser.attr("type") ?: "person"
                        if (id != null) agentTypes[id] = type
                    }

                    "songwriter" -> parser.nextTextSafe()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { songWriters += it }

                    "transliteration" -> readAlternateBlock(
                        parser,
                        "transliteration",
                        transliterationByKey,
                        transliterationBySpanKey,
                    )

                    "translation" -> readAlternateBlock(
                        parser,
                        "translation",
                        translationByKey,
                        HashMap(),
                    )

                    "p" -> lines += readParagraph(parser, agentTypes)
                }
            }
            event = parser.next()
        }

        if (lines.isEmpty()) return null

        val primaryAgent = agentTypes.keys.firstOrNull()
        val withAlternates: List<LyricLine> = lines.map { line ->
            val key = line.ttmlKey
            var out = line
            transliterationByKey[key]?.let { out = out.copy(romanized = it) }
            translationByKey[key]?.let { out = out.copy(translated = it) }
            if (transliterationBySpanKey.isNotEmpty() && out.syllables.isNotEmpty()) {
                val syllables = out.syllables.mapIndexed { index, syllable ->
                    val spanKey = "$key.${index + 1}"
                    transliterationBySpanKey[spanKey]?.let { syllable.copy(romanized = it) }
                        ?: syllable
                }
                out = out.copy(syllables = syllables)
            }
            out.model(primaryAgent)
        }

        val hasSyllables = withAlternates.any { it.syllables.isNotEmpty() }
        return LyricsDocument(
            kind = if (wordTiming || hasSyllables) LyricsKind.SYLLABLE else LyricsKind.LINE,
            lines = withAlternates.withInterludes(),
            providerName = providerName,
            providerId = providerId,
            songWriters = songWriters.distinct(),
            language = language,
            hasRomanization = withAlternates.any {
                it.romanized != null || it.syllables.any { s -> s.romanized != null }
            },
            hasTranslation = withAlternates.any { it.translated != null },
        )
    }

    /** A line plus the TTML bookkeeping we need before it becomes a [LyricLine]. */
    private class RawLine(
        val role: LineRole,
        val startMs: Int,
        val endMs: Int,
        val text: String,
        val syllables: List<Syllable>,
        val agent: String?,
        val ttmlKey: String,
        val romanized: String? = null,
        val translated: String? = null,
    ) {
        fun copy(
            romanized: String? = this.romanized,
            translated: String? = this.translated,
            syllables: List<Syllable> = this.syllables,
        ) = RawLine(role, startMs, endMs, text, syllables, agent, ttmlKey, romanized, translated)

        fun model(primaryAgent: String?): LyricLine = LyricLine(
            role = role,
            startMs = startMs,
            endMs = endMs,
            text = text,
            syllables = syllables,
            oppositeAligned = agent != null && primaryAgent != null && agent != primaryAgent,
            rtl = text.isRtlText(),
            romanized = romanized,
            translated = translated,
        )
    }

    private fun readParagraph(
        parser: XmlPullParser,
        agentTypes: Map<String, String>,
    ): List<RawLine> {
        val begin = parseTime(parser.attr("begin"))
        val end = parseTime(parser.attr("end"))
        val key = parser.attr("itunes:key") ?: parser.attr("key") ?: ""
        val agent = parser.attr("ttm:agent") ?: parser.attr("agent")

        val lead = ArrayList<Syllable>()
        val background = ArrayList<Syllable>()
        val leadText = StringBuilder()
        val backgroundText = StringBuilder()
        var pendingSpaceLead = true
        var pendingSpaceBackground = true
        var romanization: String? = null
        var translation: String? = null
        var depth = 1

        // The plain-text body, used when the paragraph has no timed spans at all.
        val flatText = StringBuilder()

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.localName() == "span") {
                        val role = parser.attr("ttm:role") ?: parser.attr("role")
                        if (role == ROLE_BACKGROUND) {
                            val nested = readSpanGroup(parser)
                            depth--
                            for (span in nested) {
                                val partOfWord = !pendingSpaceBackground && !span.leadingSpace
                                background += Syllable(
                                    text = span.text,
                                    startMs = span.startMs,
                                    endMs = span.endMs,
                                    partOfWord = partOfWord,
                                )
                                if (!partOfWord && backgroundText.isNotEmpty()) {
                                    backgroundText.append(' ')
                                }
                                backgroundText.append(span.text)
                                pendingSpaceBackground = span.trailingSpace
                            }
                        } else if (role == ROLE_ROMANIZATION || role == ROLE_TRANSLATION) {
                            val text = readSpanGroup(parser)
                                .joinToString(" ") { it.text }
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            depth--
                            if (text.isNotEmpty()) {
                                if (role == ROLE_ROMANIZATION) {
                                    romanization = romanization ?: text
                                } else {
                                    translation = translation ?: text
                                }
                            }
                        } else {
                            val spans = readSpanGroup(parser)
                            depth--
                            for (span in spans) {
                                val partOfWord = !pendingSpaceLead && !span.leadingSpace
                                lead += Syllable(
                                    text = span.text,
                                    startMs = span.startMs,
                                    endMs = span.endMs,
                                    partOfWord = partOfWord,
                                )
                                if (!partOfWord && leadText.isNotEmpty()) leadText.append(' ')
                                leadText.append(span.text)
                                pendingSpaceLead = span.trailingSpace
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    flatText.append(text)
                    if (text.isNotEmpty() && text.isBlank()) pendingSpaceLead = true
                }

                XmlPullParser.END_TAG -> depth--

                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        val out = ArrayList<RawLine>(2)
        val resolvedLeadText = if (lead.isNotEmpty()) {
            leadText.toString()
        } else {
            flatText.toString().replace(Regex("\\s+"), " ").trim()
        }
        if (resolvedLeadText.isNotEmpty() || lead.isNotEmpty()) {
            out += RawLine(
                role = LineRole.LEAD,
                startMs = begin ?: lead.firstOrNull()?.startMs ?: 0,
                endMs = end ?: lead.lastOrNull()?.endMs ?: 0,
                text = resolvedLeadText,
                syllables = lead,
                agent = agent,
                ttmlKey = key,
                romanized = romanization,
                translated = translation,
            )
        }
        if (background.isNotEmpty()) {
            out += RawLine(
                role = LineRole.BACKGROUND,
                startMs = background.first().startMs,
                endMs = background.last().endMs,
                text = backgroundText.toString(),
                syllables = background,
                agent = agent,
                ttmlKey = "$key.bg",
            )
        }
        return out
    }

    private class TimedSpan(
        val text: String,
        val startMs: Int,
        val endMs: Int,
        val leadingSpace: Boolean,
        val trailingSpace: Boolean,
    )

    /**
     * Read one `<span>` (and any spans nested inside it) into flat timed pieces.
     *
     * Apple nests spans two deep for background vocals and for emphasised words, and
     * leans on the literal whitespace between them to mark word boundaries — so the
     * raw text either side of each span is what tells us whether two syllables belong
     * to the same word.
     */
    private fun readSpanGroup(parser: XmlPullParser): List<TimedSpan> {
        val begin = parseTime(parser.attr("begin"))
        val end = parseTime(parser.attr("end"))
        val out = ArrayList<TimedSpan>(4)
        val own = StringBuilder()
        var depth = 1
        var sawNested = false
        var leadingSpace = true

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.localName() == "span") {
                        sawNested = true
                        val nested = readSpanGroup(parser)
                        for ((index, span) in nested.withIndex()) {
                            out += if (index == 0) {
                                TimedSpan(
                                    span.text,
                                    span.startMs,
                                    span.endMs,
                                    leadingSpace || span.leadingSpace,
                                    span.trailingSpace,
                                )
                            } else {
                                span
                            }
                        }
                        leadingSpace = nested.lastOrNull()?.trailingSpace ?: leadingSpace
                    } else {
                        depth++
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (sawNested) {
                        if (text.isNotBlank()) {
                            // Text sitting between nested spans: keep it as an untimed
                            // piece pinned to the parent's window.
                            out += TimedSpan(
                                text.trim(),
                                out.lastOrNull()?.endMs ?: begin ?: 0,
                                out.lastOrNull()?.endMs ?: end ?: 0,
                                leadingSpace || text.first().isWhitespace(),
                                text.last().isWhitespace(),
                            )
                            leadingSpace = text.last().isWhitespace()
                        } else if (text.isNotEmpty()) {
                            leadingSpace = true
                        }
                    } else {
                        own.append(text)
                    }
                }

                XmlPullParser.END_TAG -> depth--

                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }

        if (!sawNested) {
            val raw = own.toString()
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) {
                out += TimedSpan(
                    text = trimmed,
                    startMs = begin ?: 0,
                    endMs = end ?: begin ?: 0,
                    leadingSpace = raw.firstOrNull()?.isWhitespace() ?: false,
                    trailingSpace = raw.lastOrNull()?.isWhitespace() ?: false,
                )
            }
        }
        return out
    }

    /**
     * Read an `<transliteration>` / `<translation>` block: `<text for="L1">` entries,
     * optionally with `<span for="L1.1">` children for per-syllable alternates.
     */
    private fun readAlternateBlock(
        parser: XmlPullParser,
        endTag: String,
        byLineKey: MutableMap<String, String>,
        bySpanKey: MutableMap<String, String>,
    ) {
        var depth = 1
        var currentKey: String? = null
        val buffer = StringBuilder()
        var spanKey: String? = null
        val spanBuffer = StringBuilder()

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.localName()) {
                        "text" -> {
                            currentKey = parser.attr("for") ?: parser.attr("itunes:key")
                            buffer.setLength(0)
                        }

                        "span" -> {
                            spanKey = parser.attr("for") ?: parser.attr("itunes:key")
                            spanBuffer.setLength(0)
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    buffer.append(text)
                    if (spanKey != null) spanBuffer.append(text)
                }

                XmlPullParser.END_TAG -> {
                    when (parser.localName()) {
                        "span" -> {
                            val key = spanKey
                            val value = spanBuffer.toString().trim()
                            if (key != null && value.isNotEmpty()) bySpanKey[key] = value
                            spanKey = null
                        }

                        "text" -> {
                            val key = currentKey
                            val value = buffer.toString().replace(Regex("\\s+"), " ").trim()
                            if (key != null && value.isNotEmpty()) byLineKey[key] = value
                            currentKey = null
                        }

                        endTag -> depth = 0
                    }
                    if (depth > 0) depth--
                }

                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
    }

    // ---- small helpers ------------------------------------------------------

    private fun XmlPullParser.localName(): String {
        val raw = name ?: return ""
        val colon = raw.indexOf(':')
        return if (colon >= 0) raw.substring(colon + 1) else raw
    }

    /** Attribute lookup that tolerates the prefix being absent or different. */
    private fun XmlPullParser.attr(qualified: String): String? {
        getAttributeValue(null, qualified)?.let { return it }
        val bare = qualified.substringAfter(':')
        for (i in 0 until attributeCount) {
            val attrName = getAttributeName(i) ?: continue
            if (attrName == qualified) return getAttributeValue(i)
            if (attrName.substringAfter(':') == bare) return getAttributeValue(i)
        }
        return null
    }

    private fun XmlPullParser.nextTextSafe(): String? =
        runCatching { nextText() }.getOrNull()

    /** `hh:mm:ss.mmm`, `mm:ss.mmm`, `12.5s`, `500ms` — all of them turn up. */
    fun parseTime(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (value.endsWith("ms")) {
            return value.dropLast(2).toFloatOrNull()?.toInt()
        }
        if (value.endsWith("s")) {
            return value.dropLast(1).toFloatOrNull()?.let { (it * 1000f).toInt() }
        }

        val parts = value.split(':')
        return when (parts.size) {
            1 -> parts[0].toFloatOrNull()?.let { (it * 1000f).toInt() }
            2 -> {
                val minutes = parts[0].toIntOrNull() ?: return null
                val seconds = parts[1].toFloatOrNull() ?: return null
                (minutes * 60_000 + (seconds * 1000f).toInt())
            }

            3 -> {
                val hours = parts[0].toIntOrNull() ?: return null
                val minutes = parts[1].toIntOrNull() ?: return null
                val seconds = parts[2].toFloatOrNull() ?: return null
                hours * 3_600_000 + minutes * 60_000 + (seconds * 1000f).toInt()
            }

            else -> null
        }
    }
}
