package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.bridge.AudioControl
import ai.agentshelf.artifactmcp.bridge.Protocol
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The playback queue: one ExoPlayer, shared with [PlaybackService]'s media session so Android
 * Auto and the media notification control it. Items are synthesized in order on one lane;
 * geotrigger prefetches use a second lane so they never delay what is about to play.
 */
class AudioQueue(
    private val context: Context,
    private val resolver: ClipResolver,
    private val onState: (artifactId: String, id: String, state: String) -> Unit,
) : AudioControl {
    data class Entry(val artifactId: String, val itemId: String, val title: String, val label: String)

    private data class Request(val artifactId: String, val label: String, val item: Protocol.AudioItem, val generation: Int)

    private val scope = MainScope()
    private val playLane = Channel<Request>(capacity = 200)
    private val prefetchLane = Channel<Pair<String, Protocol.AudioItem>>(capacity = Channel.UNLIMITED)
    private val generations = HashMap<String, Int>()
    private val entries = HashMap<String, Entry>()
    private val announced = HashSet<String>()
    private var currentMediaId: String? = null
    private var sequence = 0L
    private var controller: ListenableFuture<MediaController>? = null

    val player: ExoPlayer by lazy {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // Speech content: ExoPlayer pauses (instead of ducking) for navigation prompts.
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { it.addListener(PlayerEvents()) }
    }

    init {
        scope.launch { for (request in playLane) process(request) }
        scope.launch {
            for ((artifactId, item) in prefetchLane) {
                try {
                    resolver.resolve(artifactId, item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "prefetch failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    override fun enqueue(artifactId: String, label: String, item: Protocol.AudioItem) {
        val request = Request(artifactId, label, item, generations[artifactId] ?: 0)
        if (playLane.trySend(request).isSuccess) {
            onState(artifactId, item.id, "queued")
        } else {
            onState(artifactId, item.id, "error")
        }
    }

    override fun prefetch(artifactId: String, items: List<Protocol.AudioItem>) {
        items.forEach { prefetchLane.trySend(artifactId to it) }
    }

    override fun stop(artifactId: String) {
        generations[artifactId] = (generations[artifactId] ?: 0) + 1
        for (index in player.mediaItemCount - 1 downTo 0) {
            val id = player.getMediaItemAt(index).mediaId
            if (entries[id]?.artifactId == artifactId) {
                player.removeMediaItem(index)
                entries.remove(id)
            }
        }
        if (player.mediaItemCount == 0) player.stop()
    }

    override fun stopAll() {
        generations.keys.toList().forEach { generations[it] = (generations[it] ?: 0) + 1 }
        entries.values.map { it.artifactId }.distinct().forEach { generations[it] = (generations[it] ?: 0) + 1 }
        player.stop()
        player.clearMediaItems()
        entries.clear()
        announced.clear()
        currentMediaId = null
    }

    private suspend fun process(request: Request) {
        if ((generations[request.artifactId] ?: 0) != request.generation) return
        val file = try {
            resolver.resolve(request.artifactId, request.item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "clip failed: ${e.javaClass.simpleName} ${(e as? SpeechException)?.kind}")
            onState(request.artifactId, request.item.id, "error")
            return
        }
        if ((generations[request.artifactId] ?: 0) != request.generation) return
        append(request.artifactId, request.label, request.item, file)
    }

    /** Add a resolved clip to the end of the queue and make sure it plays. */
    fun append(artifactId: String, label: String, item: Protocol.AudioItem, file: File): MediaItem {
        val mediaItem = buildMediaItem(artifactId, label, item, file)
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItem(mediaItem)
        if (wasEnded) player.seekTo(player.mediaItemCount - 1, 0L)
        if (player.playbackState == Player.STATE_IDLE || wasEnded) player.prepare()
        player.play()
        ensureSession()
        return mediaItem
    }

    fun buildMediaItem(artifactId: String, label: String, item: Protocol.AudioItem, file: File): MediaItem {
        val mediaId = "q:${++sequence}"
        entries[mediaId] = Entry(artifactId, item.id, item.title, label)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(label)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }

    /** Current playlist, for the media browser. */
    fun queueItems(): List<MediaItem> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

    suspend fun resolve(artifactId: String, item: Protocol.AudioItem): File = resolver.resolve(artifactId, item)

    /** Bind the media session service so the session exists for Android Auto and the notification. */
    private fun ensureSession() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    fun clearCache() = resolver.clear()

    private fun finish(mediaId: String?, state: String) {
        val entry = mediaId?.let { entries[it] } ?: return
        onState(entry.artifactId, entry.itemId, state)
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val id = player.currentMediaItem?.mediaId ?: return
            if (isPlaying && announced.add(id)) finish(id, "playing")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = currentMediaId
            currentMediaId = mediaItem?.mediaId
            if (previous != null && previous != currentMediaId && previous in entries) {
                finish(previous, "done")
                dropBeforeCurrent()
            }
            if (player.isPlaying) mediaItem?.mediaId?.let { if (announced.add(it)) finish(it, "playing") }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                finish(currentMediaId, "done")
                currentMediaId = null
                player.clearMediaItems()
                entries.clear()
                announced.clear()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val index = player.currentMediaItemIndex
            finish(player.currentMediaItem?.mediaId, "error")
            if (index in 0 until player.mediaItemCount) {
                entries.remove(player.getMediaItemAt(index).mediaId)
                player.removeMediaItem(index)
            }
            if (player.mediaItemCount > 0) {
                player.prepare()
                player.play()
            }
        }
    }

    /** Keep the queue showing only the current and upcoming items. */
    private fun dropBeforeCurrent() {
        val current = player.currentMediaItemIndex
        for (index in current - 1 downTo 0) {
            val id = player.getMediaItemAt(index).mediaId
            entries.remove(id)
            announced.remove(id)
            player.removeMediaItem(index)
        }
    }

    private companion object {
        const val TAG = "AudioQueue"
    }
}
