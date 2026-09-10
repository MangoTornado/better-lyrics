# Optional tokens

**You do not need any of this.** Melisma finds word-by-word lyrics for most music with
nothing configured at all — three of its sources need no account, and they are on by default.
This page is for the cases where they come up short: an obscure track, a wrong match, or lyrics
that exist only in a paid catalogue.

Everything here is *your own* credential, read out of a browser session you are already signed in
to. There is no Melisma account and no server in between.

| I want… | Paste this | Lasts |
|---|---|---|
| Better matching on Japanese/Korean/Chinese | nothing — NetEase is already on | — |
| The lyrics the Spotify app shows | [Spotify access token](#spotify) | ~1 hour |
| Bigger cover art, artist images, tempo | [Spotify access token](#spotify) | ~1 hour |
| Apple's word-by-word lyrics and translations | [Apple developer + user token](#apple-music) | months / session |
| A wider Musixmatch catalogue | [Musixmatch token](#musixmatch) | months |
| Regional NetEase catalogues | [NetEase cookie](#netease) | months |

Where they go: **Settings → Tokens and endpoints**, except the Spotify access token, which lives
under **Settings → Developer** (see [DEVELOPER-OPTIONS.md](DEVELOPER-OPTIONS.md)).

---

## Getting a value onto your phone

All of these come from a desktop browser's developer tools. The easiest way across is to paste it
into a note that syncs, or a message to yourself. If the phone is plugged in and you have `adb`:

```bash
adb shell input text 'paste-the-value-here'   # tap the field in the app first
```

Once they are in, you never have to do it twice: **Settings → Storage and backup** writes them to a
file, encrypted under a passphrase, so a new phone is a restore rather than another trip through
developer tools.

---

## Spotify

**Gives you:** Spotify's own line-synced lyrics, the full-size cover (640 px instead of the
200–300 px thumbnail a media session publishes), the artist's image for the *Artist* background,
and the song's tempo, which paces how fast the background drifts.

Spotify closed the endpoint that traded a cookie for a token, so the token itself has to be copied
out of the web player.

1. Open <https://open.spotify.com> in a desktop browser and sign in.
2. Open developer tools (<kbd>F12</kbd>) and select the **Network** tab.
3. Play something, then click the lyrics button in the player.
4. Filter the request list for `spclient` and click any request to it.
5. Under **Headers → Request Headers**, find `authorization: Bearer eyJ…` and copy the value.
6. In the app: **Settings → Developer → Developer options** on, then paste into
   **Spotify web access token**.

Paste it with or without the `Bearer ` prefix — either is understood. The app reads the token's own
expiry, so it knows when it has gone stale.

### Not having to do that every hour

An access token is good for about an hour, which makes the above an errand rather than a setting.

1. In the same browser: **Application → Cookies → `https://open.spotify.com`**, copy the value of
   the `sp_dc` cookie. That one lasts about a year.
2. Paste it into **Settings → Tokens and endpoints → Spotify sp_dc cookie**.
3. Turn on **Settings → Developer → Renew that token automatically**.

The app then loads the web player in a hidden WebView with your cookie and reads the token the
player is handed — once an hour at most, and nothing is shown on screen. It is behind developer
options and off by default because it is still automated access to a service whose terms discourage
it; for one person on their own device that is their call to make, not something to ship switched
on.

> The app deliberately does **not** reproduce the signature Spotify's player signs that request
> with. That would mean lifting a secret to defeat a check. Here the player signs its own request,
> as itself, and the token that comes back is one your browser would have been given anyway.

## Apple Music

**Gives you:** the best data of any source — word-by-word timings with official romanizations and
translations, the same ones the Apple Music app shows.

Two values, and they do different jobs. The **developer token** identifies an app to Apple and lasts
months; the **user token** proves you have a subscription and is what unlocks lyrics.

**Developer token** (no subscription needed, and enough on its own for artwork):

1. Open <https://music.apple.com> in a desktop browser.
2. Developer tools → **Network**, then reload the page.
3. Filter for `amp-api` and click any request.
4. Copy the `authorization: Bearer eyJ…` header value.
5. Paste into **Settings → Tokens and endpoints → Apple Music developer token**.

**User token** (needs an active Apple Music subscription):

1. Signed in at music.apple.com, open **Application → Cookies → `https://music.apple.com`**.
2. Copy the value of `media-user-token`.
3. Paste into **Apple Music user token**.

Then set **Apple Music storefront** to your country's two-letter code — `us`, `gb`, `jp` — and turn
**Apple Music** on under *Where lyrics come from*, where it is off by default.

If you have an Apple Developer account you can generate a proper developer token from a MusicKit
key instead, which is the supported route and lasts up to six months. It is not worth $99 a year for
this, which is why the browser's own token is documented first.

## Musixmatch

**Gives you:** a wider catalogue, and no shared rate limit. Musixmatch works with nothing pasted —
the app mints an anonymous token for itself — so this only matters if you hit its limits.

1. Sign in at <https://www.musixmatch.com>.
2. Developer tools → **Application → Cookies → `https://www.musixmatch.com`**.
3. Copy the value of `musixmatchUserToken`.
4. Paste into **Settings → Tokens and endpoints → Musixmatch user token**.

That cookie holds a small JSON object rather than a bare token. Paste the whole thing; the app pulls
the token out itself, whichever of the several it contains turns out to work.

## NetEase

**Gives you:** higher per-address limits and some regional catalogues. NetEase already works with
nothing pasted, and it is the best source there is for Japanese, Korean and Chinese — it ships
hand-checked romanization and translation alongside the timings.

1. Sign in at <https://music.163.com>.
2. Developer tools → **Application → Cookies**, and copy the whole cookie string for the site.
3. Paste into **Settings → Tokens and endpoints → NetEase cookie**.

## Self-hosted instances

The same section takes a different address for **LRCLIB**, **NetEase** and the **AMLL TTML
Database** if you run a mirror of one. Leave them alone to use the public ones.

For a caching server of your own in front of all of it, see
[CACHE-SERVER.md](CACHE-SERVER.md).

---

## Where these are kept

In the app's private preferences, in a separate file from the ordinary settings, and each is sent
only to the service it belongs to. Nothing is uploaded anywhere else — there is no backend.

Two things worth knowing:

- A source with nothing to authenticate with is **skipped rather than queried**, so leaving one
  enabled while you go and find its token costs nothing.
- **Settings → Developer → Test the sources** asks every source about the track playing now and
  reports what each said, which is the quickest way to find out whether a value you pasted works.
