# Credits and licences

Better Lyrics is a **derivative work of [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)**
by **Spikerko**, and is licensed under the **GNU Affero General Public License v3.0** as a
result. See [LICENSE](LICENSE).

## Spicy Lyrics — Spikerko

https://github.com/Spikerko/spicy-lyrics · AGPL-3.0

The reason this app looks and moves the way it does. Ported from it, not merely inspired
by it:

| Here | There |
|---|---|
| `core/Spring.kt` | `src/modules/Spring.ts` |
| `core/Spline.kt` | its use of the `cubic-spline` package |
| `ui/lyrics/LyricsAnim.kt` | the spline ranges and spring constants in `src/utils/Lyrics/Animator/` |
| `ui/lyrics/LyricsRenderer.kt` | the fill/glow/defocus model in `src/css/Lyrics/Mixed.css` and `LyricsAnimator.ts` |
| `ui/lyrics/LyricsLayout.kt` | the syllable/word/letter structure of `src/utils/Lyrics/Applyer/` |
| `lyrics/model/Lyrics.kt` | the `Static` / `Line` / `Syllable` lyric model |
| `lyrics/parse/TtmlParser.kt` | the TTML dialect handled by `src/utils/Lyrics/ttml/parser.ts` |
| `lyrics/romanize/` | the script-detection and romanization strategy of `ProcessLyrics.ts` |
| the settings surface | its Settings panel, section for section |

Specific values carried over include the word-scale overshoot to 1.0505 at 70 %, the
letter-scale peak of 1.175, the lift curves, the 4 + 2·glow px text shadow, the 1.25 px
per line defocus capped at 6.83 px, the 0.51 / 0.497 / 1.0 line opacities, the 20 %
gradient band, the three-second interlude threshold and its dot timing padding, and the
one-second letter-emphasis threshold.

The **Spicy Lyrics font** is Spikerko's own asset and is *not* bundled here; the font
setting picks between the fonts already on the device instead.

Two of its features are reached differently. Its **Artist Header** background reads the
artist banner through Spotify's internal GraphQL gateway, which needs a persisted-query
hash tied to a web-player release; this app uses the documented artist endpoint instead.
Its **Spicy Lyrics API** is not used at all — see the note at the end of this file.

## Spring physics — Fraktality

https://github.com/Fraktality/spr · MIT

`core/Spring.kt` is a Kotlin port of `spr`, by way of Spicy Lyrics' TypeScript port. The
analytic damped-harmonic solution, the sleep thresholds and the critically-damped /
under-damped / over-damped branches are all its work.

## cubic-spline — Morgan Herlocker

https://github.com/morganherlocker/cubic-spline · MIT

`core/Spline.kt` reimplements its natural cubic spline — the `getNaturalKs` matrix
construction and the Hermite-form evaluation — so the curves lifted from Spicy Lyrics
bend identically.

## Beautiful Lyrics — surfbryce

https://github.com/surfbryce/beautiful-lyrics

A reference point for what good lyrics rendering looks like, and the project that
established much of this space. **No code from it is used here** — it carries no licence
grant, so it was read only as prior art, not as a source.

## AMLL TTML Database — amll-dev and its contributors

https://github.com/amll-dev/amll-ttml-db · **CC0 1.0** (public domain dedication)

The reason this app can show hand-timed, word-by-word lyrics with no account of any kind.
A community corpus of TTML — the same format Apple Music's own lyrics are authored in —
indexed by Spotify, Apple Music, NetEase, QQ Music and ISRC, with syllable timings, duet
agents, background vocals, readings and translations.

The app queries it at runtime rather than shipping any of it, and the contributor who
timed the file you are reading is credited by name under the last line. The one exception is
`app/src/test/resources/amll-sample.ttml`: the opening of a real response, kept verbatim so
the parser is tested against the actual dialect. CC0 asks for nothing, but it was made by
hand and is worth saying so.

Its API server is separate and also free software:
[amll-ttml-api](https://github.com/amll-dev/amll-ttml-api) (MIT / Apache-2.0). The default
endpoint is the project's own instance, run by volunteers — **Settings → AMLL TTML
instance** exists so that heavy users can point the app at their own copy instead.

## Kuromoji — Atilika

https://github.com/atilika/kuromoji · Apache-2.0

Japanese morphological analysis. It is the only reason kanji get the right reading, and
the IPADIC dictionary it ships is the largest single thing in the APK.

## ML Kit Translation — Google

https://developers.google.com/ml-kit/language/translation · Google APIs Terms of Service

On-device translation.

## Other libraries

| Library | Licence |
|---|---|
| AndroidX (Core, Activity, Lifecycle, Compose, Palette) | Apache-2.0 |
| Kotlin, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 |
| OkHttp / Okio — Square | Apache-2.0 |
| JUnit 4 | Eclipse Public License 1.0 |

## Lyrics sources

Lyrics belong to their writers and publishers. This app stores nothing but a local cache
on your own device, and queries the same community and public sources the desktop
extensions do:

- **[AMLL TTML Database](https://github.com/amll-dev/amll-ttml-db)** — community-timed,
  public-domain, word-by-word. The first thing tried after your own files, because nothing
  else gives syllable timings without a token.
- **[LRCLIB](https://lrclib.net)** — an open, key-less, community-run database. Please
  read their guidelines before pointing anything high-volume at it.
- **NetEase Cloud Music** — public web endpoints. Also the source of the hand-checked
  romanizations and translations that make East Asian tracks work well.
- **Musixmatch** — via the anonymous token the desktop web player issues. Undocumented.
- **Spotify** (`color-lyrics`) — the lyrics the Spotify app shows, Musixmatch-provided.
  Undocumented, and needs your own signed-in cookie.
- **Apple Music** — official syllable-level lyrics, romanizations and translations. Needs
  your own tokens and an active subscription.

**Spikerko's Spicy Lyrics API is deliberately not used.** Reaching it would mean posting a
user's Spotify token to a third party and porting his bespoke binary payload format, and it
is his service to run rather than this app's to lean on.

None of these are affiliated with this app, and none of them endorse it.
