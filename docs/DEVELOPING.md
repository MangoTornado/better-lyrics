# Developing

## Building

Needs the Android SDK (platform 36) and a JDK 17+.

```bash
./gradlew :app:assembleDebug          # debug APK (~83 MB: two architectures, unminified)
./gradlew :app:assembleRelease        # release APK (~34 MB: arm64 only, minified)
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintRelease            # Android lint
```

With a `keystore.properties` present (see `keystore.properties.example`) the release APK comes out
**signed**; without one the same command still works and produces an unsigned APK, so a fresh clone
never needs anybody's key.

Releases are built by pushing a `v*` tag — see [RELEASING.md](RELEASING.md).

## How it works

Android makes every media app publish a **media session**: the track, the artist, the album, the
duration, the artwork, and — the part that matters — a playhead with the timestamp it was last
reported at. The app reads that session, extrapolates the playhead from the wall clock between
updates, looks the track up, and draws the words.

That is why it needs no integration with any particular player. Reading other apps' sessions is gated
behind Android's *notification access* switch, so the app declares a `NotificationListenerService`
that does nothing with notifications — it exists purely to hold that permission.

```
Spotify (or anything)  ──►  MediaSession  ──►  MediaSessionRepository
                                                       │  track + playhead
                                                       ▼
                                              LyricsRepository
                                        (cache → providers in parallel)
                                                       │  timed words
                                                       ▼
                                romanize / furigana → translate → LyricsRenderer
```

## Running it without a music service

The interesting half of this app needs a second app playing music, so two affordances exist:

**`:fakeplayer`** is a separate APK that publishes a real media session with a real advancing
playhead, so detection, extrapolation and the transport controls can be exercised on an emulator with
nothing installed.

```bash
./gradlew :fakeplayer:installDebug
adb shell am start -n com.melisma.fakeplayer/.FakePlayerActivity
```

On an emulator, `adb shell settings put secure enabled_notification_listeners …` is not enough — the
setting changes but the service is never bound. Use:

```bash
adb shell cmd notification allow_listener \
    com.melisma.app.debug/com.melisma.app.media.MediaNotificationListener
```

**Renderer preview** — in a debug build, the "nothing playing" screen offers a button that runs the
renderer against a synthetic song on a looping clock. It covers the cases that are easy to get wrong:
letter-level emphasis, Japanese with romanization and furigana, a duet line, a backing vocal, a
translated line, and interludes at both ends.

To attach a lyrics file to a track without the file picker, drop it straight into the app's store —
the filename is the cache key:

```bash
adb shell "run-as com.melisma.app.debug sh -c \
  'cat > /data/data/com.melisma.app.debug/files/local-lyrics/bohemian_rhapsody-queen-177.lrc'" \
  < my.lrc
```

## Performance

The lyrics canvas is one draw node for the whole page, so playback costs **no recomposition at all** —
the frame loop only invalidates the draw phase. On top of that:

- The canvas is its own render node, so a per-frame lyric redraw does not drag the background into
  being re-rasterised with it. Worth roughly **8×** in measured frame time.
- Non-active lines are drawn as one pass per wrapped row rather than one per syllable.
- The drifting background publishes at ~30 fps, not 60: its layers move on 30–70 second orbits, so
  the other half of the frames were redrawing three full-screen layers for a change nobody can see. A
  still background is cached as a layer and re-blitted.
- **Frames stop when nothing is moving.** Once a paused song has settled the lyrics stop drawing
  entirely — measured at 55 frames per 8 s while playing against 8 while paused.
- **A paused song stops everything else too.** The background's drift and the scrolling title are the
  only other things that animate on their own account, and both stop with the music. One animation
  left running decides the whole app's idle behaviour, however cheap it is by itself: the frame
  pipeline cannot idle while anything is still asking to be drawn.

Absolute frame times were measured on a software-rendered emulator and are not meaningful as such;
the ratios are.

Away from the screen: the app **stands its notification listener down** after ten minutes with
nothing playing and no window of its own on screen — that binding is what keeps the process resident,
so giving it back is what lets the app actually close. It also **follows the system's battery saver**,
with a switch per measure under *Settings → Battery*.

A player switched off under *Settings → Players to follow* does not count as playing for any of this:
an hour of YouTube cannot hold the app open, keep the screen awake, or keep the background moving.

## Threading

`MediaSessionRepository` is **main-thread confined**. Its state is reached from framework callbacks,
which arrive on the main looper because that is the handler it registers with, and none of it is
synchronised. Its public methods hop to the main thread themselves rather than trusting callers —
a background collector calling `republish()` while a session callback inserted into the same map is a
`ConcurrentModificationException` in the middle of a track change.

## Known constraints

- **The APK is large** (~34 MB release). Kuromoji's dictionary and ML Kit's translation engine are
  nearly all of it. Both are the price of correct Japanese readings and offline translation; both
  could become on-demand downloads later.
- **A release build packages `arm64-v8a` only**, which is every phone made in the last decade. ML Kit
  ships a ~17 MB native library per architecture, so including x86_64 was a third of the download for
  a case that never happens. Debug builds keep both.
- **Right-to-left lyrics** animate per word, not per syllable: splitting an Arabic or Hebrew run into
  syllables breaks the letter joins.
- **Musixmatch, Spotify and Apple Music** are undocumented endpoints, treated as optional throughout —
  a failure drops the provider, never the app.
- **Depth blur** relies on drawing text as transparent glyphs plus a shadow layer. If it renders oddly
  on some device, *Depth blur* in Settings turns it off.
- **Furigana on a syllable inside a longer word** takes a proportional slice of that word's reading.
  Approximate per syllable, but it never loses or repeats a sound.
- Spikerko's own **Spicy Lyrics API** is deliberately not wired up: it would mean posting someone's
  Spotify token to a third party and unpacking a bespoke binary payload, and it is his service to run.
