package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * What the native media session shows for the viewer's Listen player (ADR-0019).
 *
 * [Phase.LOADING] is set by the app while a library pick opens the page and before the page
 * reports its first `media.session`. [Phase.FAILED] is shown when that does not happen.
 */
data class MirrorState(
    val artifactId: String,
    val phase: Phase,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val actions: Set<MediaAction> = emptySet(),
    val error: String? = null,
    /** Listen speed from `media.session.rate` ("1", "1.25"), when the page reports it. */
    val rate: String? = null,
) {
    enum class Phase { LOADING, PLAYING, PAUSED, FAILED }

    /**
     * Whether the page takes [action]. `rewind` and `speed` also count as offered when the page
     * reports a `rate`: a shell that sends `rate` (ADR-0021) handles both, even though its
     * `actions` list still names only the original five.
     */
    fun supports(action: MediaAction): Boolean =
        action in actions || (rate != null && (action == MediaAction.REWIND || action == MediaAction.SPEED))
}

/** Maps [MirrorState] to Media3 player state. Kept apart from the player so it can be tested. */
@OptIn(UnstableApi::class)
object MirrorMapping {
    fun mediaId(artifactId: String) = "art:$artifactId"

    /** Commands a controller (car, notification, headset) may send for [mirror]. */
    fun commands(mirror: MirrorState?): Player.Commands {
        val builder = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
            // A library pick replaces the item: the session forwards it here, the player ignores it.
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_PREPARE,
        )
        if (mirror == null) return builder.build()
        val actions = mirror.actions
        when (mirror.phase) {
            MirrorState.Phase.LOADING -> builder.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP)
            MirrorState.Phase.FAILED -> builder.add(Player.COMMAND_STOP)
            MirrorState.Phase.PLAYING, MirrorState.Phase.PAUSED -> {
                if (MediaAction.PLAY in actions || MediaAction.PAUSE in actions) builder.add(Player.COMMAND_PLAY_PAUSE)
                if (MediaAction.STOP in actions) builder.add(Player.COMMAND_STOP)
                if (MediaAction.NEXT in actions) builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                if (MediaAction.PREVIOUS in actions) {
                    builder.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
            }
        }
        return builder.build()
    }

    /**
     * Now playing: the title is the current chapter or section, the subtitle the artifact
     * (the library title when known: the page's `album` is its document title, which can carry
     * the site name), the artist line the voice. [artwork] is the artifact's cover.
     */
    fun metadata(mirror: MirrorState, artwork: Uri? = null, artifactTitle: String? = null): MediaMetadata {
        val title = mirror.title.ifBlank { "Listen" }
        val artifact = artifactTitle?.ifBlank { null } ?: mirror.album.ifBlank { null }
        // At the start the section is the artifact's own heading: do not repeat it below.
        val subtitle = artifact?.takeUnless { it.trim().equals(title.trim(), ignoreCase = true) }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtist(mirror.artist.ifBlank { null })
            .setAlbumTitle(artifact)
            .setArtworkUri(artwork)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .build()
    }

    fun state(mirror: MirrorState?, artwork: Uri? = null, artifactTitle: String? = null): SimpleBasePlayer.State {
        val builder = SimpleBasePlayer.State.Builder().setAvailableCommands(commands(mirror))
        if (mirror == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        val metadata = metadata(mirror, artwork, artifactTitle)
        val item = SimpleBasePlayer.MediaItemData.Builder(mediaId(mirror.artifactId))
            .setMediaItem(MediaItem.Builder().setMediaId(mediaId(mirror.artifactId)).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .build()
        builder.setPlaylist(listOf(item)).setCurrentMediaItemIndex(0)
        val reason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
        return when (mirror.phase) {
            MirrorState.Phase.LOADING -> builder.setPlaybackState(Player.STATE_BUFFERING).setPlayWhenReady(true, reason)
            MirrorState.Phase.PLAYING -> builder.setPlaybackState(Player.STATE_READY).setPlayWhenReady(true, reason)
            MirrorState.Phase.PAUSED -> builder.setPlaybackState(Player.STATE_READY).setPlayWhenReady(false, reason)
            MirrorState.Phase.FAILED -> builder.setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, reason)
                .setPlayerError(PlaybackException(mirror.error ?: "Listen did not start", null, PlaybackException.ERROR_CODE_TIMEOUT))
        }.build()
    }

    /** The Listen action a seek command stands for, or null (seeking within a clip is not offered). */
    fun seekAction(seekCommand: Int): MediaAction? = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> MediaAction.NEXT
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MediaAction.PREVIOUS
        else -> null
    }
}

/**
 * A player without audio of its own: its state comes from the page's `media.session` and its
 * transport controls become `media.command` messages ([onCommand]). The media session uses it
 * instead of the ExoPlayer queue while Listen is the active source.
 */
@OptIn(UnstableApi::class)
class MirrorPlayer(looper: Looper, private val onCommand: (MediaAction) -> Unit) : SimpleBasePlayer(looper) {
    var mirror: MirrorState? = null
        private set

    /** Cover of the playing artifact (set by the app). */
    var artwork: (MirrorState) -> Uri? = { null }

    /** Library title of the playing artifact, when known (set by the app). */
    var artifactTitle: (MirrorState) -> String? = { null }

    fun update(next: MirrorState?) {
        mirror = next
        invalidateState()
    }

    override fun getState(): State {
        val current = mirror ?: return MirrorMapping.state(null)
        return MirrorMapping.state(
            current,
            runCatching { artwork(current) }.getOrNull(),
            runCatching { artifactTitle(current) }.getOrNull(),
        )
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onCommand(if (playWhenReady) MediaAction.PLAY else MediaAction.PAUSE)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        onCommand(MediaAction.STOP)
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        MirrorMapping.seekAction(seekCommand)?.let(onCommand)
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> = done()

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> = done()

    override fun handlePrepare(): ListenableFuture<*> = done()

    override fun handleRelease(): ListenableFuture<*> = done()
}
