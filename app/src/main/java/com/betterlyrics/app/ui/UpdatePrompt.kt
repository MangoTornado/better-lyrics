package com.betterlyrics.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.betterlyrics.app.update.Updater

/**
 * "There is a new version" — said once, and answerable.
 *
 * Distributed as an APK, so an update the user is never told about is an update they never
 * get. This is deliberately small: what version, what changed, and three honest answers —
 * install it, not now, or stop asking about this one.
 */
@Composable
fun UpdatePrompt(
    state: Updater.State,
    currentVersion: String,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = when (state) {
        is Updater.State.Available -> state.release
        is Updater.State.Downloading -> state.release
        is Updater.State.ReadyToInstall -> state.release
        else -> return
    }
    val downloading = state as? Updater.State.Downloading
    // Only a download in flight counts as busy. "Downloaded, not installed" used to as well, which
    // is how this dialog became a trap: Android tells an app nothing when its install screen is
    // cancelled, so the app sat in that state with no buttons and a back gesture that did nothing,
    // for as long as the process lived. Nobody noticed because a *successful* install replaces the
    // process before the state is ever seen again.
    val busy = downloading != null

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF16161C))
                .padding(20.dp),
        ) {
            Text(
                "Version ${release.versionName} is out",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "You have $currentVersion" +
                    if (release.apkSizeBytes > 0) {
                        " · %.1f MB to download".format(release.apkSizeBytes / 1_048_576f)
                    } else {
                        ""
                    },
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )

            if (release.notes.isNotBlank()) {
                Text(
                    release.notes,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    // Release notes are as long as they are; scroll rather than truncate,
                    // but never take over the screen.
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(18.dp))

            when {
                downloading != null -> {
                    Text(
                        if (downloading.fraction < 0f) {
                            "Downloading…"
                        } else {
                            "Downloading — ${(downloading.fraction * 100).toInt()}%"
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (downloading.fraction < 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { downloading.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                else -> {
                    if (state is Updater.State.ReadyToInstall) {
                        // Covers both moments this state is on screen: just before Android's screen
                        // appears, and after it has been dismissed. The app cannot tell those apart
                        // — nothing is reported back either way — so it says something true of both
                        // rather than guessing.
                        Text(
                            "Downloaded. Android's own installer confirms it — if it did not " +
                                "appear, or you closed it, press Install again. It will not " +
                                "download a second time.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }
                    DialogButton("Install", filled = true, onClick = onInstall)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            DialogButton("Not now", filled = false, onClick = onDismiss)
                        }
                        Box(Modifier.weight(1f)) {
                            DialogButton("Skip this one", filled = false, onClick = onSkip)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.09f))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) Color.Black.copy(alpha = 0.87f) else Color.White,
            fontSize = 14.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
