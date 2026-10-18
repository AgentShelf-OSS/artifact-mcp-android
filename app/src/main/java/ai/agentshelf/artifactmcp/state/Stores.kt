package ai.agentshelf.artifactmcp.state

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Minimal persistent string map, so the stores can be tested without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun keys(): Set<String>
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun keys(): Set<String> = map.keys.toSet()
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        // Synchronous commit: played state and revocations must survive an immediate process death.
        prefs.edit(commit = true) { if (value == null) remove(key) else putString(key, value) }
    }
    override fun keys(): Set<String> = prefs.all.keys
}

private val storeJson = Json { ignoreUnknownKeys = true }

private fun parseArray(raw: String?): JsonArray =
    raw?.let { runCatching { storeJson.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: JsonArray(emptyList())

/** Capabilities the app grants per artifact (in addition to the shell's own consent). */
enum class Capability(val key: String) {
    /** `geo.watch` and `audio.geotrigger`: location through the foreground service. */
    LOCATION("location"),
    /** `notify.show`: local notifications. */
    NOTIFY("notify");

    companion object {
        fun fromKey(key: String): Capability? = entries.firstOrNull { it.key == key }
    }
}

data class Grant(val artifactId: String, val capability: Capability, val label: String, val grantedAt: Long)

/** Per-artifact capability grants, revocable from Settings. */
class GrantStore(private val kv: KeyValueStore) {
    private fun key(artifactId: String) = "grant:$artifactId"

    fun isGranted(artifactId: String, capability: Capability): Boolean =
        load(artifactId).any { it.capability == capability }

    fun grant(artifactId: String, capability: Capability, label: String, now: Long) {
        val current = load(artifactId).filter { it.capability != capability } +
            Grant(artifactId, capability, label.take(120), now)
        save(artifactId, current)
    }

    fun revoke(artifactId: String, capability: Capability) {
        save(artifactId, load(artifactId).filter { it.capability != capability })
    }

    fun all(): List<Grant> = kv.keys().filter { it.startsWith("grant:") }
        .flatMap { load(it.removePrefix("grant:")) }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.artifactId }, { it.capability.ordinal }))

    fun clear() = kv.keys().filter { it.startsWith("grant:") }.forEach { kv.put(it, null) }

    private fun load(artifactId: String): List<Grant> = parseArray(kv.get(key(artifactId))).mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val cap = (o["capability"] as? JsonPrimitive)?.content?.let(Capability::fromKey) ?: return@mapNotNull null
        Grant(
            artifactId = artifactId,
            capability = cap,
            label = (o["label"] as? JsonPrimitive)?.content ?: artifactId,
            grantedAt = (o["grantedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }

    private fun save(artifactId: String, grants: List<Grant>) {
        if (grants.isEmpty()) {
            kv.put(key(artifactId), null)
            return
        }
        kv.put(key(artifactId), buildJsonArray {
            grants.forEach { g ->
                add(buildJsonObject {
                    put("capability", g.capability.key)
                    put("label", g.label)
                    put("grantedAt", g.grantedAt)
                })
            }
        }.toString())
    }
}

data class PlayedEvent(val artifactId: String, val id: String, val at: Long)

/**
 * Geotrigger played state. `played:<artifactId>` holds the ids that already fired (so they fire
 * only once, across process death); `pending_played` holds `audio.played` events the shell has
 * not acknowledged yet.
 */
class PlayedStore(private val kv: KeyValueStore) {
    companion object {
        const val MAX_PLAYED_PER_ARTIFACT = 500
        const val MAX_PENDING = 1000
        private const val PENDING_KEY = "pending_played"
    }

    private fun key(artifactId: String) = "played:$artifactId"

    fun playedIds(artifactId: String): Set<String> =
        parseArray(kv.get(key(artifactId))).mapNotNull { (it as? JsonPrimitive)?.content }.toSet()

    /** Record that [id] fired. Returns false when it had already fired. */
    fun markPlayed(artifactId: String, id: String, at: Long): Boolean {
        val ids = parseArray(kv.get(key(artifactId))).mapNotNull { (it as? JsonPrimitive)?.content }
        if (id in ids) return false
        val updated = (ids + id).takeLast(MAX_PLAYED_PER_ARTIFACT)
        kv.put(key(artifactId), buildJsonArray { updated.forEach { add(JsonPrimitive(it)) } }.toString())
        val pending = (pending() + PlayedEvent(artifactId, id, at)).takeLast(MAX_PENDING)
        savePending(pending)
        return true
    }

    fun pending(): List<PlayedEvent> = parseArray(kv.get(PENDING_KEY)).mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val a = (o["artifactId"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val at = o["at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        PlayedEvent(a, id, at)
    }

    fun pendingFor(artifactId: String): List<PlayedEvent> = pending().filter { it.artifactId == artifactId }

    fun acknowledge(artifactId: String, ids: Collection<String>) {
        val drop = ids.toSet()
        savePending(pending().filterNot { it.artifactId == artifactId && it.id in drop })
    }

    fun clear() {
        kv.keys().filter { it.startsWith("played:") || it == PENDING_KEY }.forEach { kv.put(it, null) }
    }

    private fun savePending(events: List<PlayedEvent>) {
        if (events.isEmpty()) {
            kv.put(PENDING_KEY, null)
            return
        }
        kv.put(PENDING_KEY, buildJsonArray {
            events.forEach { e ->
                add(buildJsonObject {
                    put("artifactId", e.artifactId)
                    put("id", e.id)
                    put("at", e.at)
                })
            }
        }.toString())
    }
}

data class Notice(val artifactId: String, val requestId: String, val reason: String)

/**
 * `geo.error` notices for requests the app ended while the shell could not hear about it
 * (revoked in Settings, stopped from the notification). Delivered on the artifact's next hello.
 */
class NoticeStore(private val kv: KeyValueStore) {
    private companion object {
        const val KEY = "notices"
        const val MAX = 200
    }

    fun add(notice: Notice) {
        val all = (load().filterNot { it.artifactId == notice.artifactId && it.requestId == notice.requestId } + notice)
            .takeLast(MAX)
        save(all)
    }

    /** Remove and return the notices for [artifactId]. */
    fun take(artifactId: String): List<Notice> {
        val all = load()
        val (mine, rest) = all.partition { it.artifactId == artifactId }
        if (mine.isNotEmpty()) save(rest)
        return mine
    }

    fun load(): List<Notice> = parseArray(kv.get(KEY)).mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        Notice(
            (o["artifactId"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
            (o["requestId"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
            (o["reason"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
        )
    }

    fun clear() = kv.put(KEY, null)

    private fun save(notices: List<Notice>) {
        if (notices.isEmpty()) {
            kv.put(KEY, null)
            return
        }
        kv.put(KEY, buildJsonArray {
            notices.forEach { n ->
                add(buildJsonObject {
                    put("artifactId", n.artifactId)
                    put("requestId", n.requestId)
                    put("reason", n.reason)
                })
            }
        }.toString())
    }
}

/** The configured server origin. */
class OriginStore(private val kv: KeyValueStore) {
    var origin: String?
        get() = kv.get("origin")
        set(value) = kv.put("origin", value)
}
