package `in`.jphe.storyvox.source.royalroad.auth

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
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds

/**
 * Composable that hosts a WebView pointed at Royal Road's login page.
 *
 * We never see the password. We watch every navigation; once the cookie set
 * for the host contains `.AspNetCore.Identity.Application`, login succeeded.
 * Capture all royalroad.com cookies, hand them to the caller via [onSession],
 * and the host activity persists them via EncryptedSharedPreferences.
 *
 * Cancellation: caller dismisses by removing this composable from composition;
 * the AndroidView's onDispose tears down the WebView cleanly.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RoyalRoadAuthWebView(
    modifier: Modifier = Modifier,
    onSession: (SessionCookies) -> Unit,
    onCancelled: () -> Unit = {},
) {
    val capturedHandler = remember { CapturedSession(onSession) }
    // #719 — track the WebView so BackHandler can drive its history.
    // We can't read `canGoBack()` from a remembered reference alone
    // (it doesn't snapshot into recomposition); reading it inside
    // BackHandler's `enabled` lambda re-evaluates on each Back gesture,
    // which is the behavior we want. A null guard covers the brief
    // moment before AndroidView's factory has run.
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
                settings.userAgentString = RoyalRoadIds.USER_AGENT
                // #688 — keep the autofill framework alive inside our WebView.
                // Default `importantForAutofill` toggled across API levels; being
                // explicit ensures Bitwarden / 1Password / Chrome autofill see
                // the form-field tree on every API >= 26. saveFormData is
                // separately gated and also flips between OS versions, so set
                // it directly rather than trust the default. Royal Road's
                // login form already carries `autocomplete="email"` +
                // `autocomplete="current-password"`, so once the WebView opts
                // in here, password managers latch on with no DOM tweaks.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                @Suppress("DEPRECATION")
                settings.saveFormData = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    // Cloudflare can set its cookies at the very start of the page
                    // (before onPageFinished fires for the post-login redirect).
                    // Watch both lifecycle hooks so we don't miss the identity
                    // cookie in tight redirect chains.
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        canGoBack = view?.canGoBack() == true
                        tryCapture()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        canGoBack = view?.canGoBack() == true
                        tryCapture()
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

                    private fun tryCapture() {
                        val cookies = readAllRoyalRoadCookies()
                        if (cookies.containsKey(IDENTITY_COOKIE)) {
                            capturedHandler.deliver(cookies)
                        }
                    }
                }
                loadUrl("${RoyalRoadIds.BASE_URL}/account/login")
            }.also { webView = it }
        },
        onRelease = { wv ->
            if (!capturedHandler.delivered) onCancelled()
            // #720 — `WebView.destroy()`'s javadoc requires the WebView to
            // be detached from its parent first, with no pending loads or
            // active client references. Skipping these steps leaks the
            // renderer process on some OEM ROMs and lets in-flight
            // requests (e.g., the Cloudflare `__cf_bm` cookie write during
            // a captcha → identity redirect) touch a detached
            // CookieManager. The composable is most likely to be torn
            // down mid-redirect — same timing window that `tryCapture()`
            // on `onPageStarted` + `onPageFinished` is designed to catch.
            wv.stopLoading()
            // Replace our `WebViewClient` (which closes over
            // `capturedHandler` → `onSession` → parent composable scope)
            // with a vanilla one so the rest of the teardown doesn't fire
            // captures back into a dead composable.
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

private const val IDENTITY_COOKIE = ".AspNetCore.Identity.Application"

/**
 * Walk both `https://www.royalroad.com` and `https://royalroad.com` cookie
 * sets. Some cookies (Cloudflare's __cf_bm, identity cookie) get attached to
 * the bare domain; others to www. The merge keeps every name=value pair the
 * server has handed us so far.
 */
private fun readAllRoyalRoadCookies(): Map<String, String> {
    val merged = LinkedHashMap<String, String>()
    listOf(RoyalRoadIds.BASE_URL, "https://royalroad.com").forEach { url ->
        val raw = CookieManager.getInstance().getCookie(url) ?: return@forEach
        raw.split(";").forEach { entry ->
            val trimmed = entry.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) merged[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
        }
    }
    return merged
}

data class SessionCookies(val cookies: Map<String, String>)

private class CapturedSession(private val onSession: (SessionCookies) -> Unit) {
    var delivered: Boolean = false
        private set

    fun deliver(cookies: Map<String, String>) {
        if (delivered) return
        delivered = true
        onSession(SessionCookies(cookies))
    }
}
