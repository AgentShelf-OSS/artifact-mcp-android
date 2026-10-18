package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.BuildConfig
import ai.agentshelf.artifactmcp.bridge.BridgeHub
import ai.agentshelf.artifactmcp.bridge.ShellConnection
import ai.agentshelf.artifactmcp.config.OriginPolicy
import ai.agentshelf.artifactmcp.ui.MainActivity
import ai.agentshelf.artifactmcp.ui.WebViewHardening
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * A hidden viewer page that the media service opens when an artifact is picked in the car
 * library while the phone's viewer is not on screen (ADR-0019).
 *
 * It uses the viewer's hardened settings and the same `ArtifactNative` channel (same allowed
 * origin), but it may only load `<origin>/<id>` and `<origin>/<id>?listen=autoplay`: every other
 * top-level navigation (including the Access login page, popups and external links) is blocked.
 * It is the only WebView with `mediaPlaybackRequiresUserGesture = false`, because the shell starts
 * Listen without a tap. Bridge messages are handled in reader mode (see [BridgeHub.onShellMessage]).
 * Must be created and used on the main thread.
 */
class ReaderWebView(
    context: Context,
    private val origin: String,
    private val hub: BridgeHub,
    private val events: Events,
) {
    interface Events {
        /** A navigation left the allowed URLs; [signIn] when it went to the Access login. */
        fun onReaderBlocked(reader: ReaderWebView, signIn: Boolean)
        fun onReaderGone(reader: ReaderWebView)
    }

    val webView: WebView = WebView(context.applicationContext)
    var destroyed = false
        private set

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled") // The viewer shell is a JavaScript application.
    private fun configure() {
        val settings = webView.settings
        WebViewHardening.harden(settings)
        settings.setGeolocationEnabled(false)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // Listen starts on `?listen=autoplay` without a user gesture in this WebView only.
        settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { _, _, _, _, _ -> }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, MainActivity.BRIDGE_OBJECT, setOf(origin)) { source, message, sourceOrigin, isMainFrame, reply ->
                if (destroyed || !isMainFrame) return@addWebMessageListener
                if (OriginPolicy.originOf(sourceOrigin.toString()) != origin) return@addWebMessageListener
                if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                val page = OriginPolicy.viewerArtifactId(source.url, origin)
                hub.onShellMessage(data, object : ShellConnection {
                    override val pageArtifactId: String? = page
                    override val host: Any = webView

                    @SuppressLint("RequiresFeature")
                    override fun post(message: String) {
                        if (!destroyed) reply.postMessage(message)
                    }
                }, reader = true)
            }
        }
    }

    /** Load a reader URL. Returns false (and loads nothing) for any other URL. */
    fun load(url: String): Boolean {
        if (destroyed || !OriginPolicy.isReaderUrl(url, origin)) return false
        webView.loadUrl(url)
        return true
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.destroy()
    }

    private fun allowed(url: String): Boolean = OriginPolicy.isReaderUrl(url, origin)

    // onRenderProcessGone is implemented below; the androidx.webkit check misreports Kotlin overrides.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Sub-frames stay under the page's sandbox and CSP, as in the viewer.
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (allowed(url)) return false
            events.onReaderBlocked(this@ReaderWebView, signIn = OriginPolicy.isAccessLoginHost(url))
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url != "about:blank" && !allowed(url)) {
                view.stopLoading()
                events.onReaderBlocked(this@ReaderWebView, signIn = OriginPolicy.isAccessLoginHost(url))
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (OriginPolicy.isSameOrigin(url, origin)) CookieManager.getInstance().flush()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (!destroyed) {
                destroyed = true
                view.destroy()
                events.onReaderGone(this@ReaderWebView)
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

        override fun onReceivedTitle(view: WebView, title: String?) {
            val page = OriginPolicy.viewerArtifactId(view.url, origin)
            if (page != null && !title.isNullOrBlank()) hub.setTitle(page, title)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }
}
