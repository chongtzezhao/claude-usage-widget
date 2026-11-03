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
import com.usage.claudewidget.data.CookieHarvester
import com.usage.claudewidget.data.Provider
import com.usage.claudewidget.data.Storage
import com.usage.claudewidget.widget.CodexWidget
import com.usage.claudewidget.widget.UsageWidget
import com.usage.claudewidget.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val handler = Handler(Looper.getMainLooper())
    private val captureSession = object : Runnable {
        override fun run() {
            tryCapture()
            if (!captured) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "${provider.displayName} sign in"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

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
        webView.loadUrl(provider.loginUrl)
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

        // Discover the first snapshot, then schedule periodic refresh.
        CoroutineScope(Dispatchers.Main).launch {
            RefreshScheduler.ensurePeriodic(this@LoginActivity, provider)
            RefreshScheduler.refreshNow(this@LoginActivity, provider)
            updateWidgets(this@LoginActivity)
            Toast.makeText(this@LoginActivity, "Signed in", Toast.LENGTH_SHORT).show()
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
        handler.removeCallbacks(captureSession)
        popupWebView?.destroy()
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PROVIDER = "provider"

        fun start(context: Context, provider: Provider): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_PROVIDER, provider.name)
    }
}
