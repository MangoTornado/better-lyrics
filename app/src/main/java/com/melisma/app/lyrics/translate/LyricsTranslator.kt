package com.melisma.app.lyrics.translate

import com.melisma.app.lyrics.model.LyricsDocument
import com.melisma.app.util.detectScript
import com.melisma.app.util.languageTag
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation via ML Kit.
 *
 * On-device rather than an API on purpose: the lyrics of whatever you are listening
 * to never leave the phone, it works on a plane, and there is no key to rotate. The
 * cost is a one-off ~30 MB model download per language pair, so nothing is fetched
 * until the user turns translation on.
 *
 * By default a translation the provider already supplied wins — a human translation of a
 * lyric beats machine translation of it, every time. The exception is when the user has
 * explicitly asked for on-device translation: the provider's may be in a language they do
 * not read, and honouring it would mean the setting appeared to do nothing.
 */
class LyricsTranslator {

    sealed interface State {
        data object Idle : State
        data object DownloadingModel : State
        data object Translating : State
        data class Failed(val reason: String) : State
        data object Done : State
    }

    /**
     * Adds [LyricsDocument.lines] translations into [targetTag].
     *
     * Returns the document unchanged when there is nothing to do: no source language,
     * source already matches the target, or the model is unavailable offline.
     *
     * @param replaceProvided translate every line, including ones that arrived with a
     *   translation already. What "On this device" means: the target language is the
     *   user's choice, not the transcriber's.
     */
    suspend fun translate(
        document: LyricsDocument,
        targetTag: String,
        requireWifi: Boolean,
        replaceProvided: Boolean = false,
        /** The language identified for this track, if it has already been worked out. */
        sourceTagOverride: String? = null,
        onState: (State) -> Unit = {},
    ): LyricsDocument {
        val outstanding = document.lines.filter {
            !it.isInterlude && it.text.isNotBlank() &&
                (replaceProvided || it.translated.isNullOrBlank())
        }
        if (outstanding.isEmpty()) return document

        val sourceTag = sourceTagOverride ?: identify(document) ?: return document

        val source = TranslateLanguage.fromLanguageTag(sourceTag.take(2))
            ?: return document
        val target = TranslateLanguage.fromLanguageTag(targetTag.take(2))
            ?: return document
        if (source == target) return document

        val translator: Translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )

        return try {
            onState(State.DownloadingModel)
            val conditions = DownloadConditions.Builder()
                .apply { if (requireWifi) requireWifi() }
                .build()
            translator.downloadModelIfNeeded(conditions).awaitCompletion()

            onState(State.Translating)
            val translated = withContext(Dispatchers.Default) {
                // ML Kit serialises internally; a small window keeps latency down
                // without thrashing.
                val gate = Semaphore(4)
                coroutineScope {
                    document.lines.map { line ->
                        async {
                            val alreadyDone = !replaceProvided && !line.translated.isNullOrBlank()
                            if (line.isInterlude || line.text.isBlank() || alreadyDone) {
                                return@async line
                            }
                            val result = gate.withPermit {
                                runCatching { translator.translate(line.text).await() }.getOrNull()
                            }
                            if (result.isNullOrBlank() || result == line.text) {
                                line
                            } else {
                                line.copy(translated = result)
                            }
                        }
                    }.map { it.await() }
                }
            }

            onState(State.Done)
            if (translated.none { !it.translated.isNullOrBlank() }) {
                document
            } else {
                document.copy(lines = translated, hasTranslation = true)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onState(State.Failed(e.message ?: "translation failed"))
            document
        } finally {
            runCatching { translator.close() }
        }
    }

    /**
     * Work out what language a lyric is in.
     *
     * The script alone is not enough and never was: Spanish, French, German and English
     * share an alphabet, so `detectScript` returns `LATIN` for all of them and has no tag to
     * offer. That made every Latin-script track untranslatable — the largest group of songs
     * there is. So when the script cannot answer, ask ML Kit's identifier, which is a small
     * on-device model with nothing to download.
     *
     * Preference order: what the provider declared, then the script (decisive and free for
     * Japanese, Chinese, Korean, Cyrillic and Greek), then identification.
     */
    suspend fun identify(document: LyricsDocument): String? {
        document.language?.takeIf { it.isNotBlank() }?.let { return it }

        val corpus = document.lines
            .filterNot { it.isInterlude }
            .joinToString("\n") { it.text }
            .trim()
        if (corpus.isBlank()) return null

        detectScript(corpus).languageTag()?.let { return it }

        val client = LanguageIdentification.getClient()
        return try {
            // A few lines is plenty, and keeps a long song from being pointlessly hashed.
            client.identifyLanguage(corpus.take(IDENTIFY_SAMPLE_CHARS)).await()
                ?.takeIf { it != UNDETERMINED }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        private const val UNDETERMINED = "und"
        private const val IDENTIFY_SAMPLE_CHARS = 800

        /**
         * Whether a track in [sourceTag] could be translated into [targetTag] here.
         *
         * The checks [translate] makes before it starts: a language ML Kit supports on both
         * sides, and not already the target. Exposed so the UI can decide whether a
         * translate button would do anything at all, rather than offering one that silently
         * does nothing for an English song being read in English.
         */
        fun canTranslate(sourceTag: String?, targetTag: String): Boolean {
            val source = TranslateLanguage.fromLanguageTag(
                sourceTag?.take(2) ?: return false,
            ) ?: return false
            val target = TranslateLanguage.fromLanguageTag(targetTag.take(2)) ?: return false
            return source != target
        }
    }
}

/** Bridges a Play-services [Task] into a coroutine without pulling in another artifact. */
private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}

/** Same, for a task whose only useful outcome is "it finished". */
private suspend fun Task<Void>.awaitCompletion(): Unit =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(Unit) }
        addOnFailureListener { error -> continuation.resumeWithException(error) }
        addOnCanceledListener { continuation.cancel() }
    }
