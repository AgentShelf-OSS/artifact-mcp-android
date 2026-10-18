package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.bridge.Protocol
import ai.agentshelf.artifactmcp.config.OriginPolicy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A fully described HTTP request, built separately from I/O so it can be unit-tested. */
data class HttpRequestSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpRequestSpec && url == other.url && method == other.method && headers == other.headers &&
            (body?.contentEquals(other.body) ?: (other.body == null))

    override fun hashCode(): Int = url.hashCode() * 31 + method.hashCode()
}

class SpeechException(val kind: Kind, message: String) : IOException(message) {
    enum class Kind { SIGNED_OUT, BUSY, UNAVAILABLE, REJECTED, NETWORK, TOO_LARGE }
}

/**
 * Requests that carry the viewer session to the server, outside the WebView.
 *
 * The server's request-authenticity gate (`same_origin_gate`) accepts a cookie-authenticated
 * POST only with `X-Artifact-Mutation: 1` and either `Sec-Fetch-Site: same-origin` or an
 * `Origin` equal to the public origin. A native client sends no Fetch Metadata, so it sends
 * `Origin`. Cloudflare Access accepts the `CF_Authorization` cookie from the WebView jar.
 */
object SpeechRequests {
    const val MUTATION_HEADER = "X-Artifact-Mutation"

    fun speech(origin: String, artifactId: String, text: String, voice: String, cookie: String?, userAgent: String?): HttpRequestSpec {
        require(Protocol.isValidArtifactId(artifactId)) { "invalid artifact id" }
        val body = buildJsonObject {
            put("text", text)
            put("voice", voice)
        }.toString().toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "audio/wav",
            "Origin" to origin,
            MUTATION_HEADER to "1",
        )
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        return HttpRequestSpec("$origin/$artifactId/speech", "POST", headers, body)
    }

    fun voices(origin: String, artifactId: String, cookie: String?, userAgent: String?): HttpRequestSpec {
        require(Protocol.isValidArtifactId(artifactId)) { "invalid artifact id" }
        val headers = linkedMapOf("Accept" to "application/json")
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        return HttpRequestSpec("$origin/$artifactId/speech/voices", "GET", headers, null)
    }

    /** GET for a same-origin audio URL. Returns null if [url] is not on [origin]. */
    fun audio(origin: String, url: String, cookie: String?, userAgent: String?): HttpRequestSpec? {
        if (!OriginPolicy.isSameOrigin(url, origin)) return null
        val headers = linkedMapOf("Accept" to "audio/*")
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        return HttpRequestSpec(url, "GET", headers, null)
    }

    /** The first voice id in a `/speech/voices` response, or null when speech is disabled. */
    fun firstVoice(json: String): String? {
        val root = runCatching { Protocol.parseJson(json) }.getOrNull() as? JsonObject ?: return null
        if ((root["enabled"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val voices = root["voices"] as? JsonArray ?: return null
        return voices.asSequence()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .firstOrNull { Regex("^[A-Za-z0-9_-]{1,64}$").matches(it) }
    }

    fun classify(status: Int): SpeechException.Kind? = when {
        status in 200..299 -> null
        // Redirects lead to the Access login page: the session cookie is missing or expired.
        status in 300..399 || status == 401 || status == 403 -> SpeechException.Kind.SIGNED_OUT
        status == 429 || status == 503 -> SpeechException.Kind.BUSY
        status == 413 -> SpeechException.Kind.TOO_LARGE
        status in 400..499 -> SpeechException.Kind.REJECTED
        else -> SpeechException.Kind.UNAVAILABLE
    }
}

/** Executes [HttpRequestSpec]s with HttpURLConnection. Redirects are never followed. */
class HttpExecutor(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 120_000) {
    data class Response(val status: Int, val contentType: String?, val retryAfterSeconds: Long?)

    /** Run [spec]; on 2xx stream the body to [sink] (bounded by [maxBytes]). */
    fun execute(spec: HttpRequestSpec, maxBytes: Long, sink: (InputStream) -> Unit): Response {
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            requestMethod = spec.method
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            spec.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            spec.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val response = Response(
                status,
                connection.contentType,
                connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull(),
            )
            if (status in 200..299) {
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw SpeechException(SpeechException.Kind.TOO_LARGE, "response too large")
                connection.inputStream.use { sink(BoundedInputStream(it, maxBytes)) }
            }
            return response
        } catch (e: SpeechException) {
            throw e
        } catch (e: IOException) {
            throw SpeechException(SpeechException.Kind.NETWORK, e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }
}

private class BoundedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var count = 0L
    override fun read(): Int {
        val b = inner.read()
        if (b >= 0 && ++count > limit) throw SpeechException(SpeechException.Kind.TOO_LARGE, "response too large")
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) {
            count += n
            if (count > limit) throw SpeechException(SpeechException.Kind.TOO_LARGE, "response too large")
        }
        return n
    }
    override fun close() = inner.close()
}

/** Writes a stream to [target] atomically (temp file, then rename). */
internal fun writeAtomically(target: File, input: InputStream) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".part")
    try {
        tmp.outputStream().use { input.copyTo(it) }
        if (!tmp.renameTo(target)) throw IOException("rename failed")
    } finally {
        tmp.delete()
    }
}
