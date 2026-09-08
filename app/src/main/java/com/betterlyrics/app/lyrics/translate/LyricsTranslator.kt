package com.betterlyrics.app.lyrics.translate

import com.betterlyrics.app.lyrics.model.LyricsDocument
import com.betterlyrics.app.util.detectScript
import com.betterlyrics.app.util.languageTag
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
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
        onState: (State) -> Unit = {},
    ): LyricsDocument {
        val outstanding = document.lines.filter {
            !it.isInterlude && it.text.isNotBlank() &&
                (replaceProvided || it.translated.isNullOrBlank())
        }
        if (outstanding.isEmpty()) return document

        val sourceTag = document.language
            ?: detectScript(document.lines.joinToString("\n") { it.text }).languageTag()
            ?: return document

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
