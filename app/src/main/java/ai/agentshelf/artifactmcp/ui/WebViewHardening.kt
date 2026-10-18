package ai.agentshelf.artifactmcp.ui

import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Settings shared by every WebView the app creates (viewer, popup probe, Listen reader). */
object WebViewHardening {
    fun harden(settings: WebSettings) {
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
}
