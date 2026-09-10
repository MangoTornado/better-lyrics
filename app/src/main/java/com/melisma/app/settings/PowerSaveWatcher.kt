package com.melisma.app.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone is in battery saver, as a flow.
 *
 * Watched rather than read where it is needed, because three separate places act on it — the
 * background, the screen-awake flag and the prefetch — and because it has to take effect while the
 * lyrics are already up, which is exactly when someone turns battery saver on.
 *
 * A registered receiver rather than a poll: the broadcast arrives a handful of times a day, and a
 * receiver registered in code costs nothing when nothing is happening and cannot bring a dead
 * process back to life.
 */
class PowerSaveWatcher(context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)

    private val _saving = MutableStateFlow(power?.isPowerSaveMode == true)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            _saving.value = power?.isPowerSaveMode == true
        }
    }

    init {
        if (power != null) {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                // A protected system broadcast: nothing else can send it, and this receiver has no
                // business being reachable from outside the app.
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Re-read, for the moments the app comes back and may have missed a change. */
    fun refresh() {
        _saving.value = power?.isPowerSaveMode == true
    }
}
