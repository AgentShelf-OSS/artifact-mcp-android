package ai.agentshelf.artifactmcp.listen

import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * What the native media session shows for the viewer's Listen player (ADR-0019).
 *
 * [Phase.LOADING] is set by the app while a library pick opens the page and before the page
 * reports its first `media.session`. [Phase.FAILED] is shown when that does not happen, or when
 * the page could not be opened ([failure] says why). [Phase.BUFFERING] is the page's `buffering`
 * state (ADR-0022: loading, reconnecting or restoring the saved place). A [Phase.PAUSED] state
 * with an [error] is Listen stopped on a failure that the page reported.
 *
 * Progress (ADR-0022, `media.progress`): [positionS] and [durationS] are the page's estimate in
 * seconds for the current chapter (or the whole reading scope), reported at [positionAtMs]
 * (elapsed realtime); [chapterIndex] indexes [chapters].
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
    /** Kind of [error], for the error code that cars and notifications show. */
    val failure: Failure = Failure.NOT_STARTED,
    val positionS: Double? = null,
    val durationS: Double? = null,
    val positionAtMs: Long = 0L,
    val chapterIndex: Int? = null,
    val chapters: List<String> = emptyList(),
) {
    enum class Phase { LOADING, PLAYING, PAUSED, BUFFERING, FAILED }

    /** Why Listen is not playing. Each kind maps to its own [PlaybackException] error code. */
    enum class Failure {
        /** No Access session: the reader was sent to the login page, or the server said 401/403. */
        SIGN_IN,

        /** No network, or the page could not be reached. */
        OFFLINE,

        /** The artifact does not exist (any more): HTTP 404 or 410. */
        NOT_FOUND,

        /** Another HTTP error from the server. */
        SERVER,

        /** The page reported why Listen stopped (`media.session.error`), for example no speech. */
        PAGE,

        /** The page opened but Listen did not start in time. */
        NOT_STARTED,
    }

    /**
     * Whether the page takes [action]. `rewind` and `speed` also count as offered when the page
     * reports a `rate`: a shell that sends `rate` (ADR-0021) handles both, even though its
     * `actions` list still names only the original five.
     */
    fun supports(action: MediaAction): Boolean =
        action in actions || (rate != null && (action == MediaAction.REWIND || action == MediaAction.SPEED))

    /** True when the queue shows one item per chapter: chapters are known and one is current. */
    val hasChapterQueue: Boolean get() = chapters.isNotEmpty() && chapterIndex != null && chapterIndex in chapters.indices

    /** Listen speed as a number (1 when unknown or malformed). */
    val speed: Float get() = rate?.toFloatOrNull()?.takeIf { it > 0f && it < 100f } ?: 1f

    /**
     * Position in milliseconds at [nowMs] (elapsed realtime): while playing it advances from the
     * reported value by elapsed time times the speed, at most to the duration. Null when the page
     * reports no position.
     */
    fun positionMsAt(nowMs: Long): Long? {
        val base = positionS ?: return null
        val advanced = if (phase == Phase.PLAYING) base * 1000.0 + (nowMs - positionAtMs).coerceAtLeast(0L) * speed else base * 1000.0
        val limit = durationS?.let { it * 1000.0 } ?: Double.MAX_VALUE
        return advanced.coerceIn(0.0, limit).toLong()
    }

    /**
     * Played share of the artifact (0..1) for the Continue tab: with chapters, the current
     * chapter's index plus the share of it played, over the chapter count; without chapters,
     * position over duration. Null when unknown.
     */
    fun playedFraction(nowMs: Long): Double? {
        val within = durationS?.takeIf { it > 0 }?.let { d -> positionMsAt(nowMs)?.let { (it / 1000.0 / d).coerceIn(0.0, 1.0) } }
        if (hasChapterQueue) return ((chapterIndex!! + (within ?: 0.0)) / chapters.size).coerceIn(0.0, 1.0)
        return within
    }
}

/** A transport control for the page: a plain action, or "start reading chapter [index]". */
sealed interface MirrorCommand {
    data class Action(val action: MediaAction) : MirrorCommand
    data class Chapter(val index: Int) : MirrorCommand
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
            // Play retries the pick (for example once the connection is back).
            MirrorState.Phase.FAILED -> builder.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP)
            MirrorState.Phase.PLAYING, MirrorState.Phase.PAUSED, MirrorState.Phase.BUFFERING -> {
                if (MediaAction.PLAY in actions || MediaAction.PAUSE in actions) builder.add(Player.COMMAND_PLAY_PAUSE)
                if (MediaAction.STOP in actions) builder.add(Player.COMMAND_STOP)
                if (MediaAction.NEXT in actions) builder.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                if (MediaAction.PREVIOUS in actions) {
                    builder.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                if (MediaAction.CHAPTER in actions && mirror.hasChapterQueue) {
                    // The car's queue and its next/previous buttons move by chapter.
                    builder.addAll(
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    )
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
            .apply {
                if (mirror.hasChapterQueue) {
                    setTrackNumber(mirror.chapterIndex!! + 1)
                    setTotalTrackCount(mirror.chapters.size)
                }
            }
            .build()
    }

    /** Queue entry of a chapter other than the current one. */
    private fun chapterMetadata(mirror: MirrorState, index: Int, artwork: Uri?, artifactTitle: String?): MediaMetadata {
        val title = mirror.chapters[index].ifBlank { "Chapter ${index + 1}" }
        val artifact = artifactTitle?.ifBlank { null } ?: mirror.album.ifBlank { null }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(artifact)
            .setAlbumTitle(artifact)
            .setArtworkUri(artwork)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setTrackNumber(index + 1)
            .setTotalTrackCount(mirror.chapters.size)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .build()
    }

    /** The [PlaybackException] error code of a failure kind. */
    fun errorCode(failure: MirrorState.Failure): Int = when (failure) {
        MirrorState.Failure.SIGN_IN -> PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED
        MirrorState.Failure.OFFLINE -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        MirrorState.Failure.NOT_FOUND -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        MirrorState.Failure.SERVER -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        MirrorState.Failure.PAGE -> PlaybackException.ERROR_CODE_REMOTE_ERROR
        MirrorState.Failure.NOT_STARTED -> PlaybackException.ERROR_CODE_TIMEOUT
    }

    /** The error to show for [mirror], or null. [extras] carry an optional resolution intent. */
    fun playerError(mirror: MirrorState, extras: Bundle? = null): PlaybackException? {
        val failed = mirror.phase == MirrorState.Phase.FAILED
        if (!failed && !(mirror.phase == MirrorState.Phase.PAUSED && mirror.error != null)) return null
        val failure = if (failed) mirror.failure else MirrorState.Failure.PAGE
        return PlaybackException(mirror.error ?: "Listen did not start", null, errorCode(failure), extras ?: Bundle.EMPTY)
    }

    fun state(
        mirror: MirrorState?,
        artwork: Uri? = null,
        artifactTitle: String? = null,
        errorExtras: Bundle? = null,
        clock: () -> Long = SystemClock::elapsedRealtime,
    ): SimpleBasePlayer.State {
        val builder = SimpleBasePlayer.State.Builder().setAvailableCommands(commands(mirror))
        if (mirror == null) {
            return builder.setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        }
        val metadata = metadata(mirror, artwork, artifactTitle)
        val durationUs = mirror.durationS?.let { (it * 1_000_000).toLong() } ?: C.TIME_UNSET
        val current = SimpleBasePlayer.MediaItemData.Builder(mediaId(mirror.artifactId))
            .setMediaItem(MediaItem.Builder().setMediaId(mediaId(mirror.artifactId)).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setDurationUs(durationUs)
        if (mirror.hasChapterQueue) {
            // One item per chapter, so the car shows a queue; picking one sends `chapter`.
            val index = mirror.chapterIndex!!
            val items = mirror.chapters.indices.map { i ->
                if (i == index) {
                    current.setUid(chapterUid(mirror.artifactId, i)).build()
                } else {
                    val m = chapterMetadata(mirror, i, artwork, artifactTitle)
                    SimpleBasePlayer.MediaItemData.Builder(chapterUid(mirror.artifactId, i))
                        .setMediaItem(MediaItem.Builder().setMediaId(mediaId(mirror.artifactId)).setMediaMetadata(m).build())
                        .setMediaMetadata(m)
                        .setIsSeekable(false)
                        .build()
                }
            }
            builder.setPlaylist(items).setCurrentMediaItemIndex(index)
        } else {
            builder.setPlaylist(listOf(current.build())).setCurrentMediaItemIndex(0)
        }
        if (mirror.positionS != null) {
            builder.setContentPositionMs { mirror.positionMsAt(clock()) ?: 0L }
        }
        mirror.rate?.let { builder.setPlaybackParameters(PlaybackParameters(mirror.speed)) }
        val reason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
        val error = playerError(mirror, errorExtras)
        if (error != null) {
            // Listen stopped on a failure: an error state; play retries (FAILED) or resumes (PAUSED).
            return builder.setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, reason).setPlayerError(error).build()
        }
        return when (mirror.phase) {
            MirrorState.Phase.LOADING, MirrorState.Phase.BUFFERING -> builder.setPlaybackState(Player.STATE_BUFFERING).setPlayWhenReady(true, reason)
            MirrorState.Phase.PLAYING -> builder.setPlaybackState(Player.STATE_READY).setPlayWhenReady(true, reason)
            MirrorState.Phase.PAUSED -> builder.setPlaybackState(Player.STATE_READY).setPlayWhenReady(false, reason)
            MirrorState.Phase.FAILED -> builder.setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(false, reason)
        }.build()
    }

    private fun chapterUid(artifactId: String, index: Int) = "${mediaId(artifactId)}#$index"

    /** The Listen action a seek command stands for, or null (seeking within a clip is not offered). */
    fun seekAction(seekCommand: Int): MediaAction? = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> MediaAction.NEXT
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MediaAction.PREVIOUS
        else -> null
    }

    /**
     * The page command for a seek to [mediaItemIndex] ([C.INDEX_UNSET] when Media3 found no
     * target). With a chapter queue every seek with a target is a chapter jump (queue pick,
     * next/previous chapter, previous to the start of the chapter); otherwise next/previous are
     * the page's own `next`/`previous`.
     */
    fun seekCommand(mirror: MirrorState?, mediaItemIndex: Int, seekCommand: Int): MirrorCommand? {
        if (mirror != null && mirror.hasChapterQueue && MediaAction.CHAPTER in mirror.actions &&
            mediaItemIndex != C.INDEX_UNSET && mediaItemIndex in mirror.chapters.indices
        ) {
            return MirrorCommand.Chapter(mediaItemIndex)
        }
        return seekAction(seekCommand)?.let { MirrorCommand.Action(it) }
    }
}

/**
 * A player without audio of its own: its state comes from the page's `media.session` and its
 * transport controls become `media.command` messages ([onCommand]). The media session uses it
 * instead of the ExoPlayer queue while Listen is the active source.
 */
@OptIn(UnstableApi::class)
class MirrorPlayer(
    looper: Looper,
    private val onChapter: (Int) -> Unit = {},
    private val onCommand: (MediaAction) -> Unit,
) : SimpleBasePlayer(looper) {
    var mirror: MirrorState? = null
        private set

    /** Cover of the playing artifact (set by the app). */
    var artwork: (MirrorState) -> Uri? = { null }

    /** Library title of the playing artifact, when known (set by the app). */
    var artifactTitle: (MirrorState) -> String? = { null }

    /** Extras of the error state, for example a sign-in resolution intent (set by the app). */
    var errorExtras: (MirrorState) -> Bundle? = { null }

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
            runCatching { errorExtras(current) }.getOrNull(),
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
        when (val command = MirrorMapping.seekCommand(mirror, mediaItemIndex, seekCommand)) {
            is MirrorCommand.Chapter -> onChapter(command.index)
            is MirrorCommand.Action -> onCommand(command.action)
            null -> Unit
        }
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> = done()

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> = done()

    override fun handlePrepare(): ListenableFuture<*> = done()

    override fun handleRelease(): ListenableFuture<*> = done()
}
