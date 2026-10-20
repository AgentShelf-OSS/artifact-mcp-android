package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.bridge.Protocol.Inbound
import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import ai.agentshelf.artifactmcp.bridge.Protocol.MediaState
import ai.agentshelf.artifactmcp.bridge.ShellConnection
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test

class ListenControllerTest {
    private val origin = "https://artifact.example.com"

    private class Page(override val pageArtifactId: String?, override val host: Any) : ShellConnection {
        val sent = mutableListOf<String>()
        var broken = false
        override fun post(message: String) {
            if (broken) throw IllegalStateException("gone")
            sent += message
        }
        fun commands() = sent.map { (Protocol.parseJson(it) as JsonObject)["action"].let { a -> (a as JsonPrimitive).content } }
    }

    private class FakeReader : ListenReader {
        override val host = Any()
        val loaded = mutableListOf<String>()
        var destroyed = false
        override fun load(url: String): Boolean {
            loaded += url
            return true
        }
        override fun destroy() {
            destroyed = true
        }
    }

    private class FakeViewer : ListenViewer {
        override val host = Any()
        val opened = mutableListOf<String>()
        override fun openListen(url: String) {
            opened += url
        }
    }

    private class ManualTimers : ListenTimers {
        val pending = LinkedHashMap<String, Pair<Long, () -> Unit>>()
        override fun schedule(key: String, delayMs: Long, block: () -> Unit) {
            pending[key] = delayMs to block
        }
        override fun cancel(key: String) {
            pending.remove(key)
        }
        fun fire(key: String) = pending.remove(key)!!.second()
    }

    private val timers = ManualTimers()
    private val readers = mutableListOf<FakeReader>()
    private var bound = 0
    private var nativePaused = 0
    private val actives = mutableListOf<PlaybackArbiter.Source>()
    private lateinit var listen: ListenController
    private lateinit var arbiter: PlaybackArbiter
    private var online = true
    private var now = 5_000L

    @Before fun setUp() {
        listen = ListenController({ origin }, timers, { FakeReader().also { readers += it } }, { bound++ }, { online }, { now })
        arbiter = PlaybackArbiter({ nativePaused++ }, { listen.pauseForNative() }, { actives += it })
        listen.arbiter = arbiter
    }

    private fun session(state: MediaState, artifactId: String = "book", title: String = "Ch 1") = Inbound.MediaSession(
        artifactId, state, title, "Ava", "Book",
        if (state == MediaState.NONE) emptySet() else MediaAction.entries.toSet(),
    )

    @Test fun `playing session from the viewer takes over the media session`() {
        val viewerHost = Any()
        val page = Page("book", viewerHost)
        listen.onMediaSession(session(MediaState.PLAYING), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PLAYING)
        assertThat(listen.mirror?.title).isEqualTo("Ch 1")
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.MIRROR)
        assertThat(nativePaused).isEqualTo(1)
        assertThat(bound).isEqualTo(1)
        assertThat(listen.isPlayingOn(viewerHost)).isTrue()

        listen.onMediaSession(session(MediaState.PAUSED), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PAUSED)
        assertThat(listen.isPlayingOn(viewerHost)).isFalse()
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.MIRROR)

        listen.onMediaSession(session(MediaState.NONE), page)
        assertThat(listen.mirror).isNull()
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.NATIVE)
    }

    @Test fun `commands are forwarded to the page that sent the session`() {
        val page = Page("book", Any())
        listen.onMediaSession(session(MediaState.PLAYING), page)
        listen.command(MediaAction.PAUSE)
        listen.command(MediaAction.NEXT)
        listen.command(MediaAction.PREVIOUS)
        listen.command(MediaAction.STOP)
        assertThat(page.commands()).containsExactly("pause", "next", "previous", "stop").inOrder()
        val o = Protocol.parseJson(page.sent.first()) as JsonObject
        assertThat((o["artifactId"] as JsonPrimitive).content).isEqualTo("book")
        assertThat((o["type"] as JsonPrimitive).content).isEqualTo("media.command")
    }

    @Test fun `actions the page did not offer are not sent`() {
        val page = Page("book", Any())
        listen.onMediaSession(session(MediaState.PLAYING).copy(actions = setOf(MediaAction.PAUSE)), page)
        listen.command(MediaAction.NEXT)
        listen.command(MediaAction.PAUSE)
        assertThat(page.commands()).containsExactly("pause")
    }

    @Test fun `rewind and speed go to a page that reports a rate or lists them`() {
        val classic = setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.NEXT, MediaAction.PREVIOUS, MediaAction.STOP)
        val old = Page("book", Any())
        listen.onMediaSession(session(MediaState.PLAYING).copy(actions = classic), old)
        listen.command(MediaAction.REWIND)
        listen.command(MediaAction.SPEED)
        assertThat(old.commands()).isEmpty()

        // An ADR-0021 shell sends `rate` but still lists the original five actions.
        listen.onMediaSession(session(MediaState.PLAYING).copy(actions = classic, rate = "1.25"), old)
        assertThat(listen.mirror?.rate).isEqualTo("1.25")
        listen.command(MediaAction.REWIND)
        listen.command(MediaAction.SPEED)
        assertThat(old.commands()).containsExactly("rewind", "speed").inOrder()

        val listing = Page("other", Any())
        listen.onMediaSession(session(MediaState.PLAYING, artifactId = "other").copy(actions = classic + MediaAction.REWIND), listing)
        listen.command(MediaAction.REWIND)
        listen.command(MediaAction.SPEED)
        assertThat(listing.commands()).containsExactly("rewind")
    }

    @Test fun `a broken reply channel ends the mirror`() {
        val page = Page("book", Any())
        listen.onMediaSession(session(MediaState.PLAYING), page)
        page.broken = true
        listen.command(MediaAction.PAUSE)
        assertThat(listen.mirror).isNull()
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.NATIVE)
    }

    @Test fun `last active page wins and the previous one pauses`() {
        val first = Page("book", Any())
        val second = Page("other", Any())
        listen.onMediaSession(session(MediaState.PLAYING), first)
        listen.onMediaSession(session(MediaState.PLAYING, "other", "Other"), second)
        assertThat(first.commands()).containsExactly("pause")
        assertThat(listen.mirror?.artifactId).isEqualTo("other")
        // The paused report of the old page no longer moves the mirror.
        listen.onMediaSession(session(MediaState.PAUSED), first)
        assertThat(listen.mirror?.artifactId).isEqualTo("other")
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PLAYING)
        listen.onMediaSession(session(MediaState.NONE), first)
        assertThat(listen.mirror?.artifactId).isEqualTo("other")
    }

    @Test fun `native playback pauses Listen and takes the session back`() {
        val page = Page("book", Any())
        listen.onMediaSession(session(MediaState.PLAYING), page)
        arbiter.nativeStarted()
        assertThat(page.commands()).containsExactly("pause")
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.NATIVE)
        // Listen resumes from the page: the mirror takes over again and native pauses.
        listen.onMediaSession(session(MediaState.PLAYING), page)
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.MIRROR)
        assertThat(nativePaused).isEqualTo(2)
        assertThat(actives).containsExactly(
            PlaybackArbiter.Source.MIRROR, PlaybackArbiter.Source.NATIVE, PlaybackArbiter.Source.MIRROR,
        ).inOrder()
    }

    @Test fun `navigating away ends the mirror for that page only`() {
        val host = Any()
        listen.onMediaSession(session(MediaState.PLAYING), Page("book", host))
        listen.onHostGone(Any())
        assertThat(listen.mirror).isNotNull()
        listen.onHostGone(host)
        assertThat(listen.mirror).isNull()
    }

    @Test fun `library pick without a visible viewer opens a reader with autoplay`() {
        assertThat(listen.playFromLibrary("book", "Frankenstein")).isTrue()
        val reader = readers.single()
        assertThat(reader.loaded).containsExactly("$origin/book?listen=autoplay")
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.LOADING)
        assertThat(listen.mirror?.title).isEqualTo("Frankenstein")
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.MIRROR)
        assertThat(listen.isPlayingOn(reader.host)).isTrue()

        // The page reports `none` and `paused` before autoplay starts: still loading.
        val page = Page("book", reader.host)
        listen.onMediaSession(session(MediaState.NONE), page)
        listen.onMediaSession(session(MediaState.PAUSED), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.LOADING)

        listen.onMediaSession(session(MediaState.PLAYING), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PLAYING)
        assertThat(timers.pending).doesNotContainKey("loading")

        // Stop: the page sends none; the reader is destroyed after the grace period.
        listen.command(MediaAction.STOP)
        assertThat(page.commands()).containsExactly("stop")
        listen.onMediaSession(session(MediaState.NONE), page)
        assertThat(listen.mirror).isNull()
        assertThat(reader.destroyed).isFalse()
        assertThat(timers.pending["reader"]?.first).isEqualTo(ListenController.READER_STOP_GRACE_MS)
        timers.fire("reader")
        assertThat(reader.destroyed).isTrue()
        assertThat(listen.reader).isNull()
    }

    @Test fun `only one reader exists and it is reused`() {
        listen.playFromLibrary("book", "A")
        listen.playFromLibrary("other", "B")
        assertThat(readers).hasSize(1)
        assertThat(readers.single().loaded).containsExactly("$origin/book?listen=autoplay", "$origin/other?listen=autoplay").inOrder()
    }

    @Test fun `library pick with the viewer on screen navigates the viewer and drops the reader`() {
        listen.playFromLibrary("book", "A")
        val reader = readers.single()
        val viewer = FakeViewer()
        listen.viewer = viewer
        listen.playFromLibrary("other", "B")
        assertThat(reader.destroyed).isTrue()
        assertThat(viewer.opened).containsExactly("$origin/other?listen=autoplay")
        assertThat(readers).hasSize(1)
        // The viewer's old page reports gone while it navigates: the pick keeps loading.
        listen.onHostGone(viewer.host)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.LOADING)
        listen.onMediaSession(session(MediaState.PLAYING, "other"), Page("other", viewer.host))
        assertThat(listen.isPlayingOn(viewer.host)).isTrue()
    }

    @Test fun `invalid artifact ids open nothing`() {
        assertThat(listen.playFromLibrary("../admin", "x")).isFalse()
        assertThat(listen.playFromLibrary("a b", "x")).isFalse()
        assertThat(readers).isEmpty()
        assertThat(listen.mirror).isNull()
    }

    @Test fun `loading times out into an error state`() {
        listen.playFromLibrary("book", "A")
        timers.fire("loading")
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.FAILED)
        assertThat(listen.mirror?.error).isEqualTo(ListenController.MESSAGE_NOT_STARTED)
        listen.command(MediaAction.STOP)
        assertThat(listen.mirror).isNull()
    }

    @Test fun `reader sent to the login page asks to sign in on the phone`() {
        listen.playFromLibrary("book", "A")
        listen.onReaderBlocked(signIn = true)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.FAILED)
        assertThat(listen.mirror?.error).isEqualTo(ListenController.MESSAGE_SIGN_IN)
        assertThat(readers.single().destroyed).isTrue()
    }

    @Test fun `stopping while loading cancels the pick`() {
        listen.playFromLibrary("book", "A")
        listen.command(MediaAction.PAUSE)
        assertThat(listen.mirror).isNull()
        assertThat(readers.single().destroyed).isTrue()
        assertThat(arbiter.active).isEqualTo(PlaybackArbiter.Source.NATIVE)
    }

    @Test fun `a paused reader is destroyed after the idle period`() {
        listen.playFromLibrary("book", "A")
        val reader = readers.single()
        val page = Page("book", reader.host)
        listen.onMediaSession(session(MediaState.PLAYING), page)
        listen.onMediaSession(session(MediaState.PAUSED), page)
        assertThat(timers.pending["reader"]?.first).isEqualTo(ListenController.READER_IDLE_MS)
        listen.onMediaSession(session(MediaState.PLAYING), page)
        assertThat(timers.pending).doesNotContainKey("reader")
    }

    @Test fun `reset destroys the reader and clears the mirror`() {
        listen.playFromLibrary("book", "A")
        listen.reset()
        assertThat(readers.single().destroyed).isTrue()
        assertThat(listen.mirror).isNull()
    }

    // ---- ADR-0022: progress, chapters, buffering, errors ------------------------------------

    private fun progressSession(
        state: MediaState,
        chapterIndex: Int? = null,
        position: Double? = null,
        duration: Double? = null,
        error: String? = null,
        artifactId: String = "book",
    ) = Inbound.MediaSession(
        artifactId, state, "Ch 1", "Ava", "Book",
        if (state == MediaState.NONE) emptySet() else MediaAction.entries.toSet(),
        rate = if (state == MediaState.NONE) null else "1",
        position = position, duration = duration, chapterIndex = chapterIndex, error = error,
    )

    private fun Page.lastCommand() = (Protocol.parseJson(sent.last()) as JsonObject)

    @Test fun `buffering takes over like playing and shows buffering`() {
        val page = Page("book", Any())
        listen.onMediaSession(progressSession(MediaState.BUFFERING), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.BUFFERING)
        assertThat(listen.isPlaying).isTrue()
        assertThat(actives.last()).isEqualTo(PlaybackArbiter.Source.MIRROR)
        listen.command(MediaAction.PAUSE)
        assertThat(page.commands()).containsExactly("pause")
        listen.onMediaSession(progressSession(MediaState.PLAYING, position = 3.0, duration = 60.0), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PLAYING)
        assertThat(listen.mirror?.positionS).isEqualTo(3.0)
        assertThat(listen.mirror?.durationS).isEqualTo(60.0)
        assertThat(listen.mirror?.positionAtMs).isEqualTo(5_000L)
    }

    @Test fun `chapters from the page reach the mirror, before or after its session`() {
        val host = Any()
        val page = Page("book", host)
        listen.onMediaChapters(Inbound.MediaChapters("book", listOf("One", "Two", "Three")), page)
        listen.onMediaSession(progressSession(MediaState.PLAYING, chapterIndex = 1), page)
        assertThat(listen.mirror?.chapters).containsExactly("One", "Two", "Three").inOrder()
        assertThat(listen.mirror?.hasChapterQueue).isTrue()
        listen.onMediaChapters(Inbound.MediaChapters("book", listOf("A", "B")), page)
        assertThat(listen.mirror?.chapters).containsExactly("A", "B").inOrder()
        // Another page's chapters do not change this mirror.
        listen.onMediaChapters(Inbound.MediaChapters("other", listOf("X")), Page("other", Any()))
        assertThat(listen.mirror?.chapters).containsExactly("A", "B").inOrder()
        // Chapters of a different artifact on the same page are not applied.
        listen.onMediaChapters(Inbound.MediaChapters("book2", listOf("Z")), page)
        assertThat(listen.mirror?.chapters).containsExactly("A", "B").inOrder()
    }

    @Test fun `chapter picks send the chapter command and show the pick at once`() {
        val page = Page("book", Any())
        listen.onMediaChapters(Inbound.MediaChapters("book", listOf("One", "Two", "Three")), page)
        listen.onMediaSession(progressSession(MediaState.PLAYING, chapterIndex = 0, position = 40.0, duration = 90.0), page)
        now = 9_000L
        listen.chapter(2)
        val command = page.lastCommand()
        assertThat((command["action"] as JsonPrimitive).content).isEqualTo("chapter")
        assertThat((command["index"] as JsonPrimitive).content).isEqualTo("2")
        assertThat(listen.mirror?.chapterIndex).isEqualTo(2)
        assertThat(listen.mirror?.title).isEqualTo("Three")
        assertThat(listen.mirror?.positionS).isEqualTo(0.0)
        assertThat(listen.mirror?.positionAtMs).isEqualTo(9_000L)
        // Out of range or not offered: nothing is sent.
        val before = page.sent.size
        listen.chapter(3)
        listen.chapter(-1)
        listen.command(MediaAction.CHAPTER)
        assertThat(page.sent).hasSize(before)
        listen.onMediaSession(progressSession(MediaState.PLAYING, chapterIndex = 0).copy(actions = setOf(MediaAction.PAUSE)), page)
        listen.chapter(1)
        assertThat(page.sent).hasSize(before)
    }

    @Test fun `forward goes to a page that offers it`() {
        val page = Page("book", Any())
        listen.onMediaSession(progressSession(MediaState.PLAYING), page)
        listen.command(MediaAction.FORWARD)
        assertThat(page.commands()).containsExactly("forward")
        listen.onMediaSession(session(MediaState.PLAYING).copy(actions = setOf(MediaAction.PAUSE)), page)
        listen.command(MediaAction.FORWARD)
        assertThat(page.commands()).containsExactly("forward")
    }

    @Test fun `offline pick fails at once with no connection and opens nothing`() {
        online = false
        assertThat(listen.playFromLibrary("book", "Frankenstein")).isTrue()
        assertThat(readers).isEmpty()
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.FAILED)
        assertThat(listen.mirror?.failure).isEqualTo(MirrorState.Failure.OFFLINE)
        assertThat(listen.mirror?.error).isEqualTo(ListenController.MESSAGE_OFFLINE)
        assertThat(timers.pending).doesNotContainKey("loading")
        assertThat(bound).isEqualTo(1)
        // Play retries; back online it opens the reader.
        online = true
        listen.command(MediaAction.PLAY)
        assertThat(readers.single().loaded.single()).endsWith("/book?listen=autoplay")
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.LOADING)
    }

    @Test fun `reader load failures map to distinct errors`() {
        for ((failure, message) in listOf(
            MirrorState.Failure.OFFLINE to ListenController.MESSAGE_OFFLINE,
            MirrorState.Failure.NOT_FOUND to ListenController.MESSAGE_NOT_FOUND,
            MirrorState.Failure.SERVER to ListenController.MESSAGE_SERVER,
            MirrorState.Failure.SIGN_IN to ListenController.MESSAGE_SIGN_IN,
        )) {
            listen.reset()
            assertThat(listen.playFromLibrary("book", "Frankenstein")).isTrue()
            listen.onReaderFailed(failure)
            assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.FAILED)
            assertThat(listen.mirror?.failure).isEqualTo(failure)
            assertThat(listen.mirror?.error).isEqualTo(message)
            assertThat(readers.last().destroyed).isTrue()
            assertThat(timers.pending).doesNotContainKey("loading")
        }
    }

    @Test fun `a page error ends Listen with the page's message`() {
        assertThat(listen.playFromLibrary("book", "Frankenstein")).isTrue()
        val reader = readers.single()
        val page = Page("book", reader.host)
        listen.onMediaSession(progressSession(MediaState.NONE, error = "Speech is unavailable"), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.FAILED)
        assertThat(listen.mirror?.failure).isEqualTo(MirrorState.Failure.PAGE)
        assertThat(listen.mirror?.error).isEqualTo("Speech is unavailable")
    }

    @Test fun `a paused page with an error keeps the session and shows the error`() {
        assertThat(listen.playFromLibrary("book", "Frankenstein")).isTrue()
        val page = Page("book", readers.single().host)
        listen.onMediaSession(progressSession(MediaState.PAUSED, error = "No connection"), page)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PAUSED)
        assertThat(listen.mirror?.error).isEqualTo("No connection")
        assertThat(timers.pending).doesNotContainKey("loading")
        listen.command(MediaAction.PLAY)
        assertThat(page.commands()).containsExactly("play")
    }

    @Test fun `a failure for one artifact is not hidden by another page pausing`() {
        val viewer = Page("other", Any())
        listen.onMediaSession(session(MediaState.PLAYING, artifactId = "other"), viewer)
        online = false
        listen.playFromLibrary("book", "Frankenstein")
        assertThat(viewer.commands()).containsExactly("pause")
        listen.onMediaSession(session(MediaState.PAUSED, artifactId = "other"), viewer)
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.FAILED)
        assertThat(listen.mirror?.artifactId).isEqualTo("book")
        // A page of the failed artifact may take over.
        listen.onMediaSession(session(MediaState.PAUSED), Page("book", Any()))
        assertThat(listen.mirror?.phase).isEqualTo(MirrorState.Phase.PAUSED)
    }

    @Test fun `failure messages are distinct`() {
        val messages = MirrorState.Failure.entries.filter { it != MirrorState.Failure.PAGE }.map { ListenController.message(it) }
        assertThat(messages.toSet()).hasSize(messages.size)
    }
}
