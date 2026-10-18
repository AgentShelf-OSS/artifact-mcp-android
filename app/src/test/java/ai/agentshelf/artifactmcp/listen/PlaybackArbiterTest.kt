package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.listen.PlaybackArbiter.Source
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackArbiterTest {
    private val events = mutableListOf<String>()
    private val arbiter = PlaybackArbiter({ events += "pause-native" }, { events += "pause-mirror" }, { events += "active-$it" })

    @Test fun `native is active by default`() {
        assertThat(arbiter.active).isEqualTo(Source.NATIVE)
    }

    @Test fun `last active wins and pauses the other`() {
        arbiter.mirrorStarted()
        assertThat(arbiter.active).isEqualTo(Source.MIRROR)
        arbiter.nativeStarted()
        assertThat(arbiter.active).isEqualTo(Source.NATIVE)
        assertThat(events).containsExactly("pause-native", "active-MIRROR", "pause-mirror", "active-NATIVE").inOrder()
    }

    @Test fun `native start while native is active changes nothing`() {
        arbiter.nativeStarted()
        assertThat(events).isEmpty()
    }

    @Test fun `mirror end hands the session back to native without pausing it`() {
        arbiter.mirrorStarted()
        events.clear()
        arbiter.mirrorEnded()
        assertThat(events).containsExactly("active-NATIVE")
    }

    @Test fun `picking a native item pauses Listen`() {
        arbiter.mirrorStarted()
        events.clear()
        arbiter.nativeSelected()
        assertThat(events).containsExactly("pause-mirror", "active-NATIVE").inOrder()
    }

    @Test fun `pause policy keeps the page running while Listen plays or location is used`() {
        assertThat(WebViewPausePolicy.pauseView(visible = false, listenPlayingHere = false, locationInUse = false)).isTrue()
        assertThat(WebViewPausePolicy.pauseView(visible = false, listenPlayingHere = true, locationInUse = false)).isFalse()
        assertThat(WebViewPausePolicy.pauseView(visible = false, listenPlayingHere = false, locationInUse = true)).isFalse()
        assertThat(WebViewPausePolicy.pauseView(visible = true, listenPlayingHere = false, locationInUse = false)).isFalse()
        assertThat(WebViewPausePolicy.pauseTimers(visible = false, listenPlaying = false, readerAlive = true, locationInUse = false)).isFalse()
        assertThat(WebViewPausePolicy.pauseTimers(visible = false, listenPlaying = true, readerAlive = false, locationInUse = false)).isFalse()
        assertThat(WebViewPausePolicy.pauseTimers(visible = false, listenPlaying = false, readerAlive = false, locationInUse = false)).isTrue()
    }
}
