package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.library.BrowseNode
import ai.agentshelf.artifactmcp.library.LibraryTree
import ai.agentshelf.artifactmcp.library.LibraryView
import ai.agentshelf.artifactmcp.listen.PlaybackArbiter
import ai.agentshelf.artifactmcp.ui.MainActivity
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
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

/**
 * Browse tree ids. Queue entries use the player's own `q:` media ids; the car library uses
 * `org:<name>` folders and `art:<artifactId>` items ([LibraryTree]).
 */
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
    private val main = Handler(Looper.getMainLooper())

    /** Root hints from the last `onGetLibraryRoot` (Android Auto sends a child limit). */
    private var rootLimit = Int.MAX_VALUE
    private var browsableOnlyAtRoot = false

    private val app get() = application as ArtifactApp

    private val onActive: (PlaybackArbiter.Source) -> Unit = { source -> applyPlayer(source) }

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
        val active = if (app.arbiter.active == PlaybackArbiter.Source.MIRROR) app.mirrorPlayer else app.audio.player
        session = MediaLibrarySession.Builder(this, active, LibraryCallback())
            .setSessionActivity(open)
            .build()
        app.addActiveSourceListener(onActive)
    }

    /** Last active wins: the session shows the native queue or the Listen mirror. */
    private fun applyPlayer(source: PlaybackArbiter.Source) {
        val target = if (source == PlaybackArbiter.Source.MIRROR) app.mirrorPlayer else app.audio.player
        val current = session ?: return
        if (current.player !== target) current.player = target
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        app.removeActiveSourceListener(onActive)
        scope.cancel()
        // The reader WebView is owned by this service: it ends with it.
        app.listen.destroyReader()
        // The players belong to the app and outlive this service; only the session is released.
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

    private fun node(node: BrowseNode): MediaItem {
        if (node.browsable) return folder(node.id, node.title)
        return MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(node.title)
                    .setSubtitle(node.subtitle)
                    .setIsBrowsable(false)
                    .setIsPlayable(node.playable)
                    .setMediaType(if (node.playable) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER else MediaMetadata.MEDIA_TYPE_MIXED)
                    .build(),
            )
            .build()
    }

    private fun strings() = LibraryTree.Strings(
        library = getString(R.string.browse_library),
        signIn = getString(R.string.browse_sign_in),
        offline = getString(R.string.browse_offline),
        empty = getString(R.string.browse_empty),
    )

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

    private fun nativeFolders(): List<BrowseNode> = buildList {
        if (app.audio.queueItems().isNotEmpty()) add(BrowseNode(BrowseTree.QUEUE, getString(R.string.browse_queue), browsable = true, playable = false))
        if (app.hub.activeTriggers.isNotEmpty()) add(BrowseNode(BrowseTree.TRIGGERS, getString(R.string.browse_triggers), browsable = true, playable = false))
    }

    /** The library as last known: the latest fetch, else the disk cache. */
    private fun knownLibrary(): LibraryView = app.library.last
        ?: app.library.cached()?.let { LibraryView.Ready(it, fromCache = true) }
        ?: LibraryView.Unavailable

    private fun children(parentId: String, view: LibraryView): List<MediaItem>? = when (parentId) {
        BrowseTree.ROOT -> LibraryTree.root(view, nativeFolders(), strings(), rootLimit, browsableOnlyAtRoot).map(::node)
        LibraryTree.LIBRARY -> LibraryTree.libraryFolder(view, strings()).map(::node)
        BrowseTree.QUEUE -> app.audio.queueItems()
        BrowseTree.TRIGGERS -> triggerItems()
        else -> LibraryTree.orgChildren(view, parentId)?.map(::node)
    }

    /** Media item of a library artifact (title from the library when known). */
    private fun libraryItem(artifactId: String): MediaItem {
        val artifact = (knownLibrary() as? LibraryView.Ready)?.library?.find(artifactId)
        return node(
            BrowseNode(
                LibraryTree.artifactMediaId(artifactId),
                artifact?.title ?: app.hub.label(artifactId),
                artifact?.category?.ifBlank { null },
                browsable = false,
                playable = true,
            ),
        )
    }

    /**
     * Open Listen for a picked library artifact and hand the session to the mirror. The result
     * completes on the next main-loop turn so that the session applies it to the mirror player.
     */
    private fun startLibraryItem(artifactId: String): ListenableFuture<MediaItem>? {
        val item = libraryItem(artifactId)
        if (!app.listen.playFromLibrary(artifactId, item.mediaMetadata.title?.toString().orEmpty())) return null
        applyPlayer(app.arbiter.active)
        val result = SettableFuture.create<MediaItem>()
        main.post { result.set(item) }
        return result
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
        ): ListenableFuture<LibraryResult<MediaItem>> {
            params?.extras?.let { extras ->
                val limit = extras.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)
                rootLimit = if (limit > 0) limit else Int.MAX_VALUE
                browsableOnlyAtRoot = extras.getBoolean(MediaConstants.EXTRA_KEY_ROOT_CHILDREN_BROWSABLE_ONLY, false) ||
                    extras.getInt(ROOT_SUPPORTED_FLAGS, 0) == FLAG_BROWSABLE
            }
            return Futures.immediateFuture(LibraryResult.ofItem(folder(BrowseTree.ROOT, getString(R.string.app_name)), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            val respond = { view: LibraryView ->
                val items = children(parentId, view)
                if (items == null) {
                    result.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                } else {
                    val size = pageSize.coerceAtLeast(1).toLong()
                    val from = (page.coerceAtLeast(0).toLong() * size).coerceAtMost(items.size.toLong()).toInt()
                    val to = (from.toLong() + size).coerceAtMost(items.size.toLong()).toInt()
                    result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
                }
            }
            if (parentId == BrowseTree.ROOT || parentId == LibraryTree.LIBRARY) {
                // Refresh on browse (short timeout; the cache answers when offline).
                scope.launch { respond(app.library.refresh()) }
            } else {
                respond(knownLibrary())
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            LibraryTree.parseArtifactMediaId(mediaId)?.let { return Futures.immediateFuture(LibraryResult.ofItem(libraryItem(it), null)) }
            val view = knownLibrary()
            val all = children(BrowseTree.ROOT, view).orEmpty() + app.audio.queueItems() + triggerItems()
            val item = all.firstOrNull { it.mediaId == mediaId }
                ?: LibraryTree.parseOrgId(mediaId)?.takeIf { LibraryTree.orgChildren(view, mediaId) != null }?.let { folder(mediaId, it) }
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /**
         * A controller (for example Android Auto) picked an item. Library artifacts open Listen
         * (the mirror takes over); queue items seek within the current queue; geotrigger items
         * are resolved (cached or synthesized) and appended.
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
            LibraryTree.parseArtifactMediaId(picked.mediaId)?.let { artifactId ->
                val started = startLibraryItem(artifactId) ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
                return Futures.transform(started, { item -> MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L) }, Runnable::run)
            }
            val queue = app.audio.queueItems()
            val queueIndex = queue.indexOfFirst { it.mediaId == picked.mediaId }
            if (queueIndex >= 0) {
                return nativeSelected(MediaSession.MediaItemsWithStartPosition(queue, queueIndex, 0L))
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
                    app.arbiter.nativeSelected()
                    applyPlayer(app.arbiter.active)
                    main.post { result.set(MediaSession.MediaItemsWithStartPosition(list, list.size - 1, 0L)) }
                } catch (e: Exception) {
                    result.setException(e)
                }
            }
            return result
        }

        /** Switch the session to the native queue first, then complete on the next loop turn. */
        private fun nativeSelected(value: MediaSession.MediaItemsWithStartPosition): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (app.arbiter.active == PlaybackArbiter.Source.NATIVE) return Futures.immediateFuture(value)
            app.arbiter.nativeSelected()
            applyPlayer(app.arbiter.active)
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            main.post { result.set(value) }
            return result
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // A library artifact cannot be queued behind something else: picking it opens Listen.
            mediaItems.firstNotNullOfOrNull { LibraryTree.parseArtifactMediaId(it.mediaId) }?.let { artifactId ->
                val started = startLibraryItem(artifactId) ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
                return Futures.transform(started, { item -> mutableListOf(item) }, Runnable::run)
            }
            // Only items this app built (with a local clip URI) can be added.
            val known = app.audio.queueItems().associateBy { it.mediaId }
            val resolved = mediaItems.mapNotNull { known[it.mediaId] }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private companion object {
        /** Root hint of Android Auto (MediaBrowserCompat) with the supported root child flags. */
        const val ROOT_SUPPORTED_FLAGS = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"
        const val FLAG_BROWSABLE = 1
    }
}
