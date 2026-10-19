package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.ArtifactApp
import ai.agentshelf.artifactmcp.R
import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import ai.agentshelf.artifactmcp.car.CarCommands
import ai.agentshelf.artifactmcp.car.CarErrors
import ai.agentshelf.artifactmcp.car.CoverUris
import ai.agentshelf.artifactmcp.library.Artwork
import ai.agentshelf.artifactmcp.library.BrowseNode
import ai.agentshelf.artifactmcp.library.ChildStyle
import ai.agentshelf.artifactmcp.library.Completion
import ai.agentshelf.artifactmcp.library.LibraryTree
import ai.agentshelf.artifactmcp.library.LibraryView
import ai.agentshelf.artifactmcp.listen.MirrorState
import ai.agentshelf.artifactmcp.listen.PlaybackArbiter
import ai.agentshelf.artifactmcp.ui.MainActivity
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 * Browse tree ids. Queue entries use the player's own `q:` media ids and geotrigger stops
 * `t:` ids (both in the Road stories tab); the car library uses tabs, `org:<name>` folders and
 * `art:<artifactId>` items ([LibraryTree]).
 */
object BrowseTree {
    const val ROOT = "root"

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


/**
 * The media session for the phone notification, Android Auto and Android Automotive.
 *
 * The browse tree is [LibraryTree] (tabs Continue, Library, Road stories). Covers are
 * `content://` URIs of [ai.agentshelf.artifactmcp.car.CoverProvider]; tab icons are vector
 * drawables as `android.resource` URIs. Now playing gets two custom buttons ([CarCommands]).
 */
// Media3 marks several session APIs used here as unstable (button slots and preferences, media
// metadata extras, controller identity checks).
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private companion object {
        const val TAG = "ArtifactCar"
    }

    private var session: MediaLibrarySession? = null
    private val scope = MainScope()
    private val main = Handler(Looper.getMainLooper())

    /** Root hint from the last `onGetLibraryRoot` (Android Auto sends a child limit). */
    private var rootLimit = Int.MAX_VALUE

    private val app get() = application as ArtifactApp

    private var buttons: List<CarCommands.Button> = emptyList()

    private val onActive: (PlaybackArbiter.Source) -> Unit = { source ->
        clearRejection()
        applyPlayer(source)
        updateButtons()
    }
    private val onListen: () -> Unit = { updateButtons() }
    private val onRecents: () -> Unit = {
        session?.notifyChildrenChanged(LibraryTree.CONTINUE, Int.MAX_VALUE, null)
        session?.notifyChildrenChanged(BrowseTree.ROOT, Int.MAX_VALUE, null)
    }
    private var hadRoadStories = false
    private val nativeEvents = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val road = hasRoadStories()
            if (road != hadRoadStories) session?.notifyChildrenChanged(BrowseTree.ROOT, Int.MAX_VALUE, null)
            hadRoadStories = road
            session?.notifyChildrenChanged(LibraryTree.ROAD, Int.MAX_VALUE, null)
            updateButtons()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = updateButtons()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) clearRejection()
        }
    }

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
        buttons = currentButtons()
        session = MediaLibrarySession.Builder(this, active, LibraryCallback())
            .setSessionActivity(open)
            .setMediaButtonPreferences(commandButtons(buttons))
            .build()
        app.addActiveSourceListener(onActive)
        app.listen.addListener(onListen)
        app.recents.addListener(onRecents)
        app.audio.player.addListener(nativeEvents)
        hadRoadStories = hasRoadStories()
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
        app.listen.removeListener(onListen)
        app.recents.removeListener(onRecents)
        app.audio.player.removeListener(nativeEvents)
        scope.cancel()
        // The reader WebView is owned by this service: it ends with it.
        app.listen.destroyReader()
        // The players belong to the app and outlive this service; only the session is released.
        session?.release()
        session = null
        super.onDestroy()
    }

    // ---- custom now-playing buttons ------------------------------------------------------------

    private fun labels() = CarCommands.Labels(getString(R.string.car_rewind), getString(R.string.car_speed), getString(R.string.car_forward))

    /** Buttons for the active source: the Listen page's actions, or the native queue. */
    private fun currentButtons(): List<CarCommands.Button> {
        if (app.arbiter.active == PlaybackArbiter.Source.MIRROR) {
            val mirror = app.listen.mirror
            val live = mirror != null && (
                mirror.phase == MirrorState.Phase.PLAYING || mirror.phase == MirrorState.Phase.PAUSED ||
                    mirror.phase == MirrorState.Phase.BUFFERING
                )
            return CarCommands.buttons(
                rewind = live && mirror!!.supports(MediaAction.REWIND),
                speed = live && mirror!!.supports(MediaAction.SPEED),
                rate = mirror?.rate,
                labels = labels(),
                forward = live && mirror!!.supports(MediaAction.FORWARD),
            )
        }
        val player = app.audio.player
        val hasItem = player.mediaItemCount > 0
        return CarCommands.buttons(hasItem, hasItem, CarCommands.rateString(player.playbackParameters.speed), labels(), forward = hasItem)
    }

    // CommandButton slots and custom icons; the slots come from CommandButton.SLOT_* constants
    // in CarCommands (lint cannot follow them through the list).
    @SuppressLint("WrongConstant")
    @OptIn(UnstableApi::class)
    private fun commandButtons(list: List<CarCommands.Button>): List<CommandButton> = list.map { b ->
        CommandButton.Builder(b.icon)
            .setSessionCommand(SessionCommand(b.command, Bundle.EMPTY))
            .setDisplayName(b.displayName)
            .setSlots(*b.slots.toIntArray())
            .apply { if (b.customIcon == "ic_speed_1_25") setCustomIconResId(R.drawable.ic_speed_1_25) }
            .build()
    }

    @OptIn(UnstableApi::class) // MediaSession.setMediaButtonPreferences
    private fun updateButtons() {
        val next = currentButtons()
        if (next == buttons) return
        buttons = next
        session?.setMediaButtonPreferences(commandButtons(next))
    }

    private fun runCustom(action: MediaAction) {
        if (app.arbiter.active == PlaybackArbiter.Source.MIRROR) {
            app.listen.command(action)
            return
        }
        val player = app.audio.player
        when (action) {
            MediaAction.REWIND -> player.seekTo(CarCommands.rewindTarget(player.currentPosition))
            MediaAction.FORWARD -> player.seekTo(CarCommands.forwardTarget(player.currentPosition, player.duration))
            MediaAction.SPEED -> player.setPlaybackSpeed(CarCommands.nextNativeRate(player.playbackParameters.speed))
            else -> Unit
        }
    }

    // ---- browse items --------------------------------------------------------------------------

    private fun strings() = LibraryTree.Strings(
        continueTab = getString(R.string.car_tab_continue),
        libraryTab = getString(R.string.car_tab_library),
        roadTab = getString(R.string.car_tab_road),
        continueEmpty = getString(R.string.car_continue_empty),
        signedOut = getString(R.string.car_signed_out),
        stale = getString(R.string.car_stale),
        offline = getString(R.string.car_offline),
        empty = getString(R.string.car_empty),
        other = getString(R.string.car_other),
        needsConnection = getString(R.string.car_needs_connection),
        noConnection = getString(R.string.car_no_connection),
    )

    private fun iconRes(name: String): Int = when (name) {
        LibraryTree.ICON_CONTINUE -> R.drawable.ic_tab_continue
        LibraryTree.ICON_LIBRARY -> R.drawable.ic_tab_library
        LibraryTree.ICON_ROAD -> R.drawable.ic_tab_road
        else -> 0
    }

    private fun artworkUri(artwork: Artwork?): Uri? = when (artwork) {
        null -> null
        is Artwork.Cover -> CoverUris.uri(packageName, artwork.spec)
        // The named form (package/drawable/name): the car media center resolves it with
        // getIdentifier and does not accept numeric ids. res/raw/keep.xml keeps the names.
        is Artwork.Icon -> iconRes(artwork.drawable).takeIf { it != 0 }?.let {
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority(resources.getResourcePackageName(it))
                .appendPath(resources.getResourceTypeName(it)).appendPath(resources.getResourceEntryName(it)).build()
        }
    }

    private fun styleValue(style: ChildStyle) = when (style) {
        ChildStyle.LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
        ChildStyle.GRID -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
    }

    private fun completionValue(status: Completion.Status) = when (status) {
        Completion.Status.NOT_PLAYED -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        Completion.Status.PARTIALLY_PLAYED -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        Completion.Status.FULLY_PLAYED -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
    }

    private fun extras(node: BrowseNode): Bundle? {
        if (node.style == null && node.groupTitle == null && node.completion == null) return null
        return Bundle().apply {
            node.style?.let {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, styleValue(it.browsable))
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, styleValue(it.playable))
            }
            node.groupTitle?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
            node.completion?.let { c ->
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completionValue(c.status))
                c.fraction?.let { putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, it) }
            }
        }
    }

    private fun node(node: BrowseNode): MediaItem = MediaItem.Builder()
        .setMediaId(node.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(node.title)
                .setDisplayTitle(node.title)
                .setSubtitle(node.subtitle)
                .setIsBrowsable(node.browsable)
                .setIsPlayable(node.playable)
                .setArtworkUri(artworkUri(node.artwork))
                .setExtras(extras(node))
                .setMediaType(
                    when {
                        node.browsable -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                        node.playable -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
                        else -> MediaMetadata.MEDIA_TYPE_MIXED
                    },
                )
                .build(),
        )
        .build()

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(BrowseTree.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun withGroup(item: MediaItem, group: String): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply {
            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, group)
        }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    private fun triggerItems(): List<MediaItem> = app.hub.activeTriggers.flatMap { (artifactId, set) ->
        val label = app.hub.label(artifactId)
        set.items.map { trigger ->
            MediaItem.Builder()
                .setMediaId(BrowseTree.triggerId(artifactId, trigger.item.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(LibraryTree.shortTitle(trigger.item.title))
                        .setArtist(label)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                )
                .build()
        }
    }

    private fun hasRoadStories() = app.audio.queueItems().isNotEmpty() || app.hub.activeTriggers.isNotEmpty()

    /** Road stories: the queue, then the geotrigger stops, each under its heading. */
    private fun roadItems(): List<MediaItem> =
        app.audio.queueItems().map { withGroup(it, getString(R.string.car_queue)) } +
            triggerItems().map { withGroup(it, getString(R.string.car_triggers)) }

    private fun recents() = app.recents.forOrigin(app.originStore.origin)

    private fun children(parentId: String, view: LibraryView): List<MediaItem>? {
        val map = ::node
        val offline = !app.isOnline()
        return when (parentId) {
            BrowseTree.ROOT -> LibraryTree.root(strings(), hasRoadStories(), rootLimit).map(map)
            LibraryTree.CONTINUE -> LibraryTree.continueChildren(recents(), strings(), offline).map(map)
            LibraryTree.LIBRARY -> LibraryTree.libraryChildren(view, strings()).map(map)
            LibraryTree.ROAD -> roadItems()
            LibraryTree.RECENT -> LibraryTree.recentChildren(recents(), strings()).map(map)
            in LibraryTree.MESSAGES -> emptyList()
            else -> LibraryTree.orgChildren(view, parentId, strings(), offline)?.map(map)
        }
    }

    private fun errorMessage(kind: CarErrors.Kind, query: String = ""): String = when (kind) {
        CarErrors.Kind.NOT_SET_UP -> getString(R.string.car_set_up_first)
        CarErrors.Kind.SIGNED_OUT -> getString(R.string.car_signed_out)
        CarErrors.Kind.NOTHING_TO_RESUME -> getString(R.string.car_nothing_to_resume)
        CarErrors.Kind.OFFLINE -> getString(R.string.car_no_connection)
        CarErrors.Kind.NO_MATCH -> getString(R.string.car_no_match, query.take(60))
        CarErrors.Kind.LIBRARY_UNAVAILABLE -> getString(R.string.car_library_unavailable)
    }

    /**
     * Tell the car why nothing starts: the session shows [kind] as a playback error (signed out
     * with the "open on phone" resolution) until something plays, and the request fails.
     */
    private fun <T> reject(kind: CarErrors.Kind, query: String = ""): ListenableFuture<T> {
        val message = errorMessage(kind, query)
        val extras = if (kind == CarErrors.Kind.SIGNED_OUT) app.signInResolutionExtras() else Bundle.EMPTY
        Log.i(TAG, "rejected: $kind")
        rejected = true
        session?.setPlaybackException(PlaybackException(message, null, kind.playbackCode, extras))
        return Futures.immediateFailedFuture(IllegalStateException(message))
    }

    /** Whether the session shows a [reject] error. */
    private var rejected = false

    /** Something starts: drop the [reject] error. */
    private fun clearRejection() {
        if (!rejected) return
        rejected = false
        session?.setPlaybackException(null)
    }

    /** Media item of a library artifact (title from the library when known). */
    private fun libraryItem(artifactId: String): MediaItem {
        val library = (app.library.known() as? LibraryView.Ready)?.library
        val artifact = library?.find(artifactId)
        val org = library?.orgOf(artifactId)
        if (artifact != null && org != null) return node(LibraryTree.artifactNode(artifact, org, subtitle = artifact.category.ifBlank { null }))
        val recent = recents().firstOrNull { it.artifactId == artifactId }
        val title = recent?.title ?: app.hub.label(artifactId)
        return node(
            BrowseNode(
                LibraryTree.artifactMediaId(artifactId),
                LibraryTree.shortTitle(title),
                recent?.category?.ifBlank { null },
                browsable = false,
                playable = true,
                artwork = null,
            ),
        ).let { item ->
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(app.artifactCover(artifactId, title)).build()).build()
        }
    }

    /**
     * Open Listen for a picked library artifact and hand the session to the mirror. The result
     * completes on the next main-loop turn so that the session applies it to the mirror player.
     */
    private fun startLibraryItem(artifactId: String): ListenableFuture<MediaItem>? {
        val item = libraryItem(artifactId)
        val title = (app.library.known() as? LibraryView.Ready)?.library?.find(artifactId)?.title
            ?: item.mediaMetadata.title?.toString().orEmpty()
        if (!app.listen.playFromLibrary(artifactId, title)) return null
        clearRejection()
        applyPlayer(app.arbiter.active)
        val result = SettableFuture.create<MediaItem>()
        main.post { result.set(item) }
        return result
    }

    /**
     * The artifact a search request stands for ("play <title> on Artifact MCP"): the best match
     * in the library; for an empty query ("play Artifact MCP") the most recent one.
     */
    private fun searchPick(query: String): String? {
        if (LibraryTree.searchTerms(query).isEmpty()) return recents().firstOrNull()?.artifactId
        val library = (app.library.known() as? LibraryView.Ready)?.library ?: return null
        return LibraryTree.search(library, query).firstOrNull()?.second?.id
    }

    private fun <T> page(items: List<T>, page: Int, pageSize: Int): List<T> {
        val size = pageSize.coerceAtLeast(1).toLong()
        val from = (page.coerceAtLeast(0).toLong() * size).coerceAtMost(items.size.toLong()).toInt()
        val to = (from.toLong() + size).coerceAtMost(items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    /** The library for search: the known copy, else a fresh fetch. */
    private fun withLibrary(block: (LibraryView) -> Unit) {
        val known = app.library.known()
        if (known is LibraryView.Ready) block(known) else scope.launch { block(app.library.refresh()) }
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
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CarCommands.REWIND, Bundle.EMPTY))
                .add(SessionCommand(CarCommands.SPEED, Bundle.EMPTY))
                .add(SessionCommand(CarCommands.FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(commandButtons(buttons))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val action = CarCommands.action(customCommand.customAction)
                ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            runCustom(action)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (params?.isRecent == true) {
                // EXTRA_RECENT (Android Auto's resume card): a root whose one child is the newest
                // Continue item. No recent item: no root (a null root for legacy browsers).
                if (recents().isEmpty()) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
                val recentRoot = MediaItem.Builder()
                    .setMediaId(LibraryTree.RECENT)
                    .setMediaMetadata(MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build())
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(recentRoot, LibraryParams.Builder().setRecent(true).build()))
            }
            params?.extras?.let { extras ->
                val limit = extras.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)
                rootLimit = if (limit > 0) limit else Int.MAX_VALUE
            }
            // Defaults for folders without their own hint; each tab sets its own style.
            val rootExtras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            }
            val rootParams = LibraryParams.Builder().setExtras(rootExtras).build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), rootParams))
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
                    result.set(LibraryResult.ofItemList(ImmutableList.copyOf(page(items, page, pageSize)), params))
                }
            }
            if (parentId == LibraryTree.LIBRARY) {
                // Refresh on browse (short timeout; the cache answers when offline).
                scope.launch { respond(app.library.refresh()) }
            } else {
                respond(app.library.known())
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            LibraryTree.parseArtifactMediaId(mediaId)?.let { return Futures.immediateFuture(LibraryResult.ofItem(libraryItem(it), null)) }
            if (mediaId == BrowseTree.ROOT) return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            val view = app.library.known()
            val tabs = children(BrowseTree.ROOT, view).orEmpty()
            val folder = LibraryTree.parseOrgId(mediaId)?.let { name ->
                (view as? LibraryView.Ready)?.library?.org(name)?.let { node(LibraryTree.orgFolder(it)) }
            }
            val item = folder
                ?: (tabs + children(LibraryTree.LIBRARY, view).orEmpty() + children(LibraryTree.CONTINUE, view).orEmpty() + roadItems())
                    .firstOrNull { it.mediaId == mediaId }
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            withLibrary { view ->
                val count = (view as? LibraryView.Ready)?.let { LibraryTree.search(it.library, query).size } ?: 0
                session.notifySearchResultChanged(browser, query, count, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            withLibrary { view ->
                val items = (view as? LibraryView.Ready)?.let { LibraryTree.searchResults(it.library, query) }.orEmpty().map(::node)
                result.set(LibraryResult.ofItemList(ImmutableList.copyOf(page(items, page, pageSize)), params))
            }
            return result
        }

        /** The query of a voice or typed "play ..." request (no media id), else null. */
        private fun searchQuery(item: MediaItem): String? {
            val query = item.requestMetadata.searchQuery ?: return null
            return query.takeIf { item.mediaId.isEmpty() || item.mediaId == MediaItem.DEFAULT_MEDIA_ID }
        }

        /**
         * Play the best match for [query]; the library is fetched first when none is known yet
         * (for example right after the app started).
         */
        private fun <T> playSearch(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            query: String,
            wrap: (MediaItem) -> T,
        ): ListenableFuture<T> {
            val result = SettableFuture.create<T>()
            withLibrary { view ->
                val pick = searchPick(query)
                Log.i(TAG, "play from search: ${view::class.simpleName}, match ${pick != null}")
                val started = pick?.let(::startLibraryItem)
                if (started == null) {
                    // A clear reason for the car instead of a silent failure.
                    val kind = if (pick == null && LibraryTree.searchTerms(query).isEmpty()) {
                        CarErrors.Kind.NOTHING_TO_RESUME
                    } else {
                        CarErrors.searchMiss(view, app.isOnline())
                    }
                    result.setFuture(reject(kind, LibraryTree.searchTerms(query)))
                } else {
                    result.setFuture(Futures.transform(started, { item -> wrap(item) }, Runnable::run))
                }
            }
            return result
        }

        /**
         * A controller (for example Android Auto) picked an item or asked to play a search.
         * Library artifacts open Listen (the mirror takes over); queue items seek within the
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
            searchQuery(picked)?.let { query ->
                return playSearch(mediaSession, controller, query) { item -> MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L) }
            }
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

        /**
         * Playback resumption (a media button or the system's resume card with nothing loaded):
         * the newest Continue item. For playback it opens `<origin>/<id>?listen=autoplay` like a
         * library pick; the page resumes its saved place itself. Not set up, signed out,
         * offline or nothing to resume: a clear error for the controller, never a crash.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val origin = app.originStore.origin
            val newest = recents().firstOrNull()
            val problem = CarErrors.resumption(
                originSet = origin != null,
                hasRecent = newest != null,
                library = app.library.known(),
                online = app.isOnline(),
                forPlayback = isForPlayback,
            )
            Log.i(TAG, "playback resumption (forPlayback=$isForPlayback): ${problem ?: "newest recent"}")
            if (problem != null) {
                // Metadata only (the system's resume card): no error on screen.
                return if (isForPlayback) reject(problem) else Futures.immediateFailedFuture(IllegalStateException(problem.name))
            }
            val artifactId = newest!!.artifactId
            if (!isForPlayback) {
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(libraryItem(artifactId)), 0, C.TIME_UNSET))
            }
            val started = startLibraryItem(artifactId) ?: return reject(CarErrors.Kind.NOTHING_TO_RESUME)
            return Futures.transform(started, { item -> MediaSession.MediaItemsWithStartPosition(listOf(item), 0, C.TIME_UNSET) }, Runnable::run)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // A library artifact cannot be queued behind something else: picking it opens Listen.
            mediaItems.firstNotNullOfOrNull(::searchQuery)?.let { query -> return playSearch(mediaSession, controller, query) { item -> mutableListOf(item) } }
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
}
