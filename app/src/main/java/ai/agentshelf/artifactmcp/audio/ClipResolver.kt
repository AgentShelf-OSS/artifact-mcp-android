package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.bridge.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns an audio item into a local file: `text` through the artifact's speech route, `url`
 * through a same-origin GET. Clips are cached per item (keyed by artifact, item id, content and
 * voice), so geotrigger items synthesized while the viewer was open play offline later.
 */
class ClipResolver(
    private val dir: File,
    private val origin: () -> String?,
    private val cookies: (String) -> String?,
    private val userAgent: () -> String?,
    private val http: HttpExecutor = HttpExecutor(),
    private val maxCacheBytes: Long = 200L * 1024 * 1024,
) {
    companion object {
        const val MAX_CLIP_BYTES = 32L * 1024 * 1024
        private const val MAX_ATTEMPTS = 4

        fun cacheKey(artifactId: String, item: Protocol.AudioItem): String {
            val material = listOf(artifactId, item.id, item.text ?: "", item.url ?: "", item.voice ?: "").joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val defaultVoices = ConcurrentHashMap<String, String>()

    fun cachedFile(artifactId: String, item: Protocol.AudioItem): File = File(dir, cacheKey(artifactId, item) + ".clip")

    suspend fun resolve(artifactId: String, item: Protocol.AudioItem): File = withContext(Dispatchers.IO) {
        val target = cachedFile(artifactId, item)
        val lock = locks.getOrPut(target.name) { Mutex() }
        lock.withLock {
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val currentOrigin = origin() ?: throw SpeechException(SpeechException.Kind.UNAVAILABLE, "no origin")
            if (item.text != null) {
                val voice = item.voice ?: defaultVoice(currentOrigin, artifactId)
                fetch(target) {
                    SpeechRequests.speech(currentOrigin, artifactId, item.text, voice, cookies(currentOrigin), userAgent())
                }
            } else {
                val url = item.url ?: throw SpeechException(SpeechException.Kind.REJECTED, "no content")
                fetch(target) {
                    SpeechRequests.audio(currentOrigin, url, cookies(currentOrigin), userAgent())
                        ?: throw SpeechException(SpeechException.Kind.REJECTED, "cross-origin url")
                }
            }
            trim()
            target
        }
    }

    private suspend fun defaultVoice(origin: String, artifactId: String): String {
        defaultVoices[artifactId]?.let { return it }
        val spec = SpeechRequests.voices(origin, artifactId, cookies(origin), userAgent())
        var body = ""
        val response = http.execute(spec, 64 * 1024) { body = it.readBytes().toString(Charsets.UTF_8) }
        SpeechRequests.classify(response.status)?.let { throw SpeechException(it, "voices ${response.status}") }
        val voice = SpeechRequests.firstVoice(body) ?: throw SpeechException(SpeechException.Kind.UNAVAILABLE, "speech disabled")
        defaultVoices[artifactId] = voice
        return voice
    }

    private suspend fun fetch(target: File, spec: () -> HttpRequestSpec) {
        var attempt = 0
        while (true) {
            attempt++
            val response = http.execute(spec(), MAX_CLIP_BYTES) { writeAtomically(target, it) }
            val kind = SpeechRequests.classify(response.status)
            if (kind == null) {
                val type = response.contentType?.lowercase().orEmpty()
                if (!type.startsWith("audio/")) {
                    target.delete()
                    throw SpeechException(SpeechException.Kind.REJECTED, "not audio")
                }
                return
            }
            if (kind == SpeechException.Kind.BUSY && attempt < MAX_ATTEMPTS) {
                delay(((response.retryAfterSeconds ?: 2L).coerceIn(1L, 10L)) * 1000L)
                continue
            }
            throw SpeechException(kind, "status ${response.status}")
        }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".clip") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxCacheBytes) break
            total -= file.length()
            file.delete()
        }
    }

    fun clear() {
        dir.deleteRecursively()
        defaultVoices.clear()
    }
}
