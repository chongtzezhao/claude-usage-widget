package com.usage.claudewidget.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import com.usage.claudewidget.data.CodexUsageRepository
import com.usage.claudewidget.data.CookieHarvester
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.Provider
import com.usage.claudewidget.data.Storage
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.widget.CodexWidget
import com.usage.claudewidget.widget.UsageWidget
import com.usage.claudewidget.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One-time interactive login. Loads the provider's site in a WebView; once its
 * session cookie appears, harvests cookies + the WebView's User-Agent, then finishes.
 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var content: FrameLayout
    private var popupWebView: WebView? = null
    private val provider by lazy { Provider.fromName(intent.getStringExtra(EXTRA_PROVIDER)) }
    private val storage by lazy { Storage.get(this, provider) }
    private var captured = false
    private var attempts = 0
    private var jarReady = false
    private var validationJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val captureSession = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !jarReady) return
            tryCapture()
            if (!captured) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "${provider.displayName} sign in"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // A sign-in is a fresh start: drop stale credentials and the previous
        // account's org id so a dead cookie cannot validate against them. The
        // calibration ratio is plan-level, so it survives a re-login.
        // On a configuration change keep the in-progress jar instead.
        val firstCreate = savedInstanceState == null
        if (firstCreate) storage.signOut()

        content = FrameLayout(this)
        webView = createWebView()
        // Persist the exact UA so headless fetches look like the WebView that earned
        // cf_clearance. Codex also drops WebView's "; wv" marker, which OpenAI's edge
        // scores badly, while keeping the WebView's real Chrome version.
        if (provider.cleanWebViewUserAgent) {
            webView.settings.userAgentString = webView.settings.userAgentString
                .replace("; wv", "")
                .replace(" Version/4.0", "")
        }
        storage.userAgent = webView.settings.userAgentString
        content.addView(webView)
        setContentView(content)
        // Load only once the stale jar is gone; a leftover session cookie would
        // otherwise satisfy the capture check and look like a successful sign-in.
        if (!firstCreate) {
            jarReady = true
            webView.loadUrl(provider.loginUrl)
            handler.postDelayed(captureSession, 500)
            return
        }
        CookieHarvester.clear(provider) {
            handler.post {
                if (isFinishing || isDestroyed) return@post
                jarReady = true
                webView.loadUrl(provider.loginUrl)
                handler.postDelayed(captureSession, 500)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        val cm = CookieManager.getInstance()
        cm.setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message,
            ): Boolean {
                if (!isUserGesture || popupWebView != null) return false
                val popup = createWebView()
                popupWebView = popup
                content.addView(popup)
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                closePopup(window)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                tryCapture()
            }
        }
    }

    private fun closePopup(window: WebView) {
        if (window !== popupWebView) return
        content.removeView(window)
        popupWebView = null
        window.stopLoading()
        window.destroy()
        tryCapture()
    }

    override fun onResume() {
        super.onResume()
        // Google can finish through postMessage/fetch without another page load.
        handler.post(captureSession)
    }

    override fun onPause() {
        handler.removeCallbacks(captureSession)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        popupWebView?.let { closePopup(it); return }
        super.onBackPressed()
    }

    private fun tryCapture() {
        if (captured) return
        val cookies = CookieManager.getInstance().getCookie(provider.base) ?: return
        if (!CookieHarvester.hasProviderSession(cookies, provider)) return

        captured = true
        CookieManager.getInstance().flush()
        if (!CookieHarvester.harvest(storage)) {
            captured = false
            return
        }

        // Validate the harvested session before finishing; a stale cookie that the
        // server rejects must not masquerade as a completed sign-in.
        validationJob = CoroutineScope(Dispatchers.Main).launch {
            val result = when (provider) {
                Provider.CLAUDE -> UsageRepository(this@LoginActivity).refresh()
                Provider.CODEX -> CodexUsageRepository(this@LoginActivity).refresh()
            }
            if (isFinishing || isDestroyed) return@launch

            if (result is FetchResult.NeedsLogin) {
                storage.clearSessionCookie()
                attempts++
                if (attempts >= MAX_ATTEMPTS) {
                    Toast.makeText(
                        this@LoginActivity,
                        "Sign-in failed. Go back and try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                    return@launch
                }
                // Drop the rejected jar so the retry starts clean, then poll again
                // only after the asynchronous removal has landed.
                handler.removeCallbacks(captureSession)
                CookieHarvester.clear(provider) {
                    handler.post {
                        if (isFinishing || isDestroyed) return@post
                        captured = false
                        Toast.makeText(
                            this@LoginActivity,
                            "Sign-in was not accepted. Please try again.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        webView.loadUrl(provider.loginUrl)
                        handler.postDelayed(captureSession, 500)
                    }
                }
                return@launch
            }

            RefreshScheduler.ensurePeriodic(this@LoginActivity, provider)
            updateWidgets(this@LoginActivity)
            val verified = result is FetchResult.Success || result is FetchResult.CodexSuccess
            Toast.makeText(
                this@LoginActivity,
                if (verified) "Signed in" else "Signed in; will verify on refresh",
                Toast.LENGTH_SHORT,
            ).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private suspend fun updateWidgets(context: Context) {
        when (provider) {
            Provider.CLAUDE -> UsageWidget.updateAll(context)
            Provider.CODEX -> CodexWidget.updateAll(context)
        }
    }

    override fun onDestroy() {
        validationJob?.cancel()
        handler.removeCallbacks(captureSession)
        popupWebView?.destroy()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROVIDER = "provider"
        private const val MAX_ATTEMPTS = 3

        fun start(context: Context, provider: Provider): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_PROVIDER, provider.name)
    }
}
