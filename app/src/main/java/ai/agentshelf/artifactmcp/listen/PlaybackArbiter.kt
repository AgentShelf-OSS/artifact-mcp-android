package ai.agentshelf.artifactmcp.listen

/**
 * Chooses which player the media session exposes: the native ExoPlayer queue (stories,
 * geotriggers) or the Listen mirror. The last source that starts playing wins; the other one
 * is paused.
 */
class PlaybackArbiter(
    private val pauseNative: () -> Unit,
    private val pauseMirror: () -> Unit,
    private val onActiveChanged: (Source) -> Unit,
) {
    enum class Source { NATIVE, MIRROR }

    var active: Source = Source.NATIVE
        private set

    /** A native clip started playing. */
    fun nativeStarted() {
        if (active == Source.MIRROR) pauseMirror()
        switch(Source.NATIVE)
    }

    /** Listen started playing, or a library pick is opening it. */
    fun mirrorStarted() {
        pauseNative()
        switch(Source.MIRROR)
    }

    /** Listen stopped (`state: none`) or its page went away. */
    fun mirrorEnded() {
        switch(Source.NATIVE)
    }

    /** A controller picked a native queue or geotrigger item. */
    fun nativeSelected() {
        if (active == Source.MIRROR) pauseMirror()
        switch(Source.NATIVE)
    }

    private fun switch(source: Source) {
        if (active == source) return
        active = source
        onActiveChanged(source)
    }
}

/** When the viewer WebView (and the process-wide WebView timers) may be paused in the background. */
object WebViewPausePolicy {
    /** Grace period after the app leaves the screen or Listen pauses before pausing the WebView. */
    const val GRACE_MS = 15_000L

    fun pauseView(visible: Boolean, listenPlayingHere: Boolean, locationInUse: Boolean): Boolean =
        !visible && !listenPlayingHere && !locationInUse

    /** `pauseTimers` affects every WebView in the process, including the Listen reader. */
    fun pauseTimers(visible: Boolean, listenPlaying: Boolean, readerAlive: Boolean, locationInUse: Boolean): Boolean =
        !visible && !listenPlaying && !readerAlive && !locationInUse
}
