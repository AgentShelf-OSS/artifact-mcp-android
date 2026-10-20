package ai.agentshelf.artifactmcp.bridge

import ai.agentshelf.artifactmcp.bridge.Protocol.Inbound
import ai.agentshelf.artifactmcp.bridge.Protocol.Reason
import ai.agentshelf.artifactmcp.geo.Fix
import ai.agentshelf.artifactmcp.geo.GeotriggerEngine
import ai.agentshelf.artifactmcp.state.Capability
import ai.agentshelf.artifactmcp.state.GrantStore
import ai.agentshelf.artifactmcp.state.Notice
import ai.agentshelf.artifactmcp.state.NoticeStore
import ai.agentshelf.artifactmcp.state.PlayedStore

/** The top-level viewer page that sent a message, and the way to answer it. */
interface ShellConnection {
    /** Artifact id of the viewer page (`<origin>/<id>`), or null on the gallery and other pages. */
    val pageArtifactId: String?

    /**
     * Identity of the WebView that sent the message (stable across messages from one page
     * view), or null. Used to tie a mirrored media session to its page.
     */
    val host: Any? get() = null

    fun post(message: String)
}

/** Receives the viewer's Listen state (`media.session`, `media.chapters`) for the native media session. */
fun interface MediaSessionSink {
    fun onMediaSession(message: Protocol.Inbound.MediaSession, connection: ShellConnection)

    /** The page's chapter list (ADR-0022). */
    fun onMediaChapters(message: Protocol.Inbound.MediaChapters, connection: ShellConnection) {}
}

/** Asks the user (native dialog and OS runtime permissions) before a capability is used. */
fun interface ConsentGate {
    fun request(artifactId: String, label: String, capability: Capability, onResult: (Boolean) -> Unit)
}

data class LocationDemand(val intervalMs: Long, val highAccuracy: Boolean, val labels: List<String>)

interface LocationControl {
    /** Start, update or (with null) stop the location foreground service. Returns false if it could not start. */
    fun update(demand: LocationDemand?): Boolean
}

interface AudioControl {
    fun enqueue(artifactId: String, label: String, item: Protocol.AudioItem)
    fun stop(artifactId: String)
    fun prefetch(artifactId: String, items: List<Protocol.AudioItem>)
    fun stopAll()
}

interface ArtifactNotifier {
    fun show(artifactId: String, label: String, requestId: String, title: String, body: String): Boolean
}

/**
 * Dispatches bridge messages from the viewer shell to native capabilities and routes native
 * events back. All calls happen on the main thread.
 */
class BridgeHub(
    private val appVersion: String,
    private val clock: () -> Long,
    private val origin: () -> String?,
    private val grants: GrantStore,
    private val played: PlayedStore,
    private val notices: NoticeStore,
    private val location: LocationControl,
    private val audio: AudioControl,
    private val notifier: ArtifactNotifier,
    private val media: MediaSessionSink = MediaSessionSink { _, _ -> },
) {
    companion object {
        const val MAX_WATCHES_PER_ARTIFACT = 4
        const val MAX_WATCHES = 16
        const val MAX_TRIGGER_ARTIFACTS = 10
        const val GEOTRIGGER_INTERVAL_MS = 15_000L
        const val NO_FIX_TIMEOUT_MS = 60_000L
        const val NOTIFY_WINDOW_MS = 10 * 60_000L
        const val NOTIFY_MAX_PER_WINDOW = 10
        /** Fixes are delivered when at least the interval has passed, with this much slack. */
        private const val INTERVAL_SLACK_MS = 1_000L
    }

    data class Watch(
        val artifactId: String,
        val requestId: String,
        val intervalMs: Long,
        val highAccuracy: Boolean,
        val startedAt: Long,
        var lastSentAt: Long = Long.MIN_VALUE,
        var timedOut: Boolean = false,
    )

    data class TriggerSet(val requestId: String, val items: List<Protocol.TriggerItem>)

    var consent: ConsentGate? = null
    private var shell: ShellConnection? = null
    private val watches = LinkedHashMap<Pair<String, String>, Watch>()
    private val triggers = LinkedHashMap<String, TriggerSet>()
    private val titles = HashMap<String, String>()
    private val notifyLog = HashMap<String, ArrayDeque<Long>>()
    private val listeners = mutableListOf<() -> Unit>()

    val activeWatches: List<Watch> get() = watches.values.toList()
    val activeTriggers: Map<String, TriggerSet> get() = triggers.toMap()

    fun addChangeListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun label(artifactId: String): String = titles[artifactId] ?: artifactId

    fun setTitle(artifactId: String, title: String) {
        val clean = title.filterNot { it.isISOControl() }.trim().take(80)
        if (clean.isNotEmpty()) titles[artifactId] = clean
    }

    // ---- shell -> app --------------------------------------------------------------------

    /**
     * Handle one message from a viewer page.
     *
     * [reader] is true for the hidden Listen reader that the media service opens for the car
     * library (ADR-0019). A reader page may only say `hello` and report `media.session` and `media.chapters`; every
     * other capability is denied, it does not become the page that native events go to, and
     * its `hello` does not consume notices or played events meant for the visible viewer.
     */
    fun onShellMessage(raw: String, connection: ShellConnection, reader: Boolean = false) {
        if (!reader) shell = connection
        val currentOrigin = origin() ?: return
        when (val parsed = Protocol.parse(raw, currentOrigin)) {
            is Protocol.ParseResult.Invalid ->
                connection.post(Protocol.error(parsed.artifactId, parsed.requestId, parsed.reason))
            is Protocol.ParseResult.Ok -> if (reader) dispatchReader(parsed.message, connection) else dispatch(parsed.message, connection)
        }
    }

    private fun dispatchReader(message: Inbound, connection: ShellConnection) {
        when (message) {
            Inbound.Hello -> connection.post(Protocol.ready(appVersion))
            is Inbound.MediaSession -> if (message.artifactId == connection.pageArtifactId) {
                media.onMediaSession(message, connection)
            } else {
                connection.post(Protocol.error(message.artifactId, null, Reason.DENIED))
            }
            is Inbound.MediaChapters -> if (message.artifactId == connection.pageArtifactId) {
                media.onMediaChapters(message, connection)
            } else {
                connection.post(Protocol.error(message.artifactId, null, Reason.DENIED))
            }
            else -> connection.post(Protocol.error(message.artifactId, requestIdOf(message), Reason.DENIED))
        }
    }

    private fun dispatch(message: Inbound, connection: ShellConnection) {
        // A page may only drive capabilities for the artifact it is showing.
        val bound = message.artifactId
        if (bound != null && message !is Inbound.GeoUnwatch && message !is Inbound.AudioStop &&
            bound != connection.pageArtifactId
        ) {
            connection.post(Protocol.error(bound, requestIdOf(message), Reason.DENIED))
            return
        }
        when (message) {
            Inbound.Hello -> hello(connection)
            is Inbound.GeoWatch -> geoWatch(message, connection)
            is Inbound.GeoUnwatch -> {
                watches.remove(message.artifactId to message.requestId)
                applyDemand()
            }
            is Inbound.AudioQueue -> audio.enqueue(message.artifactId, label(message.artifactId), message.item)
            is Inbound.AudioGeotrigger -> geotrigger(message, connection)
            is Inbound.AudioStop -> audio.stop(message.artifactId)
            is Inbound.NotifyShow -> notify(message, connection)
            is Inbound.PlayedAck -> played.acknowledge(message.artifactId, message.ids)
            is Inbound.MediaSession -> media.onMediaSession(message, connection)
            is Inbound.MediaChapters -> media.onMediaChapters(message, connection)
        }
    }

    private fun requestIdOf(message: Inbound): String? = when (message) {
        is Inbound.GeoWatch -> message.requestId
        is Inbound.GeoUnwatch -> message.requestId
        is Inbound.AudioQueue -> message.requestId
        is Inbound.AudioGeotrigger -> message.requestId
        is Inbound.NotifyShow -> message.requestId
        else -> null
    }

    private fun hello(connection: ShellConnection) {
        connection.post(Protocol.ready(appVersion))
        val artifactId = connection.pageArtifactId ?: return
        notices.take(artifactId).forEach { connection.post(Protocol.geoError(it.artifactId, it.requestId, it.reason)) }
        played.pendingFor(artifactId).forEach { connection.post(Protocol.audioPlayed(it.artifactId, it.id, it.at)) }
    }

    private fun geoWatch(message: Inbound.GeoWatch, connection: ShellConnection) {
        val key = message.artifactId to message.requestId
        if (key !in watches) {
            val mine = watches.values.count { it.artifactId == message.artifactId }
            if (mine >= MAX_WATCHES_PER_ARTIFACT || watches.size >= MAX_WATCHES) {
                connection.post(Protocol.error(message.artifactId, message.requestId, Reason.LIMIT))
                return
            }
        }
        withConsent(message.artifactId, Capability.LOCATION) { allowed ->
            if (!allowed) {
                post(message.artifactId, Protocol.geoError(message.artifactId, message.requestId, Reason.DENIED))
                return@withConsent
            }
            watches[key] = Watch(message.artifactId, message.requestId, message.intervalMs, message.highAccuracy, clock())
            if (!applyDemand()) {
                watches.remove(key)
                applyDemand()
                post(message.artifactId, Protocol.geoError(message.artifactId, message.requestId, Reason.UNAVAILABLE))
            }
        }
    }

    private fun geotrigger(message: Inbound.AudioGeotrigger, connection: ShellConnection) {
        if (message.items.isEmpty()) {
            triggers.remove(message.artifactId)
            applyDemand()
            return
        }
        if (message.artifactId !in triggers && triggers.size >= MAX_TRIGGER_ARTIFACTS) {
            connection.post(Protocol.error(message.artifactId, message.requestId, Reason.LIMIT))
            return
        }
        withConsent(message.artifactId, Capability.LOCATION) { allowed ->
            if (!allowed) {
                post(message.artifactId, Protocol.geoError(message.artifactId, message.requestId, Reason.DENIED))
                return@withConsent
            }
            val previous = triggers[message.artifactId]
            triggers[message.artifactId] = TriggerSet(message.requestId, message.items)
            if (!applyDemand()) {
                if (previous == null) triggers.remove(message.artifactId) else triggers[message.artifactId] = previous
                applyDemand()
                post(message.artifactId, Protocol.geoError(message.artifactId, message.requestId, Reason.UNAVAILABLE))
                return@withConsent
            }
            // Synthesize while the viewer is open and the session is fresh: playback later may
            // happen with the screen off, or after the Access session has expired.
            val alreadyPlayed = played.playedIds(message.artifactId)
            audio.prefetch(message.artifactId, message.items.map { it.item }.filter { it.id !in alreadyPlayed })
            changed()
        }
    }

    private fun notify(message: Inbound.NotifyShow, connection: ShellConnection) {
        val now = clock()
        val log = notifyLog.getOrPut(message.artifactId) { ArrayDeque() }
        while (log.isNotEmpty() && now - log.first() > NOTIFY_WINDOW_MS) log.removeFirst()
        if (log.size >= NOTIFY_MAX_PER_WINDOW) {
            connection.post(Protocol.error(message.artifactId, message.requestId, Reason.LIMIT))
            return
        }
        withConsent(message.artifactId, Capability.NOTIFY) { allowed ->
            if (!allowed) {
                post(message.artifactId, Protocol.error(message.artifactId, message.requestId, Reason.DENIED))
                return@withConsent
            }
            log.addLast(clock())
            if (!notifier.show(message.artifactId, label(message.artifactId), message.requestId, message.title, message.body)) {
                post(message.artifactId, Protocol.error(message.artifactId, message.requestId, Reason.UNAVAILABLE))
            }
        }
    }

    private fun withConsent(artifactId: String, capability: Capability, block: (Boolean) -> Unit) {
        val gate = consent
        if (gate == null) {
            block(false)
            return
        }
        gate.request(artifactId, label(artifactId), capability, block)
    }

    // ---- native -> shell -----------------------------------------------------------------

    /** Post to the shell only if it is showing [artifactId]; other pages never see the event. */
    private fun post(artifactId: String, message: String) {
        val connection = shell ?: return
        if (connection.pageArtifactId == artifactId) connection.post(message)
    }

    fun onFix(fix: Fix) {
        val now = clock()
        for (watch in watches.values) {
            if (watch.lastSentAt == Long.MIN_VALUE || now - watch.lastSentAt >= watch.intervalMs - INTERVAL_SLACK_MS) {
                watch.lastSentAt = now
                post(
                    watch.artifactId,
                    Protocol.geoPosition(watch.artifactId, watch.requestId, fix.lat, fix.lon, fix.accuracyM, fix.timestamp),
                )
            }
        }
        for ((artifactId, set) in triggers) {
            val hits = GeotriggerEngine.hits(fix, set.items, played.playedIds(artifactId))
            for (hit in hits) {
                if (!played.markPlayed(artifactId, hit.item.item.id, now)) continue
                audio.enqueue(artifactId, label(artifactId), hit.item.item)
                post(artifactId, Protocol.audioPlayed(artifactId, hit.item.item.id, now))
            }
        }
    }

    /** Called periodically by the location service; reports watches that never got a fix. */
    fun checkTimeouts() {
        val now = clock()
        for (watch in watches.values) {
            if (!watch.timedOut && watch.lastSentAt == Long.MIN_VALUE && now - watch.startedAt >= NO_FIX_TIMEOUT_MS) {
                watch.timedOut = true
                post(watch.artifactId, Protocol.geoError(watch.artifactId, watch.requestId, Reason.TIMEOUT))
            }
        }
    }

    /** Location is switched off or no provider exists. Watches stay registered and resume. */
    fun onLocationUnavailable() {
        for (watch in watches.values) {
            post(watch.artifactId, Protocol.geoError(watch.artifactId, watch.requestId, Reason.UNAVAILABLE))
        }
    }

    fun onAudioState(artifactId: String, id: String, state: String) {
        post(artifactId, Protocol.audioState(artifactId, id, state))
    }

    // ---- lifecycle and user controls -----------------------------------------------------

    /** A new top-level document started loading: its request ids are no longer meaningful. */
    fun onPageStarted() {
        shell = null
        if (watches.isNotEmpty()) {
            watches.clear()
            applyDemand()
        }
    }

    /** User pressed Stop on the location notification: end every location use. */
    fun stopAllLocation() {
        val ended = watches.values.map { Notice(it.artifactId, it.requestId, Reason.DENIED) } +
            triggers.map { (artifactId, set) -> Notice(artifactId, set.requestId, Reason.DENIED) }
        watches.clear()
        triggers.clear()
        applyDemand()
        deliverOrStore(ended)
    }

    /** User revoked a capability in Settings. */
    fun revoke(artifactId: String, capability: Capability) {
        grants.revoke(artifactId, capability)
        if (capability != Capability.LOCATION) {
            changed()
            return
        }
        val ended = watches.values.filter { it.artifactId == artifactId }
            .map { Notice(it.artifactId, it.requestId, Reason.DENIED) } +
            listOfNotNull(triggers[artifactId]?.let { Notice(artifactId, it.requestId, Reason.DENIED) })
        watches.entries.removeAll { it.value.artifactId == artifactId }
        triggers.remove(artifactId)
        applyDemand()
        deliverOrStore(ended)
    }

    /** Sign out / clear data / origin change. */
    fun reset() {
        watches.clear()
        triggers.clear()
        titles.clear()
        notifyLog.clear()
        shell = null
        audio.stopAll()
        applyDemand()
    }

    private fun deliverOrStore(ended: List<Notice>) {
        // Stored notices are delivered on the artifact's next hello, which also covers the case
        // where the page is currently paused in the background and never sees a direct post.
        ended.forEach { notices.add(it) }
        val connection = shell ?: return
        val page = connection.pageArtifactId ?: return
        notices.take(page).forEach { connection.post(Protocol.geoError(it.artifactId, it.requestId, it.reason)) }
    }

    fun demand(): LocationDemand? {
        if (watches.isEmpty() && triggers.isEmpty()) return null
        val intervals = watches.values.map { it.intervalMs } +
            (if (triggers.isNotEmpty()) listOf(GEOTRIGGER_INTERVAL_MS) else emptyList())
        val labels = (watches.values.map { it.artifactId } + triggers.keys).distinct().map(::label)
        return LocationDemand(
            intervalMs = intervals.min(),
            // Trigger radii are at least 500 m, but car speeds need GNSS-quality fixes.
            highAccuracy = triggers.isNotEmpty() || watches.values.any { it.highAccuracy },
            labels = labels,
        )
    }

    private fun applyDemand(): Boolean {
        val ok = location.update(demand())
        changed()
        return ok
    }

    private fun changed() = listeners.toList().forEach { it() }
}
