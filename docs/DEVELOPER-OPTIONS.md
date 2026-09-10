# Developer options

**Nothing here is needed to use the app.** It is a testing surface: a token that has to be replaced
every hour, a caching server of your own, and two buttons that say what each lyrics source actually
did. With the switch off none of it runs — a server URL left in preferences stops being used rather
than quietly answering.

Turn it on: **Settings → Developer → Developer options**.

| Option | What it is for |
|---|---|
| [Spotify web access token](#spotify-web-access-token) | Brings back Spotify's lyrics, artwork and tempo for about an hour |
| [Renew that token automatically](#renew-that-token-automatically) | Removes the hourly errand |
| [Cache server URL / key](#cache-server) | Points the app at a lyrics server of your own |
| [How to use it](#how-to-use-it) | Alongside the other sources, or instead of them |
| [Ask the server for artwork and tempo](#ask-the-server-for-artwork-and-tempo) | Reads the extras it has collected |
| [Test the sources](#test-the-sources) | What every source said about the track playing now |
| [Test the server](#test-the-server) | Which of *your server's* own sources and tokens work |

---

## Spotify web access token

An access token copied out of the web player, which restores Spotify's own lyrics plus the full-size
cover, the artist image and the song's tempo. It is here rather than under *Tokens and endpoints*
because something that lasts an hour is not a setting, it is an errand.

[TOKENS.md → Spotify](TOKENS.md#spotify) has the six steps to get one. Paste it with or without the
`Bearer ` prefix. Once accepted, the line underneath says so — or says what is wrong with it.

## Renew that token automatically

With your `sp_dc` cookie pasted under *Tokens and endpoints*, this loads the web player in a hidden
WebView and reads the token the player is handed, at most once an hour. Nothing appears on screen and
the view is destroyed as soon as a token arrives.

**Renew now** does it immediately, which is how you find out whether the cookie still works, and the
token it fetched is shown in full with a countdown — a masked field could not tell you that it
worked.

A token you pasted by hand is never overwritten: it is treated as your choice. Clear the field to let
the renewal take over.

Off by default. See the note in [TOKENS.md](TOKENS.md#not-having-to-do-that-every-hour) about why
this is a decision for whoever runs the build rather than a default.

## Cache server

A server of your own that sits in front of the free sources and remembers what it fetched, so an
endpoint run by volunteers is asked once per song instead of once per listener per play.

- **Cache server URL** — leave empty to not use one. The app sends
  `GET {url}/v1/lyrics?title=&artist=&album=&durationMs=&spotifyId=` and accepts TTML, LRC, or JSON
  wrapping either.
- **Cache server key** — only needed if the server is not on your own network. Sent as
  `Authorization: Bearer <key>`. A server on your Wi-Fi should let a lookup through without one; a
  server holding an Apple Music token should not answer strangers.

The full request and response contract is in **[CACHE-SERVER.md](CACHE-SERVER.md)**, and
[melisma-server](https://github.com/MelismaApp/melisma-server) implements it.

### How to use it

| Mode | Behaviour |
|---|---|
| **Alongside the others** | Asked in parallel with every enabled source, and ranked among them in *Where lyrics come from*. The best answer still wins, so the app keeps working whatever the server does. |
| **Only the cache server** | Nothing else is asked. A track with no lyrics means the server could not answer it. |

Two modes because they answer different questions. *Alongside* is for filling the cache while the
server is still being written — real traffic reaches it, and a gap costs nothing. *Only* is for
testing the server itself, where "nothing else could have answered" is the point.

Your own imported files win in either mode, and answers are still cached on the phone for 30 days, so
use **Settings → This track → Look this track up again** to force a fresh request while iterating.

### Ask the server for artwork and tempo

Off by default. On, the app also asks the server for a cover URL, an artist image, an ISRC and a
tempo, and only when no token on the phone can answer.

The reason it exists: a Spotify token lasts an hour and an Apple one a few months, but a cover URL,
an ISRC and a tempo, once known, are true forever. The server collects them for itself every time it
looks a track up, so the tokens live on that one machine rather than on every phone.

Read-only. The phone sends a title and an artist and has nothing to contribute; there is no write
endpoint to secure.

## Test the sources

Asks every source about the track playing now — ignoring the cache — and prints what each one said.

This exists because a source that is switched off, one that cannot reach its endpoint, and one that
reached it and found nothing all look identical from the lyrics screen, which makes "only some of
them work" impossible to act on. It is the fastest way to check a token you have just pasted.

## Test the server

Asks *your server* to test each of its own sources and report back: off, needs a token, token
expired, or working, with the timing.

The test above cannot answer this. Pointing the app at a server puts every source behind one hop, and
"the server returned no lyrics" covers a source switched off, a token that expired last week, and a
track nobody has transcribed. No credential comes back — only whether one works.

No answer at all usually means the URL is wrong, or the server is not on this network and wants the
key.
