package ai.agentshelf.artifactmcp.library

import ai.agentshelf.artifactmcp.audio.HttpRequestSpec
import ai.agentshelf.artifactmcp.audio.SpeechException
import ai.agentshelf.artifactmcp.audio.SpeechRequests
import ai.agentshelf.artifactmcp.bridge.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException

/** One artifact in the car library (`GET /api/library`, ADR-0019). */
data class LibraryArtifact(val id: String, val title: String, val category: String)

data class LibraryOrg(val org: String, val artifacts: List<LibraryArtifact>)

data class Library(val orgs: List<LibraryOrg>) {
    fun find(artifactId: String): LibraryArtifact? = orgs.firstNotNullOfOrNull { org -> org.artifacts.firstOrNull { it.id == artifactId } }
    fun org(name: String): LibraryOrg? = orgs.firstOrNull { it.org == name }
}

/** What the browse tree shows for the library. */
sealed interface LibraryView {
    data class Ready(val library: Library, val fromCache: Boolean) : LibraryView
    /** The server answered 403 `not_signed_in` (or redirected to the login page). */
    data object SignedOut : LibraryView
    /** No answer and nothing cached. */
    data object Unavailable : LibraryView
}

object LibraryJson {
    const val MAX_PER_ORG = 200
    const val MAX_ORGS = 50
    const val MAX_TITLE = 200
    const val MAX_ORG = 64
    const val MAX_CATEGORY = 80
    private const val CACHE_VERSION = 1

    private fun text(value: Any?, max: Int): String? {
        val p = value as? JsonPrimitive ?: return null
        if (!p.isString) return null
        return p.content.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(max)
    }

    /**
     * Parse the server's library document. Entries with an invalid id are skipped; titles and
     * categories are cleaned of control characters and capped. Returns null if the document is
     * not a library at all.
     */
    fun parse(raw: String): Library? {
        val root = runCatching { Protocol.parseJson(raw) }.getOrNull() as? JsonObject ?: return null
        val orgs = root["orgs"] as? JsonArray ?: return null
        val seen = HashSet<String>()
        val parsed = orgs.asSequence().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = text(o["org"], MAX_ORG)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!seen.add(name)) return@mapNotNull null
            val artifacts = (o["artifacts"] as? JsonArray).orEmpty().asSequence().mapNotNull { item ->
                val a = item as? JsonObject ?: return@mapNotNull null
                val id = (a["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                if (!Protocol.isValidArtifactId(id)) return@mapNotNull null
                val title = text(a["title"], MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: id
                LibraryArtifact(id, title, text(a["category"], MAX_CATEGORY).orEmpty())
            }.distinctBy { it.id }.take(MAX_PER_ORG).toList()
            LibraryOrg(name, artifacts)
        }.take(MAX_ORGS).toList()
        return Library(parsed)
    }

    /** Cache document: the library plus the origin it came from. */
    fun encodeCache(origin: String, library: Library): String = buildJsonObject {
        put("cache", CACHE_VERSION)
        put("origin", origin)
        putJsonArray("orgs") {
            library.orgs.forEach { org ->
                addJsonObject {
                    put("org", org.org)
                    putJsonArray("artifacts") {
                        org.artifacts.forEach { a ->
                            addJsonObject {
                                put("id", a.id)
                                put("title", a.title)
                                put("category", a.category)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    /** The cached library, only if it was fetched from [origin]. */
    fun decodeCache(raw: String, origin: String): Library? {
        val root = runCatching { Protocol.parseJson(raw) }.getOrNull() as? JsonObject ?: return null
        if ((root["cache"] as? JsonPrimitive)?.content != CACHE_VERSION.toString()) return null
        if ((root["origin"] as? JsonPrimitive)?.content != origin) return null
        return parse(raw)
    }
}

object LibraryRequests {
    const val PATH = "/api/library"

    /** Same-origin GET with the WebView session cookie, like the speech requests. */
    fun library(origin: String, cookie: String?, userAgent: String?): HttpRequestSpec {
        val headers = linkedMapOf("Accept" to "application/json")
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        return HttpRequestSpec(origin + PATH, "GET", headers, null)
    }
}

/** Fetches a request and returns (status, body). Blocking; called on an IO thread. */
fun interface LibraryFetcher {
    @Throws(IOException::class)
    fun fetch(spec: HttpRequestSpec): Pair<Int, String?>
}

/**
 * The library for the car: fetched on browse with a short timeout, cached on disk so the car
 * shows the last good list when the phone is offline.
 */
class LibraryRepository(
    private val cacheFile: File,
    private val origin: () -> String?,
    private val cookies: (String) -> String?,
    private val userAgent: () -> String?,
    private val fetcher: LibraryFetcher,
    private val timeoutMs: Long = 4_000L,
) {
    @Volatile var last: LibraryView? = null
        private set

    fun cached(): Library? {
        val currentOrigin = origin() ?: return null
        val raw = runCatching { cacheFile.readText() }.getOrNull() ?: return null
        return LibraryJson.decodeCache(raw, currentOrigin)
    }

    /** Fetch now; fall back to the cache on network errors and timeouts. */
    suspend fun refresh(): LibraryView {
        val currentOrigin = origin() ?: return LibraryView.Unavailable.also { last = it }
        val spec = LibraryRequests.library(currentOrigin, cookies(currentOrigin), userAgent())
        val response = withTimeoutOrNull(timeoutMs) {
            runCatching { runInterruptible(Dispatchers.IO) { fetcher.fetch(spec) } }.getOrNull()
        }
        val view = interpret(currentOrigin, response)
        last = view
        return view
    }

    private suspend fun interpret(currentOrigin: String, response: Pair<Int, String?>?): LibraryView {
        val fallback = { runCatching { cached() }.getOrNull()?.let { LibraryView.Ready(it, fromCache = true) } ?: LibraryView.Unavailable }
        if (response == null) return runInterruptible(Dispatchers.IO) { fallback() }
        val (status, body) = response
        if (SpeechRequests.classify(status) == SpeechException.Kind.SIGNED_OUT) return LibraryView.SignedOut
        if (status !in 200..299) return runInterruptible(Dispatchers.IO) { fallback() }
        val library = body?.let(LibraryJson::parse) ?: return runInterruptible(Dispatchers.IO) { fallback() }
        runInterruptible(Dispatchers.IO) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.parentFile, cacheFile.name + ".part")
                tmp.writeText(LibraryJson.encodeCache(currentOrigin, library))
                if (!tmp.renameTo(cacheFile)) tmp.delete()
            }
        }
        return LibraryView.Ready(library, fromCache = false)
    }

    fun clear() {
        last = null
        cacheFile.delete()
    }
}
