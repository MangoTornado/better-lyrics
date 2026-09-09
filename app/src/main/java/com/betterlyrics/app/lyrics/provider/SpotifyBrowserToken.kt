package com.betterlyrics.app.lyrics.provider

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) { run(cookie) }
                ?: Result.Failed(
                    "the player did not produce a token within ${timeoutMs / 1000}s — " +
                        "if the cookie has expired, copy a fresh one from a signed-in browser",
                )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun run(cookie: String): Result = suspendCancellableCoroutine { continuation ->
        val settled = AtomicBoolean(false)
        var webView: WebView? = null

        fun finish(result: Result) {
            if (!settled.compareAndSet(false, true)) return
            webView?.let {
                it.stopLoading()
                // The profile holds the session that was just used, so it does not outlive the job.
                it.clearCache(true)
                it.destroy()
            }
            webView = null
            if (continuation.isActive) continuation.resume(result)
        }

        val view = WebView(context)
        webView = view

        continuation.invokeOnCancellation {
            // The timeout arrives here. Tearing the view down has to happen on the thread that
            // built it, and this block already runs there.
            finish(Result.Failed("cancelled"))
        }

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
            // On `.spotify.com` so it reaches the accounts host and the player alike.
            setCookie(".spotify.com", "sp_dc=$cookie; Path=/; Secure; HttpOnly; SameSite=None")
            flush()
        }

        view.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onToken(value: String) {
                    val token = bearerValue(value) ?: return
                    if (token.length < MIN_TOKEN_LENGTH) return
                    finish(Result.Harvested(token, SpotifyWebToken.expiryOf(token)))
                }
            },
            BRIDGE,
        )

        WebViewCompat.addDocumentStartJavaScript(view, injectedScript(), setOf("https://*"))
        view.loadUrl(PLAYER_URL)
    }

    companion object {
        /** The script, for a test to assert on: it is the part with the delicate contract. */
        fun injectedScriptForTest(): String = injectedScript()

        private const val PLAYER_URL = "https://open.spotify.com/"
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

              var nativeFetch = window.fetch;
              if (nativeFetch) {
                window.fetch = function (input, init) {
                  try {
                    if (init) readHeaders(init.headers);
                    if (input && input.headers) readHeaders(input.headers);
                  } catch (e) {}
                  return nativeFetch.apply(this, arguments);
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
