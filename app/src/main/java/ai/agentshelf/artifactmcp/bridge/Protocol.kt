package ai.agentshelf.artifactmcp.bridge

import ai.agentshelf.artifactmcp.config.OriginPolicy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Bridge protocol version 1 between the viewer shell and the app (ADR-0018).
 *
 * Every inbound message is validated strictly: exact key sets, JSON types, lengths and ranges.
 * Invalid input never reaches a capability; it produces an `error` reply instead.
 */
object Protocol {
    const val VERSION = 1
    const val MAX_MESSAGE_CHARS = 200_000
    const val MAX_ID = 64
    const val MAX_TITLE = 120
    const val MAX_TEXT = 1500
    const val MAX_BODY = 1500
    const val MAX_TRIGGER_ITEMS = 50
    const val MAX_ACK_IDS = 500
    const val MIN_INTERVAL_MS = 15_000L
    const val MAX_INTERVAL_MS = 3_600_000L
    const val MIN_RADIUS_M = 500.0
    const val MAX_RADIUS_M = 10_000.0
    const val MAX_MEDIA_TEXT = 200
    const val MAX_MEDIA_ACTIONS = 8

    val CAPABILITIES = listOf("geo.watch", "audio.queue", "audio.geotrigger", "notify", "media.mirror", "library")

    private val ARTIFACT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val VOICE = Regex("^[A-Za-z0-9_-]{1,64}$")

    private val json = Json { isLenient = false }

    object Reason {
        const val INVALID = "invalid"
        const val UNSUPPORTED = "unsupported"
        const val DENIED = "denied"
        const val UNAVAILABLE = "unavailable"
        const val TIMEOUT = "timeout"
        const val LIMIT = "limit"
    }

    // ---- inbound -------------------------------------------------------------------------

    data class AudioItem(
        val id: String,
        val title: String,
        val text: String?,
        val url: String?,
        val voice: String?,
    )

    /** State of the viewer's Listen player, as mirrored by `media.session`. */
    enum class MediaState(val wire: String) {
        PLAYING("playing"),
        PAUSED("paused"),
        NONE("none"),
        ;

        companion object {
            fun of(wire: String): MediaState? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** Listen controls, in `media.session.actions` and `media.command.action`. */
    enum class MediaAction(val wire: String) {
        PLAY("play"),
        PAUSE("pause"),
        NEXT("next"),
        PREVIOUS("previous"),
        STOP("stop"),
        ;

        companion object {
            fun of(wire: String): MediaAction? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class TriggerItem(val item: AudioItem, val lat: Double, val lon: Double, val radiusM: Double)

    sealed interface Inbound {
        val artifactId: String?

        data object Hello : Inbound {
            override val artifactId: String? = null
        }

        data class GeoWatch(
            override val artifactId: String,
            val requestId: String,
            val intervalMs: Long,
            val highAccuracy: Boolean,
        ) : Inbound

        data class GeoUnwatch(override val artifactId: String, val requestId: String) : Inbound

        data class AudioQueue(override val artifactId: String, val requestId: String, val item: AudioItem) : Inbound

        data class AudioGeotrigger(
            override val artifactId: String,
            val requestId: String,
            val items: List<TriggerItem>,
        ) : Inbound

        data class AudioStop(override val artifactId: String) : Inbound

        data class NotifyShow(
            override val artifactId: String,
            val requestId: String,
            val title: String,
            val body: String,
        ) : Inbound

        data class PlayedAck(override val artifactId: String, val ids: List<String>) : Inbound

        /** The viewer's Listen player changed (ADR-0019 mirror). */
        data class MediaSession(
            override val artifactId: String,
            val state: MediaState,
            val title: String,
            val artist: String,
            val album: String,
            val actions: Set<MediaAction>,
        ) : Inbound
    }

    sealed interface ParseResult {
        data class Ok(val message: Inbound) : ParseResult
        data class Invalid(val artifactId: String?, val requestId: String?, val reason: String) : ParseResult
    }

    private class Reject(val reason: String = Reason.INVALID) : Exception()

    /** Parse one message. [origin] is the configured server origin (for same-origin audio URLs). */
    fun parse(raw: String, origin: String): ParseResult {
        if (raw.length > MAX_MESSAGE_CHARS) return ParseResult.Invalid(null, null, Reason.INVALID)
        val root = try {
            json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            return ParseResult.Invalid(null, null, Reason.INVALID)
        } catch (_: IllegalArgumentException) {
            return ParseResult.Invalid(null, null, Reason.INVALID)
        }
        if (root !is JsonObject) return ParseResult.Invalid(null, null, Reason.INVALID)
        // Echo identifiers only when they are themselves valid, so replies never reflect junk.
        val echoArtifact = (root["artifactId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { ARTIFACT_ID.matches(it) }
        val echoRequest = (root["requestId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { isValidId(it) }
        return try {
            ParseResult.Ok(parseObject(root, origin))
        } catch (reject: Reject) {
            ParseResult.Invalid(echoArtifact, echoRequest, reject.reason)
        }
    }

    private fun parseObject(o: JsonObject, origin: String): Inbound {
        val v = o["v"] as? JsonPrimitive ?: throw Reject()
        if (v.isString || v.longOrNull != VERSION.toLong() || v.content != "1") throw Reject()
        val type = string(o, "type")
        return when (type) {
            "hello" -> {
                keys(o, "v", "type")
                Inbound.Hello
            }
            "geo.watch" -> {
                keys(o, "v", "type", "artifactId", "requestId", "intervalMs", "highAccuracy")
                val interval = integer(o, "intervalMs")
                if (interval < MIN_INTERVAL_MS || interval > MAX_INTERVAL_MS) throw Reject()
                Inbound.GeoWatch(
                    artifactId = artifactId(o),
                    requestId = requestId(o),
                    intervalMs = interval,
                    highAccuracy = optionalBoolean(o, "highAccuracy") ?: false,
                )
            }
            "geo.unwatch" -> {
                keys(o, "v", "type", "artifactId", "requestId")
                Inbound.GeoUnwatch(artifactId(o), requestId(o))
            }
            "audio.queue" -> {
                keys(o, "v", "type", "artifactId", "requestId", "item")
                val item = o["item"] as? JsonObject ?: throw Reject()
                keys(item, "id", "title", "text", "url", "voice")
                Inbound.AudioQueue(artifactId(o), requestId(o), audioItem(item, origin))
            }
            "audio.geotrigger" -> {
                keys(o, "v", "type", "artifactId", "requestId", "items")
                val array = o["items"] as? JsonArray ?: throw Reject()
                if (array.size > MAX_TRIGGER_ITEMS) throw Reject(Reason.LIMIT)
                val items = array.map { element ->
                    val item = element as? JsonObject ?: throw Reject()
                    keys(item, "id", "title", "text", "url", "voice", "lat", "lon", "radius_m")
                    val lat = number(item, "lat")
                    val lon = number(item, "lon")
                    val radius = number(item, "radius_m")
                    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) throw Reject()
                    if (radius < MIN_RADIUS_M || radius > MAX_RADIUS_M) throw Reject()
                    TriggerItem(audioItem(item, origin), lat, lon, radius)
                }
                if (items.map { it.item.id }.toSet().size != items.size) throw Reject()
                Inbound.AudioGeotrigger(artifactId(o), requestId(o), items)
            }
            "audio.stop" -> {
                keys(o, "v", "type", "artifactId")
                Inbound.AudioStop(artifactId(o))
            }
            "notify.show" -> {
                keys(o, "v", "type", "artifactId", "requestId", "title", "body")
                val title = string(o, "title")
                val body = string(o, "body")
                if (!isValidTitle(title)) throw Reject()
                if (body.length > MAX_BODY || hasForbiddenControl(body)) throw Reject()
                Inbound.NotifyShow(artifactId(o), requestId(o), title, body)
            }
            "played.ack" -> {
                keys(o, "v", "type", "artifactId", "ids")
                val array = o["ids"] as? JsonArray ?: throw Reject()
                if (array.size > MAX_ACK_IDS) throw Reject(Reason.LIMIT)
                val ids = array.map { element ->
                    val p = element as? JsonPrimitive ?: throw Reject()
                    if (!p.isString || !isValidId(p.content)) throw Reject()
                    p.content
                }
                Inbound.PlayedAck(artifactId(o), ids)
            }
            "media.session" -> {
                keys(o, "v", "type", "artifactId", "state", "title", "artist", "album", "actions")
                val state = MediaState.of(string(o, "state")) ?: throw Reject()
                val actions = (o["actions"] ?: JsonArray(emptyList())) as? JsonArray ?: throw Reject()
                if (actions.size > MAX_MEDIA_ACTIONS) throw Reject(Reason.LIMIT)
                val parsedActions = actions.map { element ->
                    val p = element as? JsonPrimitive ?: throw Reject()
                    if (!p.isString) throw Reject()
                    MediaAction.of(p.content) ?: throw Reject()
                }.toCollection(LinkedHashSet())
                Inbound.MediaSession(
                    artifactId = artifactId(o),
                    state = state,
                    title = mediaText(o, "title"),
                    artist = mediaText(o, "artist"),
                    album = mediaText(o, "album"),
                    actions = if (state == MediaState.NONE) emptySet() else parsedActions,
                )
            }
            else -> throw Reject(Reason.UNSUPPORTED)
        }
    }

    /**
     * Optional display text of `media.session`: at most [MAX_MEDIA_TEXT] characters. Control
     * characters are replaced by spaces (the text is only shown, never interpreted).
     */
    private fun mediaText(o: JsonObject, key: String): String {
        val value = optionalString(o, key) ?: return ""
        if (value.length > MAX_MEDIA_TEXT) throw Reject()
        return value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    }

    private fun audioItem(o: JsonObject, origin: String): AudioItem {
        val id = string(o, "id")
        if (!isValidId(id)) throw Reject()
        val title = string(o, "title")
        if (!isValidTitle(title)) throw Reject()
        val text = optionalString(o, "text")
        val rawUrl = optionalString(o, "url")
        if ((text == null) == (rawUrl == null)) throw Reject()
        if (text != null && (text.isEmpty() || text.length > MAX_TEXT || hasForbiddenControl(text))) throw Reject()
        val url = rawUrl?.let { OriginPolicy.resolveSameOriginUrl(it, origin) ?: throw Reject() }
        val voice = optionalString(o, "voice")
        if (voice != null && !VOICE.matches(voice)) throw Reject()
        return AudioItem(id, title, text, url, voice)
    }

    fun isValidArtifactId(value: String): Boolean = ARTIFACT_ID.matches(value)

    fun isValidId(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_ID && value.none { it.isISOControl() } && value.isNotBlank()

    private fun isValidTitle(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_TITLE && value.none { it.isISOControl() }

    /** Mirrors the server's speech text rule: control characters other than \n and \t are rejected. */
    private fun hasForbiddenControl(value: String): Boolean =
        value.any { it.isISOControl() && it != '\n' && it != '\t' }

    private fun keys(o: JsonObject, vararg allowed: String) {
        val set = allowed.toSet()
        if (o.keys.any { it !in set }) throw Reject()
    }

    private fun artifactId(o: JsonObject): String {
        val id = string(o, "artifactId")
        if (!ARTIFACT_ID.matches(id)) throw Reject()
        return id
    }

    private fun requestId(o: JsonObject): String {
        val id = string(o, "requestId")
        if (!isValidId(id)) throw Reject()
        return id
    }

    private fun string(o: JsonObject, key: String): String {
        val p = o[key] as? JsonPrimitive ?: throw Reject()
        if (!p.isString) throw Reject()
        return p.content
    }

    private fun optionalString(o: JsonObject, key: String): String? {
        val element = o[key] ?: return null
        val p = element as? JsonPrimitive ?: throw Reject()
        if (!p.isString) throw Reject()
        return p.content
    }

    private fun optionalBoolean(o: JsonObject, key: String): Boolean? {
        val element = o[key] ?: return null
        val p = element as? JsonPrimitive ?: throw Reject()
        if (p.isString) throw Reject()
        return p.booleanOrNull ?: throw Reject()
    }

    private fun integer(o: JsonObject, key: String): Long {
        val p = o[key] as? JsonPrimitive ?: throw Reject()
        if (p.isString) throw Reject()
        return p.longOrNull ?: throw Reject()
    }

    private fun number(o: JsonObject, key: String): Double {
        val p = o[key] as? JsonPrimitive ?: throw Reject()
        if (p.isString || p.booleanOrNull != null) throw Reject()
        val d = p.doubleOrNull ?: throw Reject()
        if (!d.isFinite()) throw Reject()
        return d
    }

    // ---- outbound ------------------------------------------------------------------------

    private fun envelope(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("v", VERSION)
            put("type", type)
            body()
        }.toString()

    fun ready(appVersion: String): String = envelope("ready") {
        put("app", appVersion)
        put("capabilities", buildJsonArray { CAPABILITIES.forEach { add(it) } })
    }

    fun geoPosition(artifactId: String, requestId: String, lat: Double, lon: Double, accuracy: Double, timestamp: Long): String =
        envelope("geo.position") {
            put("artifactId", artifactId)
            put("requestId", requestId)
            put("lat", lat)
            put("lon", lon)
            put("accuracy", accuracy)
            put("timestamp", timestamp)
        }

    fun geoError(artifactId: String, requestId: String, reason: String): String = envelope("geo.error") {
        put("artifactId", artifactId)
        put("requestId", requestId)
        put("reason", reason)
    }

    fun audioState(artifactId: String, id: String, state: String): String = envelope("audio.state") {
        put("artifactId", artifactId)
        put("id", id)
        put("state", state)
    }

    fun audioPlayed(artifactId: String, id: String, at: Long): String = envelope("audio.played") {
        put("artifactId", artifactId)
        put("id", id)
        put("at", at)
    }

    fun mediaCommand(artifactId: String, action: MediaAction): String = envelope("media.command") {
        put("artifactId", artifactId)
        put("action", action.wire)
    }

    fun error(artifactId: String?, requestId: String?, reason: String): String = envelope("error") {
        put("artifactId", artifactId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("requestId", requestId?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("reason", reason)
    }

    /** Parse a JSON document produced by this object (used by tests and stores). */
    fun parseJson(raw: String): JsonElement = json.parseToJsonElement(raw)
}
