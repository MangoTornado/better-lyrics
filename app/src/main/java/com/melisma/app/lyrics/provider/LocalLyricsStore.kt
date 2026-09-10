package com.melisma.app.lyrics.provider

import android.content.Context
import android.net.Uri
import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.lyrics.parse.LrcParser
import com.melisma.app.lyrics.parse.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyrics the user brought themselves.
 *
 * Always consulted first: if you took the trouble to hand-time a TTML file for a
 * track, no online database should be allowed to overrule it. Files are keyed by the
 * same cache key the rest of the app uses, so an import sticks to that track.
 */
class LocalLyricsStore(private val context: Context) : LyricsProvider {

    override val id = "local"
    override val displayName = "Local file"
    override val canBeWordSynced = true

    private val directory: File
        get() = File(context.filesDir, "local-lyrics").apply { mkdirs() }

    override suspend fun fetch(request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val key = keyFor(request)
            for (extension in EXTENSIONS) {
                val file = File(directory, "$key.$extension")
                if (!file.exists()) continue
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                parse(text, extension)?.let { return@withContext it }
            }
            null
        }

    /**
     * Copy [uri] in as the lyrics for [request]. Returns the parsed document so the
     * caller can show it immediately, or null when the file was not lyrics we
     * understand — in which case nothing is written.
     */
    suspend fun import(uri: Uri, request: LyricsRequest): LyricsDocument? =
        withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return@withContext null

            val extension = if (text.trimStart().startsWith("<")) "ttml" else "lrc"
            val document = parse(text, extension) ?: return@withContext null

            val key = keyFor(request)
            // Remove the other spelling so a re-import doesn't leave a stale file
            // shadowing the new one.
            EXTENSIONS.forEach { File(directory, "$key.$it").delete() }
            runCatching { File(directory, "$key.$extension").writeText(text) }

            document
        }

    suspend fun remove(request: LyricsRequest): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(request)
        EXTENSIONS.map { File(directory, "$key.$it").delete() }.any { it }
    }

    suspend fun has(request: LyricsRequest): Boolean = withContext(Dispatchers.IO) {
        val key = keyFor(request)
        EXTENSIONS.any { File(directory, "$key.$it").exists() }
    }

    private fun parse(text: String, extension: String): LyricsDocument? = when (extension) {
        "ttml", "xml" -> TtmlParser.parse(text, displayName, id)
        else -> LrcParser.toDocument(text, displayName, id)
            ?: LrcParser.plainToDocument(text, displayName, id)
    }

    private fun keyFor(request: LyricsRequest): String {
        val raw = request.spotifyTrackId?.let { "sp-$it" }
            ?: "${request.title}-${request.primaryArtist}-${request.durationMs / 2000}"
        return raw.lowercase().replace(Regex("[^a-z0-9\\u00c0-\\uffff-]+"), "_").take(120)
    }

    private companion object {
        val EXTENSIONS = listOf("ttml", "lrc")
    }
}
