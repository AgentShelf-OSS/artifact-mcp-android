package ai.agentshelf.artifactmcp

import ai.agentshelf.artifactmcp.audio.PlaybackService
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prepares the app for the Android Automotive screenshots (build/emulator/test-car.sh): sets the
 * server origin and clears the library cache and the Continue list. Arguments: `origin`, and
 * `keepRecents=1` to keep the Continue list. [playFromSearch] checks the voice path
 * ("Play <title> on Artifact MCP"): arguments `query` and `expect` (the artifact title).
 */
@RunWith(AndroidJUnit4::class)
class CarSetup {
    @Test fun configure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ArtifactApp
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("origin") ?: error("origin argument is required")
        instrumentation.runOnMainSync {
            app.originStore.origin = origin
            app.library.clear()
            if (args.getString("keepRecents") != "1") app.recents.clear()
        }
        // SharedPreferences writes are asynchronous; the process ends with the instrumentation.
        SystemClock.sleep(1_500)
    }

    @Test fun playFromSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val query = args.getString("query") ?: error("query argument is required")
        val expect = args.getString("expect") ?: error("expect argument is required")
        val connected = CountDownLatch(1)
        lateinit var browser: MediaBrowser
        instrumentation.runOnMainSync {
            browser = MediaBrowser(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() = connected.countDown()
            }, null)
            browser.connect()
        }
        assertThat(connected.await(30, TimeUnit.SECONDS)).isTrue()
        lateinit var controller: MediaController
        instrumentation.runOnMainSync { controller = MediaController(context, browser.sessionToken) }
        controller.transportControls.playFromSearch(query, Bundle())
        // The mirror is read in-process: this controller's cached state can lag on AAOS.
        val app = context.applicationContext as ArtifactApp
        val end = SystemClock.elapsedRealtime() + 120_000
        var mirror: ai.agentshelf.artifactmcp.listen.MirrorState? = null
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.runOnMainSync { mirror = app.listen.mirror }
            if (mirror?.phase == ai.agentshelf.artifactmcp.listen.MirrorState.Phase.PLAYING && mirror?.album?.startsWith(expect) == true) break
            SystemClock.sleep(500)
        }
        Log.i("CarSetup", "playFromSearch '$query': ${mirror?.phase} album=${mirror?.album} section=${mirror?.title}")
        // `album` is the page's document title ("<title> · <site>").
        assertThat(mirror?.album).startsWith(expect)
        assertThat(mirror?.phase).isEqualTo(ai.agentshelf.artifactmcp.listen.MirrorState.Phase.PLAYING)
        browser.disconnect()
    }
}
