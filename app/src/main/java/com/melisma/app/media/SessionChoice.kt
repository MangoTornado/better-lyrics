package com.melisma.app.media

/**
 * One media session, reduced to what choosing between them needs.
 *
 * The controller comes along as [owner] so the caller can get back to it, and everything else is
 * read from the session once rather than several times over.
 */
internal class Candidate<T>(
    val packageName: String,
    /** A session with no title is not a track; players publish empty ones while they start up. */
    val hasMetadata: Boolean,
    val isPlaying: Boolean,
    /** When this package last reported playing, for the tie-break. */
    val lastPlayingAt: Long,
    val owner: T,
)

/**
 * Which session the lyrics should follow, if any.
 *
 * Preference order: something playing right now, then whatever played most recently, then anything
 * with usable metadata at all. That keeps the screen on the paused track you were listening to
 * rather than jumping to a silent video session that happens to exist.
 *
 * A pure function separated out from the repository because one of its properties is load-bearing
 * elsewhere and cannot be seen from the outside: **a player switched off under "Players to follow"
 * can never be chosen**, so it can never appear to be playing. The whole app asks "is something
 * playing?" by reading the published snapshot — the idle timer that lets the process go, the
 * screen-awake flag, the background's drift — and every one of them would be wrong about a
 * YouTube video the user explicitly said was not music. A filter that has to hold in four places
 * is worth stating once and pinning with a test.
 */
internal fun <T> chooseSession(candidates: List<Candidate<T>>, ignored: Set<String>): Candidate<T>? {
    val eligible = candidates.filter { it.hasMetadata && it.packageName !in ignored }
    if (eligible.isEmpty()) return null

    val playing = eligible.filter { it.isPlaying }
    return when {
        playing.isNotEmpty() -> playing.maxByOrNull { it.lastPlayingAt }
        else -> eligible.maxByOrNull { it.lastPlayingAt }
    } ?: eligible.first()
}
