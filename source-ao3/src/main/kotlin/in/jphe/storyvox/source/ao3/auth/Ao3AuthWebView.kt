package `in`.jphe.storyvox.source.ao3.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import `in`.jphe.storyvox.source.ao3.net.Ao3Api

/**
 * Composable that hosts a WebView pointed at AO3's login page (PR2 of #426).
 *
 * Mirrors [`RoyalRoadAuthWebView`][in.jphe.storyvox.source.royalroad.auth.RoyalRoadAuthWebView]
 * — storyvox never sees the password. We watch every navigation and
 * once the cookie set for `archiveofourown.org` contains
 * `_otwarchive_session`, sign-in succeeded. Capture every AO3 cookie,
 * hand them to the caller via [onSession], and let the host activity
 * persist them through EncryptedSharedPreferences.
 *
 * In addition to the cookie payload, we also extract the username
 * from the WebView's current URL once it has redirected to
 * `/users/<username>` — AO3 lands users on that page after a
 * successful sign-in, and the subscriptions / Marked-for-Later
 * endpoints are username-keyed (`/users/<username>/subscriptions`).
 * Without the username, the authed list surfaces wouldn't have an
 * endpoint to call. The capture path stashes the username into the
 * cookie map under a magic `__storyvox_user` key (never a real AO3
 * cookie name; namespaced so the hydrator can pluck it out before
 * pushing the rest into the OkHttp jar).
 *
 * Cancellation: caller dismisses by removing this composable from
 * composition; the AndroidView's onRelease tears down the WebView
 * cleanly and fires [onCancelled] if [onSession] was never called.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Ao3AuthWebView(
    modifier: Modifier = Modifier,
    onSession: (Ao3SessionCookies) -> Unit,
    onCancelled: () -> Unit = {},
) {
    val capturedHandler = remember { Ao3CapturedSession(onSession) }
    // #719 — track the WebView so BackHandler can drive its history.
    // Same shape as RoyalRoadAuthWebView; see the comment there for
    // why `canGoBack()` is read inside the BackHandler `enabled`
    // lambda rather than snapshotted into Compose state.
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
        canGoBack = webView?.canGoBack() == true
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = Ao3Api.USER_AGENT
                // #688 — keep the autofill framework alive inside our WebView.
                // Default `importantForAutofill` toggled across API levels; being
                // explicit ensures Bitwarden / 1Password / Chrome autofill see
                // the form-field tree on every API >= 26. saveFormData is
                // separately gated and also flips between OS versions, so set
                // it directly rather than trust the default.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                @Suppress("DEPRECATION")
                settings.saveFormData = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    // AO3 sets `_otwarchive_session` on the response to
                    // the login POST and immediately redirects to
                    // `/users/<username>`. Watch both lifecycle hooks so
                    // we catch the cookie regardless of whether AO3 sets
                    // it pre- or post-redirect.
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        canGoBack = view?.canGoBack() == true
                        tryCapture(url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBack = view?.canGoBack() == true
                        tryCapture(url)
                    }

                    // #934 — `onPageFinished` doesn't fire for JS-driven
                    // navigation (history.pushState / replaceState, hash
                    // changes, in-page redirects). Without this override,
                    // `canGoBack` goes stale between full-page loads and
                    // BackHandler's `enabled` lambda evaluates against
                    // out-of-date state. `doUpdateVisitedHistory` is the
                    // canonical WebView hook that fires for every history
                    // mutation, JS-initiated or otherwise.
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        canGoBack = view?.canGoBack() == true
                    }

                    private fun tryCapture(currentUrl: String?) {
                        val cookies = readAllAo3Cookies()
                        if (!cookies.containsKey(IDENTITY_COOKIE)) return
                        val username = extractUsernameFromUrl(currentUrl)
                        val payload = if (username != null) {
                            cookies + (USERNAME_KEY to username)
                        } else {
                            cookies
                        }
                        capturedHandler.deliver(payload)
                    }
                }
                loadUrl("${Ao3Api.BASE_URL}/users/login")
            }.also { webView = it }
        },
        onRelease = { wv ->
            if (!capturedHandler.delivered) onCancelled()
            // #720 — `WebView.destroy()`'s javadoc requires the WebView to
            // be detached from its parent first, with no pending loads or
            // active client references. Same teardown sequence as
            // RoyalRoadAuthWebView; see the comment there for why each
            // step is required (renderer-process leak, mid-redirect
            // cookie-write window, closure-over-composable-scope in our
            // WebViewClient).
            wv.stopLoading()
            wv.webViewClient = WebViewClient()
            wv.clearHistory()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
        }
    }
}

private const val IDENTITY_COOKIE = "_otwarchive_session"
// USERNAME_KEY + extractUsernameFromUrl live in [Ao3AuthHelpers] for
// JVM unit-test reach without dragging the Compose / WebView surface
// in.

/**
 * Walk the `https://archiveofourown.org` cookie set. AO3 serves only
 * under the bare host (no `www.` subdomain), so a single read is
 * enough; we still strip any duplicate entries defensively.
 */
private fun readAllAo3Cookies(): Map<String, String> {
    val merged = LinkedHashMap<String, String>()
    val raw = CookieManager.getInstance().getCookie(Ao3Api.BASE_URL) ?: return emptyMap()
    raw.split(";").forEach { entry ->
        val trimmed = entry.trim()
        val eq = trimmed.indexOf('=')
        if (eq > 0) merged[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
    }
    return merged
}

/** Captured payload — cookies plus (via [USERNAME_KEY]) the AO3
 *  username extracted from the post-login redirect URL. */
data class Ao3SessionCookies(val cookies: Map<String, String>)

private class Ao3CapturedSession(private val onSession: (Ao3SessionCookies) -> Unit) {
    var delivered: Boolean = false
        private set

    fun deliver(cookies: Map<String, String>) {
        if (delivered) return
        delivered = true
        onSession(Ao3SessionCookies(cookies))
    }
}
