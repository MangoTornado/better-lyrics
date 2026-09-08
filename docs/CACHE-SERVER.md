# Cache server contract

What the app sends to a lyrics server of your own, and what it will accept back. Set the
URL under **Settings → Developer → Cache server URL**, which only appears once **Developer
options** is on.

This exists so the server can be written against a fixed target. Nothing here is required
to use the app, and with the developer switch off none of it runs — a URL left behind in
preferences stops being used rather than quietly answering.

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

The app sends no authentication and no identifying header. If you want the server private,
put it behind something the network layer handles.

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
