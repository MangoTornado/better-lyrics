package com.betterlyrics.app.lyrics.provider

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Gets a Spotify web token by being the web player, in a WebView.
 *
 * Spotify closed the endpoint that traded an `sp_dc` cookie for a token, so the only thing that
 * still produces one is the player itself — and Android ships a whole Chromium to run it in. Load
 * open.spotify.com with the cookie attached and read the `Authorization` header off the request the
 * player makes to its own API. Which turns a token that has to be pasted in every hour into one the
 * app renews on its own.
 *
 * ### Why this is not the thing the app declines to do
 *
 * [SpotifyWebToken.BLOCKED_BY_SPOTIFY] explains that the player signs its token request with a
 * time-based code derived from a secret in its own JavaScript, and that reproducing that signature
 * would be working around an access control whose owner has said not to. That reasoning still
 * stands, and this is a different thing:
 *
 * - Reimplementing the signature means lifting a secret to defeat a check.
 * - Running the real player in a real browser engine means *being* the client the endpoint is for.
 *   The player computes its own signature, as itself, with the user's own cookie, and the token
 *   that comes back is one their own browser would have received.
 *
 * Nothing is bypassed. What it is, plainly, is automated access to a service whose terms discourage
 * it — which is why it sits behind Developer options, off by default, rather than being a feature
 * every install runs. This app is distributed; a private server is not.
 *
 * ### How the token is caught
 *
 * A script injected *before any page script runs* wraps `fetch` and `XMLHttpRequest`, so it sees
 * headers the player sets on its own requests. That ordering is the whole trick, and it needs
 * `androidx.webkit`'s document-start injection — `WebViewClient.shouldInterceptRequest` is no use
 * here, because the headers it reports for a `fetch` do not reliably include `Authorization`.
 *
 * Nothing is rendered. The WebView is never attached to a window; it exists to run one page's
 * JavaScript and is destroyed the moment a token appears or the timeout passes.
 */
open class SpotifyBrowserToken(private val context: Context) {

    /**
     * The result, in the terms Settings can show.
     *
     * A failure says which failure it was, because "no cookie", "this WebView is too old" and "the
     * cookie has expired" need three different things done about them.
     */
    sealed interface Result {
        data class Harvested(val token: String, val expiresAt: Long?) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Whether the player considers itself signed in.
     *
     * The player asks `/api/token` before it asks for anything else, and the answer carries
     * `isAnonymous`. A page with no usable cookie still gets a token — a real, valid, 140-character
     * one — but with no user attached, and `color-lyrics` answers **400** to it. Verified against the
     * live endpoint with no cookie set: 400 with `market=from_token`, with `market=US`, and with no
     * market at all. So the status is not about the request being malformed, and a token that
     * harvests cleanly can still be useless.
     *
     * Length is a tell but not the test — measured against the live player, an anonymous token was
     * 140 characters and a signed-in one 403. [MIN_TOKEN_LENGTH] accepts both, deliberately: a
     * length threshold would be guessing at a format Spotify can change, whereas `isAnonymous` is
     * the player's own answer.
     *
     * Catching it here is the difference between "HTTP 400" and "your cookie is not signing you in".
     */
    private enum class Session { UNKNOWN, ANONYMOUS, SIGNED_IN }

    /**
     * Loads the player and waits for a token.
     *
     * Returns [Result.Failed] rather than throwing: a missing token is an ordinary outcome and the
     * caller has other sources.
     */
    open suspend fun harvest(spDcCookie: String, timeoutMs: Long = 45_000): Result {
        val cookie = spDcCookie.trim()
        if (cookie.isEmpty()) return Result.Failed("no sp_dc cookie is set")

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Chromium 83 and up. A device this old is rare, and the alternative — injecting after
            // the page starts — is a race against the player's own bundle that would fail
            // intermittently, which is worse than failing clearly.
            return Result.Failed(
                "this device's WebView is too old to read the token — update Android System WebView",
            )
        }

        // Everything below runs on the main thread, which is where a WebView must be built, used and
        // destroyed. Holding the whole lifetime inside one `withContext` is what makes the teardown
        // in `finally` legal: it cannot end up on a coroutine's cancellation thread, which is how a
        // timeout used to take the app down with "a WebView method was called on thread …".
        return withContext(Dispatchers.Main) {
            var view: WebView? = null
            try {
                view = WebView(context)
                configure(view, cookie)

                withTimeoutOrNull(timeoutMs) { awaitToken(view) }
                    ?: Result.Failed(
                        "the player did not produce a token within ${timeoutMs / 1000}s — " +
                            "if the cookie has expired, copy a fresh one from a signed-in browser",
                    )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A dev-menu diagnostic must never be the thing that kills the app. Anything the
                // WebView stack can throw — a missing provider, an update in progress, an API that
                // rejects its arguments — becomes a line the user can read and act on.
                Log.w(TAG, "harvest failed", error)
                Result.Failed(
                    "${error.javaClass.simpleName}: ${error.message ?: "no detail"}",
                )
            } finally {
                view?.let {
                    runCatching {
                        it.stopLoading()
                        // The profile holds the session just used, so it does not outlive the job.
                        it.clearCache(true)
                        it.destroy()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView, cookie: String) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The player serves a different app to a mobile user agent, with a different token
            // flow. Ask for the desktop one it expects.
            userAgentString = SpotifyWebToken.WEB_USER_AGENT
            blockNetworkImage = true
            loadsImagesAutomatically = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
            // The first argument is a URL, not a host. It was `".spotify.com"`, which has no scheme
            // — and a `Secure` cookie is only accepted from a secure origin, so the cookie was
            // being dropped and the player loaded signed out. It still issued a token, which is why
            // this looked like it worked: an anonymous token is a real token, just useless. The
            // scope now comes from an explicit `Domain` instead of from the URL, so it still covers
            // the accounts host and the player alike.
            setCookie(
                PLAYER_URL,
                "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure; HttpOnly; SameSite=None",
            )
            flush()
        }
    }

    /** Resolves with the first real token the page's own requests carry. */
    private suspend fun awaitToken(view: WebView): Result = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        // `/api/token` is answered before the player uses a Bearer anywhere, so by the time a token
        // shows up this is normally already known. If it is not, the token is taken as-is rather
        // than held back — a working harvest must not start failing on a missed signal.
        val session = java.util.concurrent.atomic.AtomicReference(Session.UNKNOWN)

        val finish = { result: Result ->
            if (settled.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(result)
            }
        }

        view.addJavascriptInterface(
            TokenBridge(
                onToken = { value ->
                    val token = bearerValue(value)
                    if (token != null && token.length >= MIN_TOKEN_LENGTH) {
                        if (session.get() == Session.ANONYMOUS) {
                            finish(
                                Result.Failed(
                                    "the player loaded but is not signed in, so its token cannot " +
                                        "read lyrics — copy a fresh sp_dc cookie from a signed-in " +
                                        "browser",
                                ),
                            )
                        } else {
                            finish(Result.Harvested(token, SpotifyWebToken.expiryOf(token)))
                        }
                    }
                },
                onSession = { anonymous ->
                    session.set(if (anonymous) Session.ANONYMOUS else Session.SIGNED_IN)
                    // Fail as soon as it is known, rather than waiting out the timeout for a token
                    // that would be rejected anyway.
                    if (anonymous) {
                        finish(
                            Result.Failed(
                                "Spotify did not sign the player in — the sp_dc cookie is expired " +
                                    "or wrong, so copy a fresh one from a signed-in browser",
                            ),
                        )
                    }
                },
            ),
            BRIDGE,
        )

        // Real origins, not a wildcard host: see [ALLOWED_ORIGINS]. Passing the wrong shape threw
        // out of the button press rather than failing to a message, which is how this crashed.
        WebViewCompat.addDocumentStartJavaScript(view, injectedScript(), ALLOWED_ORIGINS)
        view.loadUrl(PLAYER_URL)
    }

    /**
     * The bridge, as a named class.
     *
     * Named rather than an anonymous object so a keep rule can actually name it: R8 strips or
     * renames what it cannot see being called, and nothing in Kotlin calls this — the page does.
     * See `proguard-rules.pro`.
     */
    private class TokenBridge(
        private val onToken: (String) -> Unit,
        private val onSession: (Boolean) -> Unit,
    ) {
        @JavascriptInterface
        fun onToken(value: String) {
            onToken.invoke(value)
        }

        @JavascriptInterface
        fun onSession(anonymous: Boolean) {
            onSession.invoke(anonymous)
        }
    }

    companion object {
        /** The script, for a test to assert on: it is the part with the delicate contract. */
        fun injectedScriptForTest(): String = injectedScript()

        /** The origin rules, so a test can hand them to the validator that rejected the last set. */
        fun allowedOriginsForTest(): Set<String> = ALLOWED_ORIGINS

        private const val TAG = "SpotifyBrowserToken"
        private const val PLAYER_URL = "https://open.spotify.com/"

        /**
         * Where the injected script is allowed to run.
         *
         * Real origins, because the rule format does not accept a bare wildcard host — a scheme
         * followed by only an asterisk is rejected outright, which is what threw straight out of the
         * button press. A host, or a leading `*.` and a domain, is what it wants.
         *
         * The player is one page, so one origin would do; the accounts host is included because a
         * signed-out session redirects through it.
         */
        private val ALLOWED_ORIGINS = setOf(
            "https://open.spotify.com",
            "https://*.spotify.com",
        )
        private const val BRIDGE = "BetterLyricsTokenBridge"

        /**
         * Below this a bearer is one of the anonymous tokens handed out before sign-in.
         *
         * Those are issued to a logged-out visitor and read nothing, so catching one and reporting
         * success would look like a working setup that returns no lyrics.
         */
        private const val MIN_TOKEN_LENGTH = 100

        /**
         * Watches for the header, from inside the page.
         *
         * `fetch` and `XMLHttpRequest` are both wrapped because the player has used each, and both
         * wrappers pass everything through untouched — this reads, it does not interfere. The
         * bridge is called on every candidate; the Kotlin side decides which one is real.
         */
        private fun injectedScript(): String = """
            (function () {
              if (window.__blTokenWatch) return;
              window.__blTokenWatch = true;

              var report = function (value) {
                try {
                  if (value && String(value).indexOf('Bearer ') === 0) {
                    $BRIDGE.onToken(String(value));
                  }
                } catch (e) {}
              };

              var readHeaders = function (headers) {
                if (!headers) return;
                try {
                  if (typeof headers.get === 'function') {
                    report(headers.get('authorization'));
                    return;
                  }
                  for (var name in headers) {
                    if (String(name).toLowerCase() === 'authorization') report(headers[name]);
                  }
                } catch (e) {}
              };

              // The player's own answer to "am I signed in?". Read from the response rather than
              // guessed at: an anonymous session still yields a full-length token, and the only
              // place the difference is stated is this body.
              var reportSession = function (url, response) {
                try {
                  if (String(url).indexOf('/api/token') === -1) return;
                  response.clone().json().then(function (body) {
                    try {
                      if (body && typeof body.isAnonymous === 'boolean') {
                        $BRIDGE.onSession(body.isAnonymous);
                      }
                    } catch (e) {}
                  }).catch(function () {});
                } catch (e) {}
              };

              var nativeFetch = window.fetch;
              if (nativeFetch) {
                window.fetch = function (input, init) {
                  try {
                    if (init) readHeaders(init.headers);
                    if (input && input.headers) readHeaders(input.headers);
                  } catch (e) {}
                  var url = '';
                  try {
                    url = (input && input.url) ? input.url : String(input);
                  } catch (e) {}
                  return nativeFetch.apply(this, arguments).then(function (response) {
                    reportSession(url, response);
                    return response;
                  });
                };
              }

              var nativeSetHeader = XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
                try {
                  if (String(name).toLowerCase() === 'authorization') report(value);
                } catch (e) {}
                return nativeSetHeader.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
