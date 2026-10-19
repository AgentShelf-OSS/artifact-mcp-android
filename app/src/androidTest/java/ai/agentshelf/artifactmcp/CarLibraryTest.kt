package ai.agentshelf.artifactmcp

import ai.agentshelf.artifactmcp.audio.PlaybackService
import ai.agentshelf.artifactmcp.ui.MainActivity
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Emulator test of the car library and the Listen mirror through the legacy media browser API,
 * the path Android Auto uses (build/emulator/test-listen.sh runs it against a local server).
 *
 * Arguments: `origin`, `artifactId`, optional `signedOutOrigin`. Lines tagged `MARK` tell the
 * host script when to take screenshots; the test then waits [SHOT_MS]. The ADR-0022 tests
 * (progress, chapters, buffering, page errors) need a server whose shell supports
 * `media.progress`; the host script sets the fake speech worker's mode (`ok`, `500`, `slow`)
 * before [speechFailureShowsThePageError] and [bufferingWhileSpeechIsSlow].
 */
@RunWith(AndroidJUnit4::class)
class CarLibraryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as ArtifactApp
    private val args = InstrumentationRegistry.getArguments()
    private val origin = args.getString("origin") ?: "http://localhost:8480"
    private val artifactId = args.getString("artifactId") ?: error("artifactId argument is required")

    /** An e-reader artifact with chapters (build/emulator/book.html), for the chapter queue. */
    private val bookId = args.getString("bookId") ?: artifactId

    private companion object {
        const val TAG = "CarLibraryTest"
        const val SHOT_MS = 9_000L
        const val MediaBrowserServiceRecent = "android.service.media.extra.RECENT"
    }

    private fun log(line: String) = Log.i(TAG, line)

    private fun mark(name: String, detail: String) {
        log("MARK $name $detail")
        SystemClock.sleep(SHOT_MS)
    }

    private fun shell(command: String) {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun setOrigin(value: String) = instrumentation.runOnMainSync {
        app.originStore.origin = value
        app.library.clear()
    }

    private fun connect(): MediaBrowser {
        val connected = CountDownLatch(1)
        lateinit var browser: MediaBrowser
        instrumentation.runOnMainSync {
            val hints = Bundle().apply { putInt("androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT", 4) }
            browser = MediaBrowser(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
            }, hints)
            browser.connect()
        }
        assertThat(connected.await(30, TimeUnit.SECONDS)).isTrue()
        return browser
    }

    private fun children(browser: MediaBrowser, parent: String): List<MediaBrowser.MediaItem> {
        val done = CountDownLatch(1)
        var result: List<MediaBrowser.MediaItem> = emptyList()
        instrumentation.runOnMainSync {
            browser.subscribe(parent, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                    result = children.toList()
                    browser.unsubscribe(parent)
                    done.countDown()
                }

                override fun onError(parentId: String) = done.countDown()
            })
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        log("children $parent: " + result.joinToString { "${it.mediaId}=${it.description.title}|${it.description.subtitle}|playable=${it.isPlayable}" })
        return result
    }

    private fun controller(browser: MediaBrowser): MediaController {
        lateinit var controller: MediaController
        instrumentation.runOnMainSync { controller = MediaController(context, browser.sessionToken) }
        return controller
    }

    private fun describe(controller: MediaController): String {
        val state = controller.playbackState
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        return "state=${state?.state} actions=${state?.actions} title=$title artist=$artist error=${state?.errorMessage}"
    }

    private fun waitState(controller: MediaController, want: Int, timeoutMs: Long, extra: (MediaController) -> Boolean = { true }): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (controller.playbackState?.state == want && extra(controller)) return true
            SystemClock.sleep(250)
        }
        log("timeout waiting for state $want: ${describe(controller)}")
        return false
    }

    private fun title(controller: MediaController) = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

    private fun check(name: String, ok: Boolean, controller: MediaController? = null) {
        log("${if (ok) "PASS" else "FAIL"} $name ${controller?.let(::describe).orEmpty()}")
        assertThat(ok).isTrue()
    }

    @Test fun signedOutLibraryShowsTheSignInItem() {
        val signedOut = args.getString("signedOutOrigin") ?: return
        setOrigin(signedOut)
        val browser = connect()
        val root = children(browser, browser.root)
        check("tabs: Continue and Library", root.map { it.mediaId } == listOf("continue", "library") && root.all { it.isBrowsable })
        check("tabs: each has a drawable icon", root.all { it.description.iconUri?.scheme == "android.resource" })
        val library = children(browser, "library")
        check("signed out: one non-playable sign-in item", library.size == 1 && library[0].mediaId == "signin" && !library[0].isPlayable)
        browser.disconnect()
        setOrigin(origin)
    }

    /** Covers are content:// URIs another app can open (the car loads them itself). */
    private fun coverReadable(item: MediaBrowser.MediaItem): Boolean {
        val uri = item.description.iconUri ?: return false
        if (uri.scheme != "content") return false
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.width == 512
    }

    @Test fun readerPlaysFromTheCarLibrary() {
        setOrigin(origin)
        val browser = connect()
        val root = children(browser, browser.root)
        check("tabs: root starts with Continue and Library", root.take(2).map { it.mediaId } == listOf("continue", "library"))
        val orgs = children(browser, "library").filter { it.mediaId?.startsWith("org:") == true }
        check("library: the Library tab lists organization folders", orgs.isNotEmpty() && orgs.all { it.isBrowsable })
        check("library: organization folders have covers", coverReadable(orgs.first()))
        // An administrator sees every organization: look for the story in each folder.
        val item = orgs.asSequence().flatMap { children(browser, it.mediaId!!).asSequence() }.firstOrNull { it.mediaId == "art:$artifactId" }
        check("library: the story artifact is a playable item", item != null && item.isPlayable)
        check("library: artifacts have covers and a category group", coverReadable(item!!) &&
            item.description.extras?.getString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT") != null)

        val controller = controller(browser)
        controller.transportControls.playFromMediaId("art:$artifactId", null)
        check("reader: Listen starts and the mirror shows playing", waitState(controller, PlaybackState.STATE_PLAYING, 120_000) { title(it) != null }, controller)
        mark("reader-playing", describe(controller))
        val art = controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        log("${if (art != null) "PASS" else "INFO"} now playing: artwork bitmap ${art?.width}x${art?.height}")
        val custom = controller.playbackState?.customActions.orEmpty().map { it.action }
        log("INFO now playing: custom actions $custom")
        val continueTab = children(browser, "continue")
        check("continue: the playing artifact is listed first", continueTab.firstOrNull()?.mediaId == "art:$artifactId")

        val first = title(controller)
        controller.transportControls.skipToNext()
        val advanced = waitState(controller, PlaybackState.STATE_PLAYING, 20_000) { title(it) != first }
        log("${if (advanced) "PASS" else "INFO"} reader: next changes the section title ($first -> ${title(controller)})")

        controller.transportControls.pause()
        check("reader: pause reaches the page and it reports paused", waitState(controller, PlaybackState.STATE_PAUSED, 15_000), controller)
        mark("reader-paused", describe(controller))
        controller.transportControls.play()
        check("reader: play resumes", waitState(controller, PlaybackState.STATE_PLAYING, 15_000), controller)
        controller.transportControls.stop()
        check("reader: stop ends the mirror", waitState(controller, PlaybackState.STATE_NONE, 15_000) || waitState(controller, PlaybackState.STATE_STOPPED, 1_000), controller)
        browser.disconnect()
    }

    @Test fun visibleViewerPlaysFromTheCarLibraryAndKeepsRunningWithTheScreenOff() {
        setOrigin(origin)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(8_000)
        val browser = connect()
        val controller = controller(browser)
        controller.transportControls.playFromMediaId("art:$artifactId", null)
        check("viewer: Listen starts in the visible viewer", waitState(controller, PlaybackState.STATE_PLAYING, 120_000) { title(it) != null }, controller)
        check("viewer: no reader WebView was created", app.listen.reader == null)
        mark("viewer-playing", describe(controller))

        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(25_000) // longer than the 15 s pause grace period
        controller.transportControls.pause()
        check("screen off: the page still answers pause after the grace period", waitState(controller, PlaybackState.STATE_PAUSED, 15_000), controller)
        SystemClock.sleep(25_000) // paused: the viewer WebView is paused after the grace period
        controller.transportControls.play()
        check("screen off: play wakes the paused viewer and Listen resumes", waitState(controller, PlaybackState.STATE_PLAYING, 20_000), controller)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        mark("viewer-resumed", describe(controller))
        controller.transportControls.stop()
        waitState(controller, PlaybackState.STATE_NONE, 15_000)
        browser.disconnect()
    }

    // ---- 0.4.0: resumption, fail fast, errors, progress and chapters (ADR-0022) -------------

    private fun mirror(): ai.agentshelf.artifactmcp.listen.MirrorState? {
        var m: ai.agentshelf.artifactmcp.listen.MirrorState? = null
        instrumentation.runOnMainSync { m = app.listen.mirror }
        return m
    }

    private fun waitMirror(timeoutMs: Long, what: String, pred: (ai.agentshelf.artifactmcp.listen.MirrorState?) -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (pred(mirror())) return true
            SystemClock.sleep(200)
        }
        log("INFO timeout waiting for $what: ${mirror()}")
        return false
    }

    private fun online(): Boolean {
        var value = true
        instrumentation.runOnMainSync { value = app.isOnline() }
        return value
    }

    private fun setNetwork(up: Boolean) {
        val verb = if (up) "enable" else "disable"
        shell("svc wifi $verb")
        shell("svc data $verb")
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end && online() != up) SystemClock.sleep(250)
        log("INFO network ${if (up) "up" else "down"}: online=${online()}")
    }

    private fun stopAll(controller: MediaController) {
        controller.transportControls.stop()
        waitMirror(15_000, "mirror ended") { it == null }
    }

    @Test fun offlinePickFailsFastAndPlayRetries() {
        setOrigin(origin)
        val browser = connect()
        val controller = controller(browser)
        setNetwork(false)
        try {
            check("offline: the device reports no network", !online())
            val started = SystemClock.elapsedRealtime()
            controller.transportControls.playFromMediaId("art:$artifactId", null)
            val failed = waitState(controller, PlaybackState.STATE_ERROR, 10_000) { it.playbackState?.errorMessage == "No connection" }
            val ms = SystemClock.elapsedRealtime() - started
            check("offline: a pick fails within 5 s with 'No connection' (took $ms ms)", failed && ms < 5_000, controller)
            check("offline: no reader page was opened", app.listen.reader == null)
            check("offline: the failure kind is OFFLINE", mirror()?.failure == ai.agentshelf.artifactmcp.listen.MirrorState.Failure.OFFLINE)
            val cont = children(browser, "continue")
            log("INFO offline continue rows: ${cont.map { "${it.mediaId}|${it.description.subtitle}" }}")
            mark("offline-pick", describe(controller))
        } finally {
            setNetwork(true)
        }
        controller.transportControls.play()
        check("offline: play after the network is back retries the pick", waitState(controller, PlaybackState.STATE_PLAYING, 120_000) { title(it) != null }, controller)
        stopAll(controller)
        browser.disconnect()
    }

    @Test fun missingArtifactFailsFastAsNotFound() {
        setOrigin(origin)
        val browser = connect()
        val controller = controller(browser)
        val started = SystemClock.elapsedRealtime()
        controller.transportControls.playFromMediaId("art:nosuchartifact404", null)
        val failed = waitState(controller, PlaybackState.STATE_ERROR, 30_000)
        val ms = SystemClock.elapsedRealtime() - started
        val m = mirror()
        log("INFO not found: ${m?.failure} '${m?.error}' after $ms ms")
        check("not found: the pick fails well before the 45 s timeout ($ms ms)", failed && ms < 20_000, controller)
        check("not found: the error is NOT_FOUND, not a timeout", m?.failure == ai.agentshelf.artifactmcp.listen.MirrorState.Failure.NOT_FOUND)
        stopAll(controller)
        browser.disconnect()
    }

    @Test fun speechFailureShowsThePageError() {
        setOrigin(origin)
        val browser = connect()
        val controller = controller(browser)
        controller.transportControls.playFromMediaId("art:$artifactId", null)
        val failed = waitState(controller, PlaybackState.STATE_ERROR, 120_000) { it.playbackState?.errorMessage != null }
        val m = mirror()
        log("INFO speech failure: phase=${m?.phase} failure=${m?.failure} error='${m?.error}'")
        check("speech 500: the car shows the page's reason", failed && m?.failure == ai.agentshelf.artifactmcp.listen.MirrorState.Failure.PAGE && !m.error.isNullOrBlank(), controller)
        mark("speech-error", describe(controller))
        stopAll(controller)
        browser.disconnect()
    }

    @Test fun bufferingWhileSpeechIsSlow() {
        setOrigin(origin)
        val browser = connect()
        val controller = controller(browser)
        controller.transportControls.playFromMediaId("art:$artifactId", null)
        val buffering = waitMirror(120_000, "buffering") { it?.phase == ai.agentshelf.artifactmcp.listen.MirrorState.Phase.BUFFERING }
        val legacy = controller.playbackState?.state
        check("slow speech: the page reports buffering and the session shows it (legacy state $legacy)", buffering && waitState(controller, PlaybackState.STATE_BUFFERING, 3_000), controller)
        mark("buffering", describe(controller))
        check("slow speech: playback starts after buffering", waitState(controller, PlaybackState.STATE_PLAYING, 120_000), controller)
        stopAll(controller)
        browser.disconnect()
    }

    @Test fun chaptersQueueProgressAndForward() {
        setOrigin(origin)
        val browser = connect()
        val controller = controller(browser)
        controller.transportControls.playFromMediaId("art:$bookId", null)
        check("progress: Listen plays", waitState(controller, PlaybackState.STATE_PLAYING, 120_000) { title(it) != null }, controller)
        val chapters = waitMirror(30_000, "chapters") { (it?.chapters?.size ?: 0) > 1 && it?.chapterIndex != null }
        val m = mirror()
        log("INFO chapters=${m?.chapters} index=${m?.chapterIndex} position=${m?.positionS} duration=${m?.durationS}")
        check("chapters: the page sends its chapter list and the current index", chapters)
        val queue = controller.queue.orEmpty()
        log("INFO queue: ${queue.map { "${it.queueId}=${it.description.title}" }}")
        check("chapters: the session queue has one item per chapter", queue.size == m?.chapters?.size)
        check("progress: duration and position reach the session",
            waitMirror(20_000, "position") { it?.positionS != null } && (controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) > 0L, controller)
        val p1 = controller.playbackState?.position ?: -1
        SystemClock.sleep(3_000)
        val p2 = controller.playbackState?.let { it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() } ?: -1
        log("INFO position $p1 -> $p2 (speed ${controller.playbackState?.playbackSpeed})")
        check("progress: the position advances while playing", p2 > p1)
        val custom = controller.playbackState?.customActions.orEmpty().map { it.action }
        check("forward: the Forward 30 s button is offered", "ai.agentshelf.artifactmcp.FORWARD" in custom)
        val target = queue.getOrNull(2) ?: queue.last()
        val targetIndex = queue.indexOf(target)
        controller.transportControls.skipToQueueItem(target.queueId)
        check("chapters: picking a queue item jumps to that chapter",
            waitMirror(30_000, "chapter $targetIndex") { it?.chapterIndex == targetIndex && it.phase == ai.agentshelf.artifactmcp.listen.MirrorState.Phase.PLAYING }, controller)
        mark("chapters-queue", describe(controller) + " queue=${queue.size}")
        val before = mirror()?.let { it.positionMsAt(SystemClock.elapsedRealtime()) }
        controller.transportControls.sendCustomAction("ai.agentshelf.artifactmcp.FORWARD", null)
        SystemClock.sleep(6_000)
        val after = mirror()
        log("INFO forward: position $before -> ${after?.positionMsAt(SystemClock.elapsedRealtime())} chapter ${after?.chapterIndex}")
        check("forward: Listen keeps playing after Forward 30 s", waitState(controller, PlaybackState.STATE_PLAYING, 15_000), controller)
        controller.transportControls.skipToNext()
        check("chapters: next moves to the next chapter",
            waitMirror(30_000, "next chapter") { (it?.chapterIndex ?: -1) > targetIndex || (targetIndex == queue.size - 1) }, controller)
        controller.transportControls.pause()
        waitState(controller, PlaybackState.STATE_PAUSED, 15_000)
        SystemClock.sleep(1_500)
        val recent = app.recents.forOrigin(origin).firstOrNull { it.artifactId == bookId }
        log("INFO recent fraction ${recent?.fraction}")
        check("continue: the played share is stored", recent?.fraction != null && recent.fraction > 0.0)
        val cont = children(browser, "continue").firstOrNull { it.mediaId == "art:$bookId" }
        val pct = cont?.description?.extras?.getDouble("androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE", -1.0)
        check("continue: the row carries the completion percentage ($pct)", pct != null && pct > 0.0)
        stopAll(controller)
        browser.disconnect()
    }

    @Test fun resumptionPlaysTheNewestRecent() {
        setOrigin(origin)
        check("resume: Continue has the story", app.recents.forOrigin(origin).firstOrNull()?.artifactId == artifactId)
        // The recent root (EXTRA_RECENT), as Android Auto asks for its resume card.
        val connected = CountDownLatch(1)
        lateinit var recentBrowser: MediaBrowser
        instrumentation.runOnMainSync {
            val hints = Bundle().apply { putBoolean(MediaBrowserServiceRecent, true) }
            recentBrowser = MediaBrowser(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
            }, hints)
            recentBrowser.connect()
        }
        check("resume: the recent root connects", connected.await(30, TimeUnit.SECONDS))
        log("INFO recent root ${recentBrowser.root} extras ${recentBrowser.extras}")
        val recent = children(recentBrowser, recentBrowser.root)
        check("resume: the recent root lists the newest item", recent.map { it.mediaId } == listOf("art:$artifactId") && recent[0].isPlayable)
        recentBrowser.disconnect()

        val browser = connect()
        val controller = controller(browser)
        stopAll(controller)
        controller.transportControls.play()
        check("resume: play with nothing loaded resumes the newest Continue item",
            waitState(controller, PlaybackState.STATE_PLAYING, 120_000) { title(it) != null } && mirror()?.artifactId == artifactId, controller)
        mark("resumed", describe(controller))
        stopAll(controller)
        browser.disconnect()
    }

    @Test fun resumptionWithoutRecentsIsAClearError() {
        setOrigin(origin)
        instrumentation.runOnMainSync { app.recents.clear() }
        val browser = connect()
        val controller = controller(browser)
        // The session shows the reason as an error state until something plays.
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                state?.errorMessage?.let { errors += it.toString() }
            }
        }
        instrumentation.runOnMainSync { controller.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper())) }
        controller.transportControls.play()
        SystemClock.sleep(4_000)
        instrumentation.runOnMainSync { controller.unregisterCallback(callback) }
        val state = controller.playbackState
        log("INFO no recents: state=${state?.state} errors=$errors")
        check("resume: no recents does not start anything", mirror() == null && state?.state != PlaybackState.STATE_PLAYING)
        check("resume: no recents shows an error state with a message",
            errors.any { it.contains("Nothing to resume") } && state?.state == PlaybackState.STATE_ERROR, controller)
        mark("no-recents", describe(controller))
        browser.disconnect()
    }
}
