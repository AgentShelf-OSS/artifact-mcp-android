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
 * host script when to take screenshots; the test then waits [SHOT_MS].
 */
@RunWith(AndroidJUnit4::class)
class CarLibraryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as ArtifactApp
    private val args = InstrumentationRegistry.getArguments()
    private val origin = args.getString("origin") ?: "http://localhost:8480"
    private val artifactId = args.getString("artifactId") ?: error("artifactId argument is required")

    private companion object {
        const val TAG = "CarLibraryTest"
        const val SHOT_MS = 9_000L
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
}
