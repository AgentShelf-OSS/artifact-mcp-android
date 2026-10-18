package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.ui.MainActivity
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Who may control playback through the exported media service.
 *
 * Media3 identifies callers by package and uid. Accepted: this app, the system media
 * notification controller, Android Auto (phone projection, `gearhead`), Android Automotive
 * controllers, and the system UI. Everything else is rejected in `onConnect`.
 */
object ControllerPolicy {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private val TRUSTED_SYSTEM_PACKAGES = setOf(ANDROID_AUTO_PACKAGE, "com.android.systemui")

    fun allow(
        packageName: String,
        ownPackage: String,
        isMediaNotificationController: Boolean,
        isAutoCompanionController: Boolean,
        isAutomotiveController: Boolean,
    ): Boolean = packageName == ownPackage ||
        isMediaNotificationController ||
        isAutoCompanionController ||
        isAutomotiveController ||
        packageName in TRUSTED_SYSTEM_PACKAGES
}

/** Browse tree ids. Queue entries use the player's own `q:` media ids. */
object BrowseTree {
    const val ROOT = "root"
    const val QUEUE = "queue"
    const val TRIGGERS = "triggers"

    fun triggerId(artifactId: String, itemId: String) = "t:$artifactId:${itemId.length}:$itemId"

    /** Inverse of [triggerId]; the item id may contain ':' so its length is encoded. */
    fun parseTriggerId(mediaId: String): Pair<String, String>? {
        if (!mediaId.startsWith("t:")) return null
        val rest = mediaId.removePrefix("t:")
        val artifactId = rest.substringBefore(':', "")
        val tail = rest.substringAfter(':', "")
        val length = tail.substringBefore(':', "").toIntOrNull() ?: return null
        val itemId = tail.substringAfter(':', "")
        if (!Protocol.isValidArtifactId(artifactId) || itemId.length != length) return null
        return artifactId to itemId
    }
}

class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()

    private val app get() = application as ArtifactApp

    @OptIn(UnstableApi::class) // DefaultMediaNotificationProvider customization
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_artifact) },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, app.audio.player, LibraryCallback())
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        scope.cancel()
        // The player belongs to AudioQueue and outlives this service; only the session is released.
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun triggerItems(): List<MediaItem> = app.hub.activeTriggers.flatMap { (artifactId, set) ->
        val label = app.hub.label(artifactId)
        set.items.map { trigger ->
            MediaItem.Builder()
                .setMediaId(BrowseTree.triggerId(artifactId, trigger.item.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(trigger.item.title)
                        .setArtist(label)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
    }

    private fun children(parentId: String): List<MediaItem>? = when (parentId) {
        BrowseTree.ROOT -> listOf(
            folder(BrowseTree.QUEUE, getString(R.string.browse_queue)),
            folder(BrowseTree.TRIGGERS, getString(R.string.browse_triggers)),
        )
        BrowseTree.QUEUE -> app.audio.queueItems()
        BrowseTree.TRIGGERS -> triggerItems()
        else -> null
    }

    // Controller identity checks and MediaItemsWithStartPosition are marked unstable in Media3.
    @OptIn(UnstableApi::class)
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val allowed = ControllerPolicy.allow(
                packageName = controller.packageName,
                ownPackage = packageName,
                isMediaNotificationController = session.isMediaNotificationController(controller),
                isAutoCompanionController = session.isAutoCompanionController(controller),
                isAutomotiveController = session.isAutomotiveController(controller),
            )
            if (!allowed) return MediaSession.ConnectionResult.reject()
            return super.onConnect(session, controller)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(BrowseTree.ROOT, getString(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = children(parentId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            val from = (page.toLong() * pageSize).coerceAtMost(items.size.toLong()).toInt()
            val to = (from.toLong() + pageSize).coerceAtMost(items.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val all = (children(BrowseTree.ROOT).orEmpty() + app.audio.queueItems() + triggerItems())
            val item = all.firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /**
         * A controller (for example Android Auto) picked an item. Queue items seek within the
         * current queue; geotrigger items are resolved (cached or synthesized) and appended.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val picked = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            val queue = app.audio.queueItems()
            val queueIndex = queue.indexOfFirst { it.mediaId == picked.mediaId }
            if (queueIndex >= 0) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue, queueIndex, 0L))
            }
            val (artifactId, itemId) = BrowseTree.parseTriggerId(picked.mediaId)
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            val trigger = app.hub.activeTriggers[artifactId]?.items?.firstOrNull { it.item.id == itemId }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    val file = app.audio.resolve(artifactId, trigger.item)
                    val item = app.audio.buildMediaItem(artifactId, app.hub.label(artifactId), trigger.item, file)
                    val list = app.audio.queueItems() + item
                    result.set(MediaSession.MediaItemsWithStartPosition(list, list.size - 1, 0L))
                } catch (e: Exception) {
                    result.setException(e)
                }
            }
            return result
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // Only items this app built (with a local clip URI) can be added.
            val known = app.audio.queueItems().associateBy { it.mediaId }
            val resolved = mediaItems.mapNotNull { known[it.mediaId] }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
