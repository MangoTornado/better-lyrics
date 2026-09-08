package com.betterlyrics.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betterlyrics.app.R

/**
 * What to read before using the app.
 *
 * There is exactly one thing it cannot work without (notification access) and several
 * things it works better with (tokens). Those are easy to confuse, and a lyrics app that
 * silently finds nothing is indistinguishable from a broken one — so this says plainly
 * which is which, once, on the first run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeSheet(
    accent: Color,
    permissionGranted: Boolean,
    onGrantPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF14141A),
        contentColor = Color.White,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The sheet is dark whatever the system theme is, so the artwork drawn for a
                // dark surface is named explicitly rather than left to a night qualifier the
                // app would ignore. Its black field blends into the sheet, leaving the mark.
                Image(
                    painter = painterResource(R.drawable.ic_brand_on_dark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(13.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "Welcome to Better Lyrics",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Word-by-word lyrics for whatever your phone is playing — Spotify, YouTube " +
                    "Music, a local player, anything.",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )

            Step(
                number = "1",
                title = "One permission, and it is required",
                body = "Android publishes what is playing — track, artist and the live " +
                    "playhead — as a “media session”, and that is what the lyrics sync to. " +
                    "Reading it sits behind the notification-access switch. Without it the " +
                    "app cannot see anything at all.\n\nNothing leaves your phone except a " +
                    "track title and artist, and only to look lyrics up.",
                accent = accent,
                done = permissionGranted,
            )

            Step(
                number = "2",
                title = "No tokens needed for most music",
                body = "Four sources work immediately, with no account and no setup:\n\n" +
                    "•  AMLL TTML Database — community-timed word-by-word lyrics, made by " +
                    "hand and released to the public domain\n" +
                    "•  LRCLIB — open community database\n" +
                    "•  Musixmatch — word-by-word for most Western music\n" +
                    "•  NetEase — word-by-word for East Asian music, with hand-checked " +
                    "romanization and translation\n\nTranslations that come with the lyrics " +
                    "are shown as they are. Translating into a language of your own choosing " +
                    "is a separate setting, because it runs a model on the phone and has to " +
                    "download it first.\n\nYou can also import your own .lrc or .ttml file " +
                    "for any track, which always wins.",
                accent = accent,
            )

            Step(
                number = "3",
                title = "Tokens are optional, and each adds something",
                body = "All of these live in Settings → Tokens and endpoints, and stay on " +
                    "this device:\n\n" +
                    "•  Spotify sp_dc cookie — no longer works, and not because of anything " +
                    "you did: Spotify closed the endpoint that turned the cookie into a " +
                    "token, to everything but their own player. Nothing to paste.\n\n" +
                    "•  Apple Music developer + user token — the best data there is: " +
                    "syllable timings with official romanizations and translations. Needs a " +
                    "subscription.\n\n" +
                    "•  Musixmatch cookie — optional. It works without one, using a token it " +
                    "mints for itself, but that is rate-limited per network. Paste the " +
                    "musixmatchUserToken cookie from a signed-in musixmatch.com session and " +
                    "that limit does not apply.\n\n" +
                    "Skip all of them and the app still works. A source with no token stays " +
                    "quiet rather than failing.",
                accent = accent,
            )

            Spacer(Modifier.height(8.dp))

            if (!permissionGranted) {
                PrimaryButton("Open notification access", accent, onGrantPermission)
                Spacer(Modifier.height(10.dp))
            }
            SecondaryButton("Add tokens in settings", accent, onOpenSettings)
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                if (permissionGranted) "Start listening" else "Later",
                accent,
                onDismiss,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "You can read this again from Settings → About.",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Step(
    number: String,
    title: String,
    body: String,
    accent: Color,
    done: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (done) accent.copy(alpha = 0.55f) else accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (done) "✓" else number,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(accent.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.Black.copy(alpha = 0.86f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SecondaryButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
