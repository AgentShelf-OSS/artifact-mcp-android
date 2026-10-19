package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MirrorPlayerTest {
    private val sent = mutableListOf<MediaAction>()
    private val chapters = mutableListOf<Int>()
    private val player = MirrorPlayer(Looper.getMainLooper(), onChapter = { chapters += it }) { sent += it }

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
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT)
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

    // ---- ADR-0022 -------------------------------------------------------------------------

    private val chapterList = listOf("Letter 1", "Chapter 1", "Chapter 2", "Chapter 3")

    private fun progress(
        phase: MirrorState.Phase = MirrorState.Phase.PLAYING,
        chapterIndex: Int? = 1,
        positionS: Double? = 10.0,
        durationS: Double? = 120.0,
        at: Long = 1_000L,
        rate: String? = "1",
    ) = state(phase).copy(
        chapters = chapterList, chapterIndex = chapterIndex, positionS = positionS, durationS = durationS,
        positionAtMs = at, rate = rate,
    )

    @Test fun `buffering shows buffering with play when ready and keeps the controls`() {
        player.update(state(MirrorState.Phase.BUFFERING))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.playWhenReady).isTrue()
        assertThat(player.isPlaying).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        player.pause()
        idle()
        assertThat(sent).containsExactly(MediaAction.PAUSE)
    }

    @Test fun `chapters become a queue with the current chapter selected`() {
        player.update(progress())
        idle()
        assertThat(player.mediaItemCount).isEqualTo(4)
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.mediaMetadata.title.toString()).isEqualTo("Chapter 1")
        assertThat(player.getMediaItemAt(2).mediaMetadata.title.toString()).isEqualTo("Chapter 2")
        assertThat(player.getMediaItemAt(0).mediaMetadata.subtitle.toString()).isEqualTo("Frankenstein")
        assertThat(player.mediaMetadata.trackNumber).isEqualTo(2)
        assertThat(player.mediaMetadata.totalTrackCount).isEqualTo(4)
        assertThat(player.currentMediaItem?.mediaId).isEqualTo("art:book")
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)).isFalse()
        assertThat(player.isCurrentMediaItemSeekable).isFalse()
    }

    @Test fun `queue picks and next and previous send chapter commands`() {
        player.update(progress(phase = MirrorState.Phase.PAUSED, positionS = 1.0))
        idle()
        player.seekToDefaultPosition(3)
        idle()
        player.seekToNextMediaItem()
        idle()
        player.seekToPrevious() // 1 s in: the previous chapter
        idle()
        assertThat(chapters).containsExactly(3, 2, 0).inOrder()
        assertThat(sent).isEmpty()
    }

    @Test fun `previous late in a chapter restarts it and next on the last chapter falls back to the page`() {
        player.update(progress(chapterIndex = 3, positionS = 60.0))
        idle()
        player.seekToPrevious()
        idle()
        player.seekToNext()
        idle()
        assertThat(chapters).containsExactly(3)
        assertThat(sent).containsExactly(MediaAction.NEXT)
    }

    @Test fun `without the chapter action next and previous stay the page's own`() {
        player.update(progress().copy(actions = MediaAction.entries.toSet() - MediaAction.CHAPTER))
        idle()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)).isFalse()
        player.seekToNext()
        idle()
        assertThat(sent).containsExactly(MediaAction.NEXT)
        assertThat(chapters).isEmpty()
    }

    @Test fun `no chapter index means a single item`() {
        player.update(progress(chapterIndex = null))
        idle()
        assertThat(player.mediaItemCount).isEqualTo(1)
        player.update(progress(chapterIndex = 9))
        idle()
        assertThat(player.mediaItemCount).isEqualTo(1)
    }

    @Test fun `duration and an interpolated position are exposed`() {
        player.update(progress(chapterIndex = null, positionS = 10.0, durationS = 120.0, at = android.os.SystemClock.elapsedRealtime(), rate = "1.5"))
        idle()
        assertThat(player.duration).isEqualTo(120_000L)
        val start = player.currentPosition
        assertThat(start).isAtLeast(10_000L)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        // 4 s at 1.5x: 6 s further.
        assertThat(player.currentPosition).isAtLeast(15_900L)
        assertThat(player.playbackParameters.speed).isEqualTo(1.5f)
    }

    @Test fun `position interpolation is clamped and frozen when not playing`() {
        val playing = progress(positionS = 100.0, durationS = 120.0, at = 0L, rate = "2")
        assertThat(playing.positionMsAt(5_000L)).isEqualTo(110_000L)
        assertThat(playing.positionMsAt(60_000L)).isEqualTo(120_000L)
        assertThat(playing.copy(phase = MirrorState.Phase.PAUSED).positionMsAt(60_000L)).isEqualTo(100_000L)
        assertThat(playing.copy(phase = MirrorState.Phase.BUFFERING).positionMsAt(60_000L)).isEqualTo(100_000L)
        assertThat(playing.copy(positionS = null).positionMsAt(5_000L)).isNull()
        assertThat(playing.copy(durationS = null).positionMsAt(5_000L)).isEqualTo(110_000L)
    }

    @Test fun `played fraction uses chapters, else position over duration`() {
        val p = progress(phase = MirrorState.Phase.PAUSED, chapterIndex = 2, positionS = 60.0, durationS = 120.0)
        assertThat(p.playedFraction(0L)).isWithin(1e-9).of((2 + 0.5) / 4)
        assertThat(p.copy(durationS = null).playedFraction(0L)).isWithin(1e-9).of(0.5)
        assertThat(p.copy(chapters = emptyList()).playedFraction(0L)).isWithin(1e-9).of(0.5)
        assertThat(p.copy(chapters = emptyList(), durationS = null).playedFraction(0L)).isNull()
    }

    @Test fun `each failure has its own error code and a paused page error is shown`() {
        val codes = MirrorState.Failure.entries.associateWith { MirrorMapping.errorCode(it) }
        assertThat(codes.values.toSet()).hasSize(MirrorState.Failure.entries.size)
        assertThat(codes[MirrorState.Failure.SIGN_IN]).isEqualTo(PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED)
        assertThat(codes[MirrorState.Failure.OFFLINE]).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertThat(codes[MirrorState.Failure.NOT_FOUND]).isEqualTo(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertThat(codes[MirrorState.Failure.PAGE]).isEqualTo(PlaybackException.ERROR_CODE_REMOTE_ERROR)

        val extras = android.os.Bundle().apply { putString("label", "Open on phone") }
        player.errorExtras = { if (it.failure == MirrorState.Failure.SIGN_IN) extras else null }
        player.update(MirrorState("book", MirrorState.Phase.FAILED, "Book", error = "Open the app on your phone to sign in", failure = MirrorState.Failure.SIGN_IN))
        idle()
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED)
        assertThat(player.playerError?.extras?.getString("label")).isEqualTo("Open on phone")
        // Play retries after a failure.
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()

        player.update(state(MirrorState.Phase.PAUSED).copy(error = "Speech is unavailable", failure = MirrorState.Failure.PAGE))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.message).isEqualTo("Speech is unavailable")
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_REMOTE_ERROR)
        player.play()
        idle()
        assertThat(sent).contains(MediaAction.PLAY)
    }
}
