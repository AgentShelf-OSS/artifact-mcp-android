package ai.agentshelf.artifactmcp.ui

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.BuildConfig
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.bridge.ConsentGate
import ai.agentshelf.artifactmcp.bridge.ShellConnection
import ai.agentshelf.artifactmcp.config.OriginPolicy
import ai.agentshelf.artifactmcp.geo.LocationService
import ai.agentshelf.artifactmcp.notify.Notifications
import ai.agentshelf.artifactmcp.state.Capability
import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The viewer: one WebView on the configured origin, plus the `ArtifactNative` host channel.
 *
 * The channel is registered with `WebViewCompat.addWebMessageListener` and an allowed-origin
 * rule of exactly the configured origin, so only top-level pages of that origin get the object.
 * The sandboxed artifact iframe has an opaque origin and never receives it.
 * `addJavascriptInterface` is not used.
 */
class MainActivity : AppCompatActivity(), ConsentGate {
    companion object {
        const val BRIDGE_OBJECT = "ArtifactNative"
    }

    private lateinit var app: ArtifactApp
    private lateinit var container: FrameLayout
    private var webView: WebView? = null
    private var origin: String = ""

    /** True after the viewer left the origin for the Access login page, until it returns. */
    private var loginInProgress = false

    private data class ConsentRequest(
        val artifactId: String,
        val label: String,
        val capability: Capability,
        val onResult: (Boolean) -> Unit,
    )

    private val consentQueue = ArrayDeque<ConsentRequest>()
    private var consentActive = false
    private var permissionCallback: (() -> Unit)? = null
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as ArtifactApp
        val configured = app.originStore.origin
        if (configured == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        origin = configured
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionCallback?.invoke()
            permissionCallback = null
        }
        container = FrameLayout(this)
        setContentView(container)
        // Edge-to-edge is enforced on recent Android: keep the viewer clear of the status bar,
        // display cutout, navigation bar and keyboard, and tint the status-bar strip to match.
        container.setBackgroundColor(ContextCompat.getColor(this, R.color.viewer_chrome))
        WindowCompat.getInsetsController(window, container).isAppearanceLightStatusBars = !isNightMode()
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        supportActionBar?.title = getString(R.string.app_name)
        createWebView()
        app.hub.consent = this
        onBackPressedDispatcher.addCallback(this) {
            val view = webView
            if (view != null && view.canGoBack()) {
                view.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
        val target = intent?.dataString?.takeIf { OriginPolicy.isViewerTarget(it, origin) }
        if (savedInstanceState == null || webView?.url == null) {
            webView?.loadUrl(target ?: "$origin/")
        }
    }

    @SuppressLint("SetJavaScriptEnabled") // The viewer shell is a JavaScript application.
    private fun createWebView() {
        val view = WebView(this)
        view.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        harden(view.settings)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.setSupportMultipleWindows(true)
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.webViewClient = ViewerClient()
        view.webChromeClient = ViewerChrome()
        view.setDownloadListener { _, _, _, _, _ -> Toast.makeText(this, R.string.downloads_unsupported, Toast.LENGTH_SHORT).show() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Without this WebView feature the shell gets no reply and behaves as in a browser.
            WebViewCompat.addWebMessageListener(view, BRIDGE_OBJECT, setOf(origin)) { source, message, sourceOrigin, isMainFrame, replyProxy ->
                onBridgeMessage(source, message, sourceOrigin, isMainFrame, replyProxy)
            }
        }
        container.addView(view)
        webView = view
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun harden(settings: WebSettings) {
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Geolocation is answered per origin in onGeolocationPermissionsShowPrompt.
        settings.setGeolocationEnabled(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
    }

    private fun onBridgeMessage(
        source: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        // Defence in depth on top of the allowed-origin rule.
        if (!isMainFrame) return
        if (OriginPolicy.originOf(sourceOrigin.toString()) != origin) return
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val data = message.data ?: return
        val page = OriginPolicy.viewerArtifactId(source.url, origin)
        app.hub.onShellMessage(data, object : ShellConnection {
            override val pageArtifactId: String? = page

            // Only reachable from a WEB_MESSAGE_LISTENER callback, so the feature is present.
            @SuppressLint("RequiresFeature")
            override fun post(message: String) = replyProxy.postMessage(message)
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.dataString?.takeIf { OriginPolicy.isViewerTarget(it, origin) } ?: return
        webView?.loadUrl(target)
    }

    override fun onResume() {
        super.onResume()
        val configured = app.originStore.origin
        if (configured == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        if (configured != origin) {
            // The host channel's allowed origin is fixed per WebView: rebuild for the new origin.
            recreate()
            return
        }
        app.hub.consent = this
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (app.hub.consent === this) app.hub.consent = null
        consentQueue.forEach { it.onResult(false) }
        consentQueue.clear()
        webView?.let {
            container.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, Menu.NONE, R.string.menu_gallery)
        menu.add(Menu.NONE, 2, Menu.NONE, R.string.menu_reload)
        menu.add(Menu.NONE, 3, Menu.NONE, R.string.settings_title)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> {
            webView?.loadUrl("$origin/")
            true
        }
        2 -> {
            webView?.reload()
            true
        }
        3 -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---- navigation ----------------------------------------------------------------------

    private fun handleNavigation(url: String): Boolean = when (OriginPolicy.classifyNavigation(url, origin, loginInProgress)) {
        OriginPolicy.Navigation.IN_APP -> {
            if (OriginPolicy.isAccessLoginHost(url)) loginInProgress = true
            false
        }
        OriginPolicy.Navigation.BLOCKED_GOOGLE_SIGN_IN -> {
            showGoogleBlocked()
            true
        }
        OriginPolicy.Navigation.EXTERNAL -> {
            openExternal(url)
            true
        }
        OriginPolicy.Navigation.DENY -> true
    }

    private fun showGoogleBlocked() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.google_blocked_title)
            .setMessage(R.string.google_blocked_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openExternal(url: String) {
        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme == "https" || scheme == "http") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Prefer a native app that owns the link (for example Google Maps directions).
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, uri)
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER),
                    )
                    return
                } catch (_: ActivityNotFoundException) {
                    // No app claims it: open a Custom Tab below.
                }
            }
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
                return
            } catch (_: ActivityNotFoundException) {
                // No Custom Tabs browser: fall through to a plain view intent.
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    // onRenderProcessGone is implemented below; the androidx.webkit check misreports Kotlin overrides.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class ViewerClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Sub-frame navigation stays under the page's own sandbox and CSP.
            if (!request.isForMainFrame) return false
            return handleNavigation(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            app.hub.onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (OriginPolicy.isSameOrigin(url, origin)) {
                loginInProgress = false
                CookieManager.getInstance().flush()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The renderer crashed or was killed: drop this WebView and start a fresh one.
            app.hub.onPageStarted()
            container.removeView(view)
            view.destroy()
            webView = null
            createWebView()
            webView?.loadUrl("$origin/")
            return true
        }
    }

    private inner class ViewerChrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            // target=_blank / window.open: capture the URL with a throwaway WebView that never
            // loads anything, then route it like any other navigation.
            val probe = WebView(this@MainActivity)
            harden(probe.settings)
            // onRenderProcessGone is implemented below (lint misreports Kotlin object overrides).
            probe.webViewClient = @SuppressLint("MissingOnRenderProcessGone") object : WebViewClient() {
                private var handled = false
                private fun route(url: String) {
                    if (handled || url == "about:blank") return
                    handled = true
                    if (OriginPolicy.isSameOrigin(url, origin)) webView?.loadUrl(url) else if (
                        OriginPolicy.classifyNavigation(url, origin, false) == OriginPolicy.Navigation.EXTERNAL
                    ) openExternal(url)
                    probe.post { probe.destroy() }
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    route(request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    v.stopLoading()
                    route(url)
                }

                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    v.destroy()
                    return true
                }
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val page = OriginPolicy.viewerArtifactId(view.url, origin)
            if (page != null && !title.isNullOrBlank()) app.hub.setTitle(page, title)
            supportActionBar?.title = title?.takeIf { it.isNotBlank() && OriginPolicy.isSameOrigin(view.url ?: "", origin) }
                ?: getString(R.string.app_name)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            // Only the configured server's viewer shell may ask. The sandboxed artifact frame has an
            // opaque origin and no geolocation of its own; the shell still asks the per-artifact
            // question (artifact-mcp ADR-0016) before any position reaches an artifact.
            val server = this@MainActivity.origin
            if (origin == null || !OriginPolicy.isSameOrigin(origin, server)) {
                callback.invoke(origin, false, false)
                return
            }
            if (LocationService.hasLocationPermission(this@MainActivity)) {
                callback.invoke(origin, true, false)
                return
            }
            val previous = permissionCallback
            permissionCallback = {
                previous?.invoke()
                callback.invoke(origin, LocationService.hasLocationPermission(this@MainActivity), false)
            }
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    // ---- consent ---------------------------------------------------------------------------

    override fun request(artifactId: String, label: String, capability: Capability, onResult: (Boolean) -> Unit) {
        consentQueue.addLast(ConsentRequest(artifactId, label, capability, onResult))
        processConsent()
    }

    private fun processConsent() {
        if (consentActive) return
        val next = consentQueue.firstOrNull() ?: return
        if (isFinishing || isDestroyed) {
            completeConsent(next, false)
            return
        }
        consentActive = true
        if (app.grants.isGranted(next.artifactId, next.capability)) {
            ensurePermissions(next)
            return
        }
        val (title, message) = when (next.capability) {
            Capability.LOCATION -> getString(R.string.consent_location_title, next.label) to
                getString(R.string.consent_location_message, next.label)
            Capability.NOTIFY -> getString(R.string.consent_notify_title, next.label) to
                getString(R.string.consent_notify_message, next.label)
        }
        var answered = false
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.consent_allow) { _, _ ->
                answered = true
                app.grants.grant(next.artifactId, next.capability, next.label, System.currentTimeMillis())
                ensurePermissions(next)
            }
            .setNegativeButton(R.string.consent_deny) { _, _ ->
                answered = true
                completeConsent(next, false)
            }
            .setOnDismissListener { if (!answered) completeConsent(next, false) }
            .show()
    }

    private fun ensurePermissions(request: ConsentRequest) {
        val needed = mutableListOf<String>()
        val wantsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when (request.capability) {
            Capability.LOCATION -> {
                if (!LocationService.hasLocationPermission(this)) {
                    needed += Manifest.permission.ACCESS_FINE_LOCATION
                    needed += Manifest.permission.ACCESS_COARSE_LOCATION
                }
                // The persistent location notification needs this to be visible on Android 13+.
                if (wantsNotifications) needed += Manifest.permission.POST_NOTIFICATIONS
            }
            Capability.NOTIFY -> if (wantsNotifications) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val satisfied = {
            when (request.capability) {
                Capability.LOCATION -> LocationService.hasLocationPermission(this)
                Capability.NOTIFY -> Notifications.canPost(this)
            }
        }
        if (needed.isEmpty()) {
            completeConsent(request, satisfied())
            return
        }
        permissionCallback = { completeConsent(request, satisfied()) }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun completeConsent(request: ConsentRequest, allowed: Boolean) {
        // Answer every queued request for the same artifact and capability at once.
        val same = consentQueue.filter { it.artifactId == request.artifactId && it.capability == request.capability }
        consentQueue.removeAll(same.toSet())
        consentActive = false
        (same.ifEmpty { listOf(request) }).forEach { it.onResult(allowed) }
        processConsent()
    }
}
