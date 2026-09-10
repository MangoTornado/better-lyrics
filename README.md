<div align="center">

<img src="docs/branding/icon-light.jpg" width="104" alt="" />

# Better Lyrics

**Word-by-word karaoke lyrics for whatever your phone is already playing.**

Spotify, YouTube Music, Apple Music, SoundCloud, a local player — anything that plays audio.
No login, no account, and it works out of the box.

[![Release](https://img.shields.io/github/v/release/MangoTornado/better-lyrics?display_name=tag&color=6c5ce7)](https://github.com/MangoTornado/better-lyrics/releases/latest)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#install)
[![Licence AGPL-3.0](https://img.shields.io/badge/licence-AGPL--3.0-blue)](LICENSE)
[![CI](https://github.com/MangoTornado/better-lyrics/actions/workflows/ci.yml/badge.svg)](https://github.com/MangoTornado/better-lyrics/actions/workflows/ci.yml)

</div>

![Cinema view in landscape](docs/cinema-landscape.png)

| Active line, mid-word | Furigana over kanji | Instrumental gap |
|---|---|---|
| ![](docs/renderer-active-line.png) | ![](docs/furigana.png) | ![](docs/renderer-interlude.png) |

| Cinema view | Popup lyrics | Copy lines |
|---|---|---|
| ![](docs/cinema-portrait.png) | ![](docs/popup-lyrics.png) | ![](docs/copy-lines.png) |

> [!NOTE]
> This is a port of [**Spicy Lyrics**](https://github.com/Spikerko/spicy-lyrics) (a
> [Spicetify](https://github.com/spicetify/cli) extension) to Android — its look, its motion and its
> lyric model. Spicy Lyrics is AGPL-3.0, so this is too. [NOTICE.md](NOTICE.md) is the honest
> accounting of what came from where.

## Contents

- [What it does](#what-it-does)
- [Install](#install)
- [Using it](#using-it)
- [Where the lyrics come from](#where-the-lyrics-come-from)
- [Optional tokens](#optional-tokens)
- [Permissions](#permissions)
- [FAQ](#faq)
- [Building it](#building-it)
- [Docs](#docs)
- [Credits](#credits)

## What it does

🎤 **Word-by-word karaoke**, not a scrolling teleprompter — each syllable fills as it is sung, lifts,
swells and glows, and a note held over a second breaks into individually animated letters.

🎧 **Follows any player.** It reads the media session Android makes every audio app publish, so
there is nothing to connect and no service to log into.

🈶 **Romanization and furigana** for Japanese (through a real dictionary, so kanji get the right
reading), Chinese, Korean, Cyrillic and Greek — per syllable, so the karaoke fill still works.

🌍 **Translation on the device** via ML Kit. The lyrics never leave your phone.

🪟 **Popup lyrics** in a floating window over other apps, plus **Cinema** view with the album art
beside the words, and a **compact**, **minimal** and **simple** mode.

🎨 **Living background** drawn from the album art, drifting on long non-repeating periods and paced
by the song's tempo.

📋 **Copy a line** by holding it, or pick out several and copy them together.

🔋 **Stops when the music does.** Nothing animates, nothing polls, and after ten idle minutes the app
lets go of everything keeping it alive.

<details>
<summary>The rendering, in detail</summary>

A port of Spicy Lyrics' visual language, down to the curve constants:

- Each syllable is filled by a soft gradient sweeping down through it as it is sung, while it lifts,
  swells past its resting size, and glows.
- A syllable held for over a second breaks into **individually animated letters**, so a held note
  ripples instead of sitting bright.
- Every other line is drawn as a **blur of itself**, with the radius growing the further it is from
  the current line. That is what gives the page its depth.
- Instrumental gaps of three seconds or more become **three dots** that breathe in turn.
- Duet lines pin to the opposite edge; backing vocals render smaller under their lead.
- Sung lines fade out rather than snapping back, so a breath between lines reads as one.
- Scrolling is spring-driven and hands control back to you the moment you drag.
- Songwriters and the lyrics source close the song, inside the scroll rather than in the chrome.
- Backgrounds: **Living** (the drifting colour field), **Auto**, **Cover art** with a blur slider,
  **Artist**, **Colour**, **Black**.

</details>

## Install

1. **Download the APK** from the [latest release](https://github.com/MangoTornado/better-lyrics/releases/latest)
   and install it. Android will warn you about installing outside the Play Store; that is expected for
   any APK.
2. **Open it and tap "Open notification access"**, then enable Better Lyrics in the list that appears
   and come back. It picks the permission up on its own.
3. **Play something.** Lyrics appear.

That is the whole setup. Android 10 or newer.

## Using it

| | |
|---|---|
| **Tap a line** | Jumps the song to it |
| **Hold a line** | Copies it |
| **Drag** | Scrolls freely; the lyrics take back over a moment after you let go |
| **Copy button** | Pick out several lines, then copy those or all of them |
| **Views** | *Lyrics* fills the screen; *Cinema* puts the album art, scrubber and transport beside the words; *Popup* floats over other apps and shrinks into place when you leave, like YouTube |

**The one setting most people end up wanting is *Sync offset*.** Players and audio routes add their
own latency. If the highlight reaches a word *before* you hear it, go negative — and **Bluetooth
earbuds always need a negative value**, because they run 150–250 ms behind what the player reports.

Every setting has a one-line description, and the **?** in the settings header expands a longer
explanation under each one. There is also a welcome guide in *Settings → About*.

## Where the lyrics come from

Every enabled source is asked **at once** and the best answer wins — word-by-word beats line-by-line
beats unsynced. Order only breaks ties: hold a row's handle in *Settings → Where lyrics come from*
and drag it.

| Source | Timing | Needs |
|---|---|---|
| **Your own files** | up to word | `.lrc` / `.ttml` you import. Always wins. |
| **AMLL TTML Database** | **word** | **Nothing.** Community-timed, hand-made, public domain. |
| **NetEase Cloud Music** | **word** | **Nothing.** Best for Japanese, Korean and Chinese — ships hand-checked romanization and translation. |
| **Musixmatch** | **word** | **Nothing.** Most Western music. |
| **LRCLIB** | line | **Nothing.** Open community database. |
| **Spotify** | line | An access token — see [tokens](#optional-tokens). |
| **Apple Music** | **word** | Two tokens. The best data there is: official romanizations and translations. |
| **Your own cache server** | any | Optional, for putting one server in front of the free ones — [CACHE-SERVER.md](docs/CACHE-SERVER.md). |

**Four sources need no account at all** — the four in the middle — and they are the ones switched on
when you install it. The rest are there for the tracks the free ones miss.

## Optional tokens

Skip this unless something is missing. Pasting your own credentials adds:

| | |
|---|---|
| **Spotify** | Its own lyrics, the cover at full size, the artist's image, and the song's tempo |
| **Apple Music** | Word-by-word lyrics with official romanizations and translations |
| **Musixmatch / NetEase** | Higher rate limits and a wider catalogue |

They are read out of a browser session you are already signed in to — there is no Better Lyrics
account and no server in between. They stay in the app's private storage and each is sent only to the
service it belongs to.

**→ [docs/TOKENS.md](docs/TOKENS.md) has step-by-step instructions for every one of them**, including
how to stop having to replace the Spotify token every hour.

A source with nothing to authenticate with is skipped rather than queried, so leaving one enabled
while you go and find its token costs nothing.

## Permissions

| Permission | Why | What leaves your phone |
|---|---|---|
| **Notification access** | The only way Android lets an app read another app's media session. The service that holds it ignores notifications entirely. | Nothing |
| **Internet**, network state | Looking lyrics up, and noticing when there is no connection to look with. | The track title, artist, album and duration, to the lyrics sources you have enabled |
| **Install packages** | Only when you accept an in-app update, which goes through Android's own confirmation screen. | Nothing |

There is no analytics, no crash reporting and no account. Lyrics are cached on the device for 30
days and nowhere else.

## FAQ

**Do I need Spotify Premium, or a Spotify account?**

No. The app reads what your phone is playing, whatever is playing it. It never talks to Spotify unless
you paste a token yourself.

**Does it work with YouTube Music / SoundCloud / a local player?**

Yes — anything that publishes a media session, which is everything that plays audio on Android. You
can also switch a player off under *Settings → Players to follow*, which is how you stop a podcast or
a video being looked up as a song.

**Why does it want notification access? That sounds like a lot.**

Because it is the only way Android will let an app read another app's playback position. The service
that holds the permission has an empty `onNotificationPosted` — it never reads a notification. It is
also released automatically after ten idle minutes.

**Is it on the Play Store / F-Droid?**

No. Install the APK from [releases](https://github.com/MangoTornado/better-lyrics/releases/latest);
the app checks GitHub for updates itself and offers to install them.

**Why is the APK 34 MB?**

The Japanese dictionary and the offline translation engine are nearly all of it. Both are the price of
getting kanji readings right and of translating without sending your lyrics anywhere.

**Lyrics are slightly out of time.**

*Settings → Timing → Sync offset.* Negative for Bluetooth. See [above](#using-it).

**A track has no lyrics, or the wrong ones.**

*Settings → This track → Look this track up again* forces a fresh lookup. If nothing has it, you can
import a `.lrc` or `.ttml` file yourself, and that always wins.

## Building it

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (unsigned without a keystore)
./gradlew :app:testDebugUnitTest  # unit tests
```

Needs the Android SDK (platform 36) and a JDK 17+. A fresh clone never needs anybody's signing key.
More in **[docs/DEVELOPING.md](docs/DEVELOPING.md)**.

## Docs

| | |
|---|---|
| **[TOKENS.md](docs/TOKENS.md)** | Every optional token, step by step |
| **[DEVELOPER-OPTIONS.md](docs/DEVELOPER-OPTIONS.md)** | The developer menu, and what each switch does |
| **[CACHE-SERVER.md](docs/CACHE-SERVER.md)** | The request/response contract for a lyrics server of your own |
| **[DEVELOPING.md](docs/DEVELOPING.md)** | Building, architecture, testing without a music app |
| **[RELEASING.md](docs/RELEASING.md)** | Tagging a release, and checking an APK is really yours |
| **[NOTICE.md](NOTICE.md)** | What came from where |

## Credits

This is [**Spicy Lyrics**](https://github.com/Spikerko/spicy-lyrics) by **Spikerko**, read onto
Android — its look, its animation curves, its lyric model and its TTML dialect. The spring is a port
of [Fraktality's `spr`](https://github.com/Fraktality/spr) (MIT) and the curves of `cubic-spline`
(MIT). The word-by-word lyrics that arrive with no account come from the
[**AMLL TTML Database**](https://github.com/amll-dev/amll-ttml-db), hand-timed by its contributors
and dedicated to the public domain. [**Beautiful Lyrics**](https://github.com/surfbryce/beautiful-lyrics)
by surfbryce is prior art and a reference point; no code from it is used.

Lyrics belong to their writers and publishers. None of the sources here are affiliated with this app.

Licensed **AGPL-3.0** — see [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and *Settings → Credits and
licences* in the app itself.
