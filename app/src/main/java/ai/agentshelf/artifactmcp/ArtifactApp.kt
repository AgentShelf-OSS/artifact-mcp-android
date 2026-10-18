package ai.agentshelf.artifactmcp

import ai.agentshelf.artifactmcp.audio.AudioQueue
import ai.agentshelf.artifactmcp.audio.ClipResolver
import ai.agentshelf.artifactmcp.bridge.BridgeHub
import ai.agentshelf.artifactmcp.geo.ServiceLocationControl
import ai.agentshelf.artifactmcp.notify.Notifications
import ai.agentshelf.artifactmcp.notify.SystemArtifactNotifier
import ai.agentshelf.artifactmcp.state.GrantStore
import ai.agentshelf.artifactmcp.state.NoticeStore
import ai.agentshelf.artifactmcp.state.OriginStore
import ai.agentshelf.artifactmcp.state.PlayedStore
import ai.agentshelf.artifactmcp.state.SharedPreferencesStore
import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import java.io.File

class ArtifactApp : Application() {
    lateinit var originStore: OriginStore
        private set
    lateinit var grants: GrantStore
        private set
    lateinit var played: PlayedStore
        private set
    private lateinit var notices: NoticeStore
    lateinit var hub: BridgeHub
        private set

    /** Created lazily: ExoPlayer and the clip cache are not needed until something plays. */
    val audio: AudioQueue by lazy {
        val resolver = ClipResolver(
            dir = File(cacheDir, "clips"),
            origin = { originStore.origin },
            cookies = { url -> CookieManager.getInstance().getCookie(url) },
            userAgent = { userAgent },
        )
        AudioQueue(this, resolver) { artifactId, id, state -> hub.onAudioState(artifactId, id, state) }
    }

    private val userAgent: String? by lazy { runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull() }

    override fun onCreate() {
        super.onCreate()
        val kv = SharedPreferencesStore(getSharedPreferences("artifact", Context.MODE_PRIVATE))
        originStore = OriginStore(kv)
        grants = GrantStore(kv)
        played = PlayedStore(kv)
        notices = NoticeStore(kv)
        Notifications.createChannels(this)
        hub = BridgeHub(
            appVersion = BuildConfig.VERSION_NAME,
            clock = System::currentTimeMillis,
            origin = { originStore.origin },
            grants = grants,
            played = played,
            notices = notices,
            location = ServiceLocationControl(this),
            audio = LazyAudio(),
            notifier = SystemArtifactNotifier(this) { originStore.origin },
        )
    }

    /** Forwards to [audio] without creating the player for calls that do not need it. */
    private inner class LazyAudio : ai.agentshelf.artifactmcp.bridge.AudioControl {
        private var used = false
        override fun enqueue(artifactId: String, label: String, item: ai.agentshelf.artifactmcp.bridge.Protocol.AudioItem) {
            used = true
            audio.enqueue(artifactId, label, item)
        }
        override fun stop(artifactId: String) {
            if (used) audio.stop(artifactId)
        }
        override fun prefetch(artifactId: String, items: List<ai.agentshelf.artifactmcp.bridge.Protocol.AudioItem>) {
            used = true
            audio.prefetch(artifactId, items)
        }
        override fun stopAll() {
            if (used) audio.stopAll()
        }
    }

    /**
     * Sign out and clear data: cookies (the Access session), web storage and caches, cached
     * clips, consent grants, played state and notices. The configured origin is kept unless
     * [forgetOrigin] is set.
     */
    fun clearAllData(forgetOrigin: Boolean, done: () -> Unit) {
        hub.reset()
        audio.clearCache()
        grants.clear()
        played.clear()
        notices.clear()
        if (forgetOrigin) originStore.origin = null
        WebStorage.getInstance().deleteAllData()
        File(cacheDir, "WebView").deleteRecursively()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            done()
        }
    }
}
