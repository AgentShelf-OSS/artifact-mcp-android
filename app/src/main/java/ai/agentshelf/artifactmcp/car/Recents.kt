package ai.agentshelf.artifactmcp.car

import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.state.KeyValueStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * An artifact listened to recently, for the car's Continue tab.
 *
 * [fraction] is the played share (0..1) when it is known. `media.session` does not say how far
 * into an artifact Listen is (no section index or count), so today it stays null and the car
 * shows the item as partly played.
 */
data class RecentItem(
    val origin: String,
    val artifactId: String,
    val title: String,
    val org: String,
    val category: String,
    val color: String?,
    val at: Long,
    val fraction: Double? = null,
)

/** Up to [MAX] recent artifacts, newest first, kept per server origin. */
class RecentStore(private val kv: KeyValueStore) {
    companion object {
        const val MAX = 20
        private const val KEY = "car_recent"
        private const val MAX_TEXT = 200

        /** Do not rewrite an unchanged entry more often than this. */
        const val MIN_TOUCH_MS = 60_000L
    }

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun all(): List<RecentItem> {
        val raw = kv.get(KEY) ?: return emptyList()
        val array = runCatching { Protocol.parseJson(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun s(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val origin = s("origin") ?: return@mapNotNull null
            val id = s("artifactId")?.takeIf(Protocol::isValidArtifactId) ?: return@mapNotNull null
            RecentItem(
                origin = origin,
                artifactId = id,
                title = s("title")?.take(MAX_TEXT)?.ifBlank { null } ?: id,
                org = s("org").orEmpty().take(MAX_TEXT),
                category = s("category").orEmpty().take(MAX_TEXT),
                color = s("color"),
                at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                fraction = (o["fraction"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(0.0, 1.0),
            )
        }.sortedByDescending { it.at }.take(MAX)
    }

    /** Recent items of [origin], newest first. */
    fun forOrigin(origin: String?): List<RecentItem> = if (origin == null) emptyList() else all().filter { it.origin == origin }

    /**
     * Record that [item] is playing: it moves to the front. An unchanged entry touched within
     * [MIN_TOUCH_MS] is not rewritten (`media.session` arrives on every section change).
     */
    fun record(item: RecentItem) {
        val current = all()
        val existing = current.firstOrNull { it.origin == item.origin && it.artifactId == item.artifactId }
        val merged = item.copy(fraction = item.fraction ?: existing?.fraction)
        if (existing != null && current.first() == existing && existing.copy(at = merged.at) == merged && merged.at - existing.at < MIN_TOUCH_MS) return
        val next = (listOf(merged) + current.filterNot { it.origin == item.origin && it.artifactId == item.artifactId }).take(MAX)
        save(next)
        listeners.toList().forEach { it() }
    }

    fun clear() {
        kv.put(KEY, null)
        listeners.toList().forEach { it() }
    }

    private fun save(items: List<RecentItem>) {
        kv.put(KEY, buildJsonArray {
            items.forEach { r ->
                addJsonObject {
                    put("origin", r.origin)
                    put("artifactId", r.artifactId)
                    put("title", r.title)
                    put("org", r.org)
                    put("category", r.category)
                    r.color?.let { put("color", it) }
                    put("at", r.at)
                    r.fraction?.let { put("fraction", it) }
                }
            }
        }.toString())
    }
}
