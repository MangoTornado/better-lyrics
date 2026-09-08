# Cache server contract

What the app sends to a lyrics server of your own, and what it will accept back. Set the
URL under **Settings → Developer → Cache server URL**, which only appears once **Developer
options** is on.

This exists so the server can be written against a fixed target. Nothing here is required
to use the app, and with the developer switch off none of it runs — a URL left behind in
preferences stops being used rather than quietly answering.

`CacheServerContractTest` checks this against a response a real server actually sent, so if
either side drifts the build says so. A cache server returning something unreadable otherwise
looks exactly like a track nobody has transcribed, which is the failure worth making loud.

## Why a server at all

The free sources are the reason this app works without an account, and two of them are
somebody's goodwill: LRCLIB asks not to be hammered, and the AMLL endpoint is run by
volunteers. A cache in front of them turns *one request per listener per play* into *one
request per song, ever*. It is also the only way to use a source that is rate-limited per
token rather than per user.

## The request

```
GET {baseUrl}/v1/lyrics?title=…&artist=…&album=…&durationMs=…&spotifyId=…
```

| Parameter | Always sent | Notes |
|---|---|---|
| `title` | yes | The title as the media session reported it, not cleaned up. |
| `artist` | yes | May be several names in one string, as the player published it. |
| `album` | when known | Absent for a queue entry, which publishes none. |
| `durationMs` | when known | Milliseconds. Absent or 0 when the player did not say. |
| `spotifyId` | when known | 22-character track id, when playing from Spotify. |

Everything is URL-encoded with `%20` for spaces. `Accept: application/json,
application/xml, text/plain`.

## Authentication

By default the app sends none, because a lookup cannot expose anything: it carries a title and
an artist, and nothing it can ask for returns a credential. A server on your own network should
let that through.

A server reachable from further away should not — one holding an Apple Music token has no
business answering strangers. Put its key in **Cache server key** and the app sends
`Authorization: Bearer <key>`. Left empty, the header is absent rather than empty, so the same
build works against both.

That is the split
[better-lyrics-server](https://github.com/MangoTornado/better-lyrics-server) implements: its
admin surface, the only part that can read a token, always demands the key; a lookup from the
local network does not.

A request may arrive for a track that is *about to* play rather than one playing now — the
app prefetches the next queued track when the player publishes a queue. Those look
identical and need no special handling.

## The response

`200` with a body in any of these shapes. The app sniffs rather than trusting a content
type, because the three cannot be confused: JSON opens with `{`, TTML with `<`, and LRC
with a timestamp or plain text.

**TTML** — the best case. Word-level timings, duet agents, background vocals, readings and
translations all survive. This is the format Apple Music and the AMLL database use:

```xml
<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word">…</tt>
```

**LRC**, plain or enhanced (`<mm:ss.xx>` word tags):

```
[00:12.34]Line one
[00:15.00]<00:15.00>Word <00:15.40>by <00:15.90>word
```

**JSON envelope** wrapping either:

```json
{
  "status": 200,
  "data": {
    "format": "ttml",
    "lyrics": "<tt …>",
    "source": "amll",
    "providerName": "AMLL TTML DB · by cybaka520"
  }
}
```

- `lyrics` (or `body`) is the only required field.
- `format` may be `ttml` or `lrc`; it is a hint, and the sniffer wins if it disagrees.
- `providerName` is shown under the last line as the credit. Use it to name where the
  lyrics actually came from — `source` is used the same way if `providerName` is absent.
  Without either, every track claims to come from a cache server, which loses the
  attribution the upstream sources are owed.
- Wrapping in `data` is optional; the fields may sit at the top level.

**No lyrics**: any non-2xx, or an empty body. `404` is the obvious one. The app treats a
failure and a miss identically — the provider simply contributes nothing to that track's
lookup — so there is no need to distinguish them on the wire.

**Not lyrics**: markup that is not TTML is refused rather than shown. The realistic accident is
a misconfigured server, or something in front of it, answering `200` with an error page; a
lyric line reading "502 Bad Gateway" is worse than finding nothing. Plain text with no
timestamps *is* accepted, because unsynced lyrics are a real answer.

## Artwork and tempo

Optional, and behind its own switch — **Developer → Ask the server for artwork and tempo**. Off,
none of this is called.

Read-only. The server collects these for itself, every time it looks a track up, so the tokens
live on that one machine rather than on every phone. There is nothing for the app to contribute
and no write endpoint to secure.

The reason it exists: a Spotify access token is good for about an hour and an Apple developer
token for a few months, but a cover URL, an ISRC and a tempo, once known, are true forever. The
server is the thing that outlives the tokens.

```
GET {baseUrl}/v1/extras?title=…&artist=…&album=…&durationMs=…&spotifyId=…
```

Same parameters and same optional `Authorization: Bearer <key>` as the lyrics lookup, and like a
lookup it needs no key from the local network. Answer:

```json
{
  "coverUrl": "https://…/cover.jpg",
  "artistImageUrl": "https://…/artist.jpg",
  "tempo": 87.5,
  "isrc": "JPU901800227",
  "palette": { "bgColor": "1f1f24", "textColor1": "ffffff" },
  "analysis": { "beats": [], "bars": [], "sections": [] },
  "metadata": { "composerName": "…", "albumName": "…" }
}
```

- Every field is optional; an answer with none of them is the same as a 404.
- `cover` and `artistImage` are accepted as aliases, and a `data` wrapper is allowed, as with the
  lyrics.
- The URLs may point anywhere — including back at the server, which is how it serves a copy it
  holds rather than a link to somebody else's.
- `tempo` is beats per minute. It paces the animated background.
- `palette`, `analysis` and `metadata` are held whole and served whole. The app reads none of
  them yet; they are collected because the tokens are the scarce thing, not the storage, and
  `audio-attributes` — which carries the tempo, key, loudness and the beat, bar and section grids
  — was withdrawn from Spotify's public API in November 2024, so a cached copy is the only
  durable one there is.
- Only asked when no token on the phone can answer. A live token is about the track playing now,
  where the server is a record of one that matched before.

## What the app does with it

In **Alongside the others** mode the server is asked first, in parallel with every enabled
source, and the best answer wins on quality: word-synced beats line-synced beats untimed,
and a tie goes to the server because that answer cost nobody a request. A server that is
down, slow or wrong costs nothing, which is what makes this the mode to develop against.

In **Only the cache server** mode nothing else is asked. A track with no lyrics means the
server could not answer it — which is the point.

In both modes:

- A file the user imported for a track still wins outright, and is answered without
  asking anything.
- Answers are cached on the phone for 30 days (misses for 2), so the server is asked once
  per track per month at most. **Settings → This track → Look this track up again** drops
  that entry and forces a fresh request, which is the button to use while iterating.
- Each provider gets 12 seconds before it is abandoned.

## Redistribution

Worth being deliberate about, because caching solves a rate limit and not a licence. A
private server, one user, your own credentials, is a defensible position. The moment other
people query it, it is a redistribution service for content you do not have the rights to
redistribute — which is a different thing entirely, whatever the technical design.

The community-positive version of the same idea already exists: the
[AMLL TTML Database](https://github.com/amll-dev/amll-ttml-db) is CC0 and accepts
contributions.
