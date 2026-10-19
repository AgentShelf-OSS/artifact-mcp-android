package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import android.os.Looper
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MirrorPlayerTest {
    private val sent = mutableListOf<MediaAction>()
    private val player = MirrorPlayer(Looper.getMainLooper()) { sent += it }

    private fun state(phase: MirrorState.Phase, actions: Set<MediaAction> = MediaAction.entries.toSet()) =
        MirrorState("book", phase, "Chapter 1", "Ava", "Frankenstein", actions)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `no session is an idle empty player`() {
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.mediaItemCount).isEqualTo(0)
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isFalse()
    }

    @Test fun `playing maps to ready and play when ready with metadata`() {
        player.update(state(MirrorState.Phase.PLAYING))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.playWhenReady).isTrue()
        assertThat(player.isPlaying).isTrue()
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("Chapter 1")
        assertThat(player.mediaMetadata.artist.toString()).isEqualTo("Ava")
        assertThat(player.mediaMetadata.albumTitle.toString()).isEqualTo("Frankenstein")
        assertThat(player.currentMediaItem?.mediaId).isEqualTo("art:book")
    }

    @Test fun `now playing shows the section, the artifact below it, the voice and the cover`() {
        val cover = android.net.Uri.parse("content://ai.agentshelf.artifactmcp.covers/a/142235/eA/-")
        player.artwork = { if (it.artifactId == "book") cover else null }
        player.update(state(MirrorState.Phase.PLAYING).copy(rate = "1.25"))
        idle()
        val m = player.mediaMetadata
        assertThat(m.displayTitle.toString()).isEqualTo("Chapter 1")
        assertThat(m.subtitle.toString()).isEqualTo("Frankenstein")
        assertThat(m.artist.toString()).isEqualTo("Ava")
        assertThat(m.artworkUri).isEqualTo(cover)
        assertThat(player.mirror?.rate).isEqualTo("1.25")

        // The library title replaces the page's document title ("Frankenstein · Artifact Index").
        player.artifactTitle = { "Frankenstein, read aloud" }
        player.update(state(MirrorState.Phase.PLAYING).copy(album = "Frankenstein · Artifact Index"))
        idle()
        assertThat(player.mediaMetadata.subtitle.toString()).isEqualTo("Frankenstein, read aloud")

        // On the artifact's own heading the subtitle is not repeated.
        player.update(state(MirrorState.Phase.PLAYING).copy(title = "Frankenstein, read aloud"))
        idle()
        assertThat(player.mediaMetadata.subtitle).isNull()
    }

    @Test fun `paused maps to ready without play when ready`() {
        player.update(state(MirrorState.Phase.PAUSED))
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.playWhenReady).isFalse()
    }

    @Test fun `loading buffers and failure shows an error`() {
        player.update(MirrorState("book", MirrorState.Phase.LOADING, "Frankenstein"))
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.playWhenReady).isTrue()
        player.update(MirrorState("book", MirrorState.Phase.FAILED, "Frankenstein", error = "Open the app"))
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.message).isEqualTo("Open the app")
    }

    @Test fun `available commands follow the page's actions`() {
        player.update(state(MirrorState.Phase.PLAYING, setOf(MediaAction.PAUSE)))
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_STOP)).isFalse()
        player.update(state(MirrorState.Phase.PLAYING))
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_STOP)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)).isFalse()
    }

    @Test fun `transport controls become media commands`() {
        player.update(state(MirrorState.Phase.PLAYING))
        player.pause()
        player.seekToNext()
        player.seekToPrevious()
        player.stop()
        idle()
        player.update(state(MirrorState.Phase.PAUSED))
        player.play()
        idle()
        assertThat(sent).containsExactly(MediaAction.PAUSE, MediaAction.NEXT, MediaAction.PREVIOUS, MediaAction.STOP, MediaAction.PLAY).inOrder()
    }

    @Test fun `unavailable controls are not forwarded`() {
        player.update(state(MirrorState.Phase.PLAYING, setOf(MediaAction.PAUSE)))
        player.seekToNext()
        player.stop()
        idle()
        assertThat(sent).isEmpty()
    }

    @Test fun `setting media items is accepted without a command`() {
        player.update(MirrorState("book", MirrorState.Phase.LOADING, "Frankenstein"))
        player.setMediaItems(listOf(androidx.media3.common.MediaItem.Builder().setMediaId("art:book").build()))
        player.prepare()
        idle()
        assertThat(sent).isEmpty()
        assertThat(player.currentMediaItem?.mediaId).isEqualTo("art:book")
    }
}
