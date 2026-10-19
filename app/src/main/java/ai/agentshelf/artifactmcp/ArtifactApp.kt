package ai.agentshelf.artifactmcp

import ai.agentshelf.artifactmcp.audio.AudioQueue
import ai.agentshelf.artifactmcp.audio.ClipResolver
import ai.agentshelf.artifactmcp.audio.HttpExecutor
import ai.agentshelf.artifactmcp.bridge.BridgeHub
import ai.agentshelf.artifactmcp.car.CoverDesign
import ai.agentshelf.artifactmcp.car.CoverUris
import ai.agentshelf.artifactmcp.car.RecentItem
import ai.agentshelf.artifactmcp.car.RecentStore
import ai.agentshelf.artifactmcp.library.LibraryView
import ai.agentshelf.artifactmcp.listen.MirrorState
import ai.agentshelf.artifactmcp.library.LibraryFetcher
import ai.agentshelf.artifactmcp.library.LibraryRepository
import ai.agentshelf.artifactmcp.listen.ListenController
import ai.agentshelf.artifactmcp.listen.ListenReader
import ai.agentshelf.artifactmcp.listen.ListenTimers
import ai.agentshelf.artifactmcp.listen.MirrorPlayer
import ai.agentshelf.artifactmcp.listen.PlaybackArbiter
import ai.agentshelf.artifactmcp.listen.ReaderWebView
import ai.agentshelf.artifactmcp.geo.ServiceLocationControl
import ai.agentshelf.artifactmcp.notify.Notifications
import ai.agentshelf.artifactmcp.notify.SystemArtifactNotifier
import ai.agentshelf.artifactmcp.state.GrantStore
import ai.agentshelf.artifactmcp.state.NoticeStore
import ai.agentshelf.artifactmcp.state.OriginStore
import ai.agentshelf.artifactmcp.state.PlayedStore
import ai.agentshelf.artifactmcp.state.SharedPreferencesStore
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import java.io.File

class ArtifactApp : Application() {
    private companion object {
        const val MAX_LIBRARY_BYTES = 4L * 1024 * 1024
    }

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
    private var audioCreated = false

    val audio: AudioQueue by lazy {
        audioCreated = true
        val resolver = ClipResolver(
            dir = File(cacheDir, "clips"),
            origin = { originStore.origin },
            cookies = { url -> CookieManager.getInstance().getCookie(url) },
            userAgent = { userAgent },
        )
        AudioQueue(this, resolver) { artifactId, id, state -> hub.onAudioState(artifactId, id, state) }.also { queue ->
            queue.player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) arbiter.nativeStarted()
                }
            })
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Listen mirror and car library (ADR-0019). */
    lateinit var listen: ListenController
        private set
    lateinit var arbiter: PlaybackArbiter
        private set
    val mirrorPlayer: MirrorPlayer by lazy {
        MirrorPlayer(Looper.getMainLooper()) { action -> listen.command(action) }.also { player ->
            player.artwork = { mirror -> mirrorPresentation(mirror).first }
            player.artifactTitle = { mirror -> mirrorPresentation(mirror).second }
            listen.player = player
        }
    }

    private var presentationKey: String? = null
    private var presentation: Pair<Uri?, String?> = null to null

    /**
     * Cover and library title of the artifact Listen plays (memoized: the player asks on every
     * state read).
     */
    private fun mirrorPresentation(mirror: MirrorState): Pair<Uri?, String?> {
        val key = mirror.artifactId + "\u0000" + mirror.album
        if (key != presentationKey) {
            presentationKey = key
            val title = (library.known() as? LibraryView.Ready)?.library?.find(mirror.artifactId)?.title
                ?: recents.forOrigin(originStore.origin).firstOrNull { it.artifactId == mirror.artifactId }?.title
            presentation = artifactCover(mirror.artifactId, title ?: mirror.album.ifBlank { mirror.title }) to title
        }
        return presentation
    }

    /** Cover URI of an artifact, from the library (title, category, organization color). */
    fun artifactCover(artifactId: String, fallbackTitle: String): Uri {
        val known = (library.known() as? LibraryView.Ready)?.library
        val artifact = known?.find(artifactId)
        val org = known?.orgOf(artifactId)
        val recent = if (artifact == null) recents.forOrigin(originStore.origin).firstOrNull { it.artifactId == artifactId } else null
        val title = artifact?.title ?: recent?.title ?: fallbackTitle.ifBlank { artifactId }
        val category = artifact?.category ?: recent?.category.orEmpty()
        val color = CoverDesign.orgColor(org?.org ?: recent?.org.orEmpty(), org?.color ?: recent?.color)
        return CoverUris.uri(packageName, CoverDesign.Spec.Artifact(title, category, color))
    }

    /** Remember what Listen plays for the car's Continue tab. */
    private fun recordRecent() {
        val mirror = listen.mirror ?: return
        if (mirror.phase != MirrorState.Phase.PLAYING) return
        val origin = originStore.origin ?: return
        val known = (library.known() as? LibraryView.Ready)?.library
        val artifact = known?.find(mirror.artifactId)
        val org = known?.orgOf(mirror.artifactId)
        val previous = recents.forOrigin(origin).firstOrNull { it.artifactId == mirror.artifactId }
        recents.record(
            RecentItem(
                origin = origin,
                artifactId = mirror.artifactId,
                title = artifact?.title ?: previous?.title ?: mirror.album.ifBlank { mirror.title },
                org = org?.org ?: previous?.org.orEmpty(),
                category = artifact?.category ?: previous?.category.orEmpty(),
                color = org?.color ?: previous?.color,
                at = System.currentTimeMillis(),
            ),
        )
    }
    lateinit var library: LibraryRepository
        private set

    /** Recently played artifacts, for the car's Continue tab. */
    lateinit var recents: RecentStore
        private set
    private val activeSourceListeners = mutableListOf<(PlaybackArbiter.Source) -> Unit>()
    private var sessionController: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    fun addActiveSourceListener(listener: (PlaybackArbiter.Source) -> Unit) {
        activeSourceListeners += listener
    }

    fun removeActiveSourceListener(listener: (PlaybackArbiter.Source) -> Unit) {
        activeSourceListeners -= listener
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
            media = { message, connection -> listen.onMediaSession(message, connection) },
        )
        listen = ListenController(
            origin = { originStore.origin },
            timers = HandlerTimers(main),
            newReader = ::newReader,
            bindSession = ::bindSession,
        )
        arbiter = PlaybackArbiter(
            pauseNative = { if (audioCreated) audio.player.pause() },
            pauseMirror = { listen.pauseForNative() },
            onActiveChanged = { source -> activeSourceListeners.toList().forEach { it(source) } },
        )
        listen.arbiter = arbiter
        recents = RecentStore(kv)
        listen.addListener(::recordRecent)
        library = LibraryRepository(
            cacheFile = File(filesDir, "library.json"),
            origin = { originStore.origin },
            cookies = { url -> CookieManager.getInstance().getCookie(url) },
            userAgent = { userAgent },
            fetcher = LibraryFetcher { spec ->
                var body: String? = null
                val response = HttpExecutor(connectTimeoutMs = 4_000, readTimeoutMs = 4_000)
                    .execute(spec, MAX_LIBRARY_BYTES) { body = it.readBytes().toString(Charsets.UTF_8) }
                response.status to body
            },
        )
    }


    private fun newReader(): ListenReader? {
        val currentOrigin = originStore.origin ?: return null
        val page = runCatching {
            ReaderWebView(this, currentOrigin, hub, object : ReaderWebView.Events {
                override fun onReaderBlocked(reader: ReaderWebView, signIn: Boolean) = listen.onReaderBlocked(signIn)
                override fun onReaderGone(reader: ReaderWebView) {
                    listen.reader?.let { if (it.host === reader.webView) listen.onReaderGone(it) }
                }
            })
        }.getOrNull() ?: return null
        // The viewer may have paused the process-wide WebView timers in the background.
        page.webView.resumeTimers()
        return object : ListenReader {
            override val host: Any = page.webView
            override fun load(url: String) = page.load(url)
            override fun destroy() = page.destroy()
        }
    }

    /** Bind the media session service so the session exists for Android Auto and the notification. */
    fun bindSession() {
        if (sessionController != null) return
        mirrorPlayer // created before the service reads it
        val token = SessionToken(this, ComponentName(this, ai.agentshelf.artifactmcp.audio.PlaybackService::class.java))
        sessionController = MediaController.Builder(this, token).buildAsync()
    }

    private class HandlerTimers(private val handler: Handler) : ListenTimers {
        private val tasks = HashMap<String, Runnable>()
        override fun schedule(key: String, delayMs: Long, block: () -> Unit) {
            cancel(key)
            val task = Runnable {
                tasks.remove(key)
                block()
            }
            tasks[key] = task
            handler.postDelayed(task, delayMs)
        }
        override fun cancel(key: String) {
            tasks.remove(key)?.let(handler::removeCallbacks)
        }
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
        listen.reset()
        library.clear()
        recents.clear()
        File(cacheDir, "covers").deleteRecursively()
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
