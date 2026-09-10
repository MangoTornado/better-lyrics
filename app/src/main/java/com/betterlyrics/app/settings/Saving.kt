package com.betterlyrics.app.settings

/**
 * What the app is giving up right now to save power.
 *
 * Three separate answers rather than one "saving" flag, because each measure costs something
 * different and the trade is not the same for everybody: a still background is barely noticeable,
 * a screen that dims mid-song is the app failing at its job. The system's battery saver is the
 * trigger; these say what it is allowed to do.
 *
 * Computed rather than stored, so there is one place that decides and it can be tested without a
 * phone in a low-battery state.
 */
data class Saving(
    /** Hold the animated background still. */
    val stillBackground: Boolean = false,
    /** Let the screen time out even while the music plays. */
    val releaseScreen: Boolean = false,
    /** Do not look the next queued track up before it starts. */
    val skipPrefetch: Boolean = false,
) {
    /** True when at least one measure is in force — for saying so on screen. */
    val any: Boolean get() = stillBackground || releaseScreen || skipPrefetch

    companion object {
        val NONE = Saving()

        /**
         * @param systemSaverOn whether the phone itself is in battery saver.
         */
        fun of(settings: Settings, systemSaverOn: Boolean): Saving {
            if (!systemSaverOn || !settings.followBatterySaver) return NONE
            return Saving(
                stillBackground = settings.saverStillBackground,
                releaseScreen = settings.saverReleaseScreen,
                skipPrefetch = settings.saverSkipPrefetch,
            )
        }
    }
}
