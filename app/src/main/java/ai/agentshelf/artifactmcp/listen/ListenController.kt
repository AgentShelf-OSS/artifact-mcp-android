package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.bridge.MediaSessionSink
import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.bridge.Protocol.Inbound
import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import ai.agentshelf.artifactmcp.bridge.Protocol.MediaState
import ai.agentshelf.artifactmcp.bridge.ShellConnection
import ai.agentshelf.artifactmcp.config.OriginPolicy

/** The phone's visible viewer, registered while it is on screen. */
interface ListenViewer {
    /** The viewer WebView; the same object as [ShellConnection.host] of its messages. */
    val host: Any
    fun openListen(url: String)
}

/** A hidden reader page; [ReaderWebView] in the app, a fake in tests. */
interface ListenReader {
    val host: Any
    fun load(url: String): Boolean
    fun destroy()
}

/** Main-thread timers (a Handler in the app, a manual clock in tests). */
interface ListenTimers {
    fun schedule(key: String, delayMs: Long, block: () -> Unit)
    fun cancel(key: String)
}

/**
 * Mirrors the viewer's Listen player into the native media session (ADR-0019) and opens Listen
 * for artifacts picked in the car library.
 *
 * One page at a time owns the mirror: the last page that reports `playing` wins and the
 * previous one is asked to pause. A library pick opens `<origin>/<id>?listen=autoplay` in the
 * visible viewer when it is on screen, otherwise in one hidden reader. The reader is destroyed
 * a grace period after Listen stops (or after a long pause), and at once if it is replaced.
 * All calls happen on the main thread.
 */
class ListenController(
    private val origin: () -> String?,
    private val timers: ListenTimers,
    private val newReader: () -> ListenReader?,
    private val bindSession: () -> Unit,
) : MediaSessionSink {
    companion object {
        const val LOADING_TIMEOUT_MS = 45_000L
        const val READER_STOP_GRACE_MS = 30_000L
        const val READER_IDLE_MS = 15 * 60_000L
        private const val T_LOADING = "loading"
        private const val T_READER = "reader"
        const val MESSAGE_SIGN_IN = "Open the app on your phone to sign in"
        const val MESSAGE_NOT_STARTED = "Listen did not start for this artifact"
    }

    /** Set by the app: forwards commands and state to the media session's player. */
    var player: MirrorPlayer? = null
        set(value) {
            field = value
            value?.update(mirror)
        }

    lateinit var arbiter: PlaybackArbiter

    var viewer: ListenViewer? = null

    var mirror: MirrorState? = null
        private set

    /** Page that owns the mirror ([ShellConnection.host]), and its last connection. */
    private var ownerHost: Any? = null
    private var connection: ShellConnection? = null
    var reader: ListenReader? = null
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    val isPlaying: Boolean get() = mirror?.phase == MirrorState.Phase.PLAYING || mirror?.phase == MirrorState.Phase.LOADING

    /** True while Listen plays (or is starting) in the page shown by [host]. */
    fun isPlayingOn(host: Any?): Boolean = host != null && host === ownerHost && isPlaying

    // ---- shell -> app ------------------------------------------------------------------------

    override fun onMediaSession(message: Inbound.MediaSession, connection: ShellConnection) {
        val host = connection.host ?: connection
        val owned = host === ownerHost
        val current = mirror
        when (message.state) {
            MediaState.NONE -> {
                if (!owned) return
                // A library pick reports `none` before autoplay starts; keep waiting for it.
                if (current?.phase == MirrorState.Phase.LOADING) return
                end()
                if (host === reader?.host) scheduleReaderDestroy(READER_STOP_GRACE_MS)
            }
            MediaState.PLAYING -> {
                if (!owned) {
                    // Last active wins: the page that owned the mirror pauses.
                    if (current?.phase == MirrorState.Phase.PLAYING) send(MediaAction.PAUSE)
                    if (reader != null && reader?.host !== host) destroyReader()
                }
                adopt(host, connection, message, MirrorState.Phase.PLAYING)
                timers.cancel(T_LOADING)
                if (host === reader?.host) timers.cancel(T_READER)
                arbiter.mirrorStarted()
                bindSession()
            }
            MediaState.PAUSED -> {
                if (!owned && current != null && current.phase != MirrorState.Phase.FAILED) return
                if (owned && current?.phase == MirrorState.Phase.LOADING) {
                    // Autoplay has not started yet: keep the loading state, remember the session.
                    this.connection = connection
                    return
                }
                adopt(host, connection, message, MirrorState.Phase.PAUSED)
                if (host === reader?.host) scheduleReaderDestroy(READER_IDLE_MS)
            }
        }
    }

    private fun adopt(host: Any, connection: ShellConnection, message: Inbound.MediaSession, phase: MirrorState.Phase) {
        ownerHost = host
        this.connection = connection
        publish(
            MirrorState(
                artifactId = message.artifactId,
                phase = phase,
                title = message.title,
                artist = message.artist,
                album = message.album,
                actions = message.actions,
            ),
        )
    }

    /** The page in [host] navigated away, was destroyed or its renderer died. */
    fun onHostGone(host: Any) {
        if (host !== ownerHost) return
        // A viewer that is navigating to the picked artifact reports the old page first.
        if (mirror?.phase == MirrorState.Phase.LOADING && connection == null) return
        end()
    }

    // ---- car library -------------------------------------------------------------------------

    /**
     * Open Listen for [artifactId] (picked in the car library). Returns false when the id or
     * origin is invalid or no page could be opened.
     */
    fun playFromLibrary(artifactId: String, title: String): Boolean {
        val currentOrigin = origin() ?: return false
        if (!Protocol.isValidArtifactId(artifactId)) return false
        val url = OriginPolicy.listenUrl(currentOrigin, artifactId)
        if (mirror?.phase == MirrorState.Phase.PLAYING) send(MediaAction.PAUSE)
        val visible = viewer
        val host: Any
        if (visible != null) {
            destroyReader()
            visible.openListen(url)
            host = visible.host
        } else {
            val page = reader ?: newReader()?.also { reader = it } ?: return false
            timers.cancel(T_READER)
            if (!page.load(url)) return false
            host = page.host
        }
        ownerHost = host
        connection = null
        publish(MirrorState(artifactId, MirrorState.Phase.LOADING, title.ifBlank { artifactId }))
        arbiter.mirrorStarted()
        bindSession()
        timers.schedule(T_LOADING, LOADING_TIMEOUT_MS) { loadingTimedOut() }
        return true
    }

    private fun loadingTimedOut() {
        val current = mirror ?: return
        if (current.phase != MirrorState.Phase.LOADING) return
        fail(MESSAGE_NOT_STARTED)
    }

    /** The reader was sent elsewhere (for example the Access login page) or crashed. */
    fun onReaderBlocked(signIn: Boolean) {
        if (mirror?.phase == MirrorState.Phase.LOADING || ownerHost === reader?.host) {
            fail(if (signIn) MESSAGE_SIGN_IN else MESSAGE_NOT_STARTED)
        }
        destroyReader()
    }

    fun onReaderGone(gone: ListenReader) {
        if (reader !== gone) return
        val wasOwner = ownerHost === gone.host
        reader = null
        timers.cancel(T_READER)
        if (wasOwner) {
            if (mirror?.phase == MirrorState.Phase.LOADING) fail(MESSAGE_NOT_STARTED) else end()
        }
        changed()
    }

    private fun fail(error: String) {
        timers.cancel(T_LOADING)
        val current = mirror ?: return
        // The error stays on screen until stopped; no page owns it any more.
        ownerHost = null
        connection = null
        publish(current.copy(phase = MirrorState.Phase.FAILED, actions = emptySet(), error = error))
        if (reader != null) scheduleReaderDestroy(READER_STOP_GRACE_MS)
    }

    // ---- controller -> page ------------------------------------------------------------------

    /** A transport control from the media session (car, notification, headset). */
    fun command(action: MediaAction) {
        val current = mirror ?: return
        when (current.phase) {
            MirrorState.Phase.LOADING -> if (action == MediaAction.PAUSE || action == MediaAction.STOP) {
                end()
                destroyReader()
            }
            MirrorState.Phase.FAILED -> if (action == MediaAction.STOP || action == MediaAction.PAUSE) end()
            MirrorState.Phase.PLAYING, MirrorState.Phase.PAUSED -> {
                if (action !in current.actions) return
                if (!send(action)) end()
            }
        }
    }

    /** Ask the owning page to pause (the native queue took over). */
    fun pauseForNative() {
        if (mirror?.phase == MirrorState.Phase.PLAYING) send(MediaAction.PAUSE)
    }

    private fun send(action: MediaAction): Boolean {
        val current = mirror ?: return false
        val target = connection ?: return false
        return try {
            target.post(Protocol.mediaCommand(current.artifactId, action))
            true
        } catch (_: RuntimeException) {
            // The page's reply channel is gone (it navigated or was destroyed).
            false
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    private fun end() {
        timers.cancel(T_LOADING)
        ownerHost = null
        connection = null
        publish(null)
        arbiter.mirrorEnded()
    }

    private fun scheduleReaderDestroy(delayMs: Long) {
        timers.schedule(T_READER, delayMs) {
            if (!isPlayingOn(reader?.host)) destroyReader()
        }
    }

    fun destroyReader() {
        timers.cancel(T_READER)
        val page = reader ?: return
        reader = null
        if (ownerHost === page.host) end()
        page.destroy()
        changed()
    }

    /** Sign out, origin change, or the media service ended. */
    fun reset() {
        destroyReader()
        if (mirror != null) end()
    }

    private fun publish(next: MirrorState?) {
        mirror = next
        player?.update(next)
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }
}
