package ai.agentshelf.artifactmcp.config

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Origin rules for the configured Artifact MCP server.
 *
 * An origin is always serialized as `https://host[:port]` with a lower-case host and without a
 * default port, which is also the form WebView reports as a message source origin.
 */
object OriginPolicy {
    private val ARTIFACT_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Hosts that serve Cloudflare Access login pages for a team. */
    private const val ACCESS_HOST_SUFFIX = ".cloudflareaccess.com"

    /** Google sign-in rejects embedded WebViews (`disallowed_useragent`). */
    private val GOOGLE_SIGN_IN_HOSTS = setOf("accounts.google.com", "accounts.youtube.com")

    sealed interface Parsed {
        data class Valid(val origin: String) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    /** Validate user input for the server origin. Only an https origin without path is accepted. */
    fun normalize(input: String): Parsed {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Parsed.Invalid("empty")
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return Parsed.Invalid("malformed")
        }
        if (!uri.isAbsolute || uri.isOpaque) return Parsed.Invalid("malformed")
        if (!"https".equals(uri.scheme, ignoreCase = true)) return Parsed.Invalid("https_required")
        if (uri.rawUserInfo != null) return Parsed.Invalid("userinfo")
        val host = uri.host?.lowercase(Locale.ROOT) ?: return Parsed.Invalid("malformed")
        if (host.isEmpty()) return Parsed.Invalid("malformed")
        val path = uri.rawPath ?: ""
        if (path.isNotEmpty() && path != "/") return Parsed.Invalid("path")
        if (uri.rawQuery != null || uri.rawFragment != null) return Parsed.Invalid("path")
        val port = uri.port
        if (port == 0 || port > 65535) return Parsed.Invalid("port")
        return Parsed.Valid(serialize(host, port))
    }

    /** Origin of an absolute URL, or null when it is not http(s). */
    fun originOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme == "http") {
            return if (uri.port == -1 || uri.port == 80) "http://$host" else "http://$host:${uri.port}"
        }
        return serialize(host, uri.port)
    }

    fun isSameOrigin(url: String, origin: String): Boolean = originOf(url) == origin

    /**
     * Resolve a same-origin audio URL. Absolute URLs must match [origin]; root-relative paths
     * are resolved against it. Anything else (other origins, http, protocol-relative, userinfo)
     * is rejected.
     */
    fun resolveSameOriginUrl(raw: String, origin: String): String? {
        if (raw.isEmpty() || raw.length > 2048) return null
        if (raw.any { it.isWhitespace() || it.isISOControl() }) return null
        if (raw.startsWith("//")) return null
        val candidate = if (raw.startsWith("/")) origin + raw else raw
        val uri = try {
            URI(candidate)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.rawUserInfo != null) return null
        if (originOf(candidate) != origin) return null
        return uri.toASCIIString()
    }

    /**
     * Artifact id addressed by a viewer URL on [origin] (`<origin>/<artifactId>`, optionally
     * with a query or fragment), or null.
     */
    fun viewerArtifactId(url: String?, origin: String): String? {
        if (url == null || !isSameOrigin(url, origin)) return null
        val path = try {
            URI(url).rawPath ?: return null
        } catch (_: URISyntaxException) {
            return null
        }
        val segment = path.removePrefix("/").substringBefore('/')
        return segment.takeIf { ARTIFACT_ID.matches(it) }
    }

    fun viewerUrl(origin: String, artifactId: String): String {
        require(ARTIFACT_ID.matches(artifactId)) { "invalid artifact id" }
        return "$origin/$artifactId"
    }

    /**
     * Validate a launch target such as a notification tap. Only the exact viewer URL form
     * `<origin>/<artifactId>` is accepted; other apps can send intents to the exported
     * launcher activity, so anything else is ignored.
     */
    fun isViewerTarget(url: String?, origin: String): Boolean {
        if (url == null) return false
        val id = viewerArtifactId(url, origin) ?: return false
        return url == "$origin/$id"
    }

    enum class Navigation {
        /** Load in the viewer WebView. */
        IN_APP,
        /** Google sign-in: blocked inside WebViews, explain the alternative. */
        BLOCKED_GOOGLE_SIGN_IN,
        /** Hand off to another app or a Custom Tab. */
        EXTERNAL,
        /** Drop silently (javascript:, file:, intent:, data:, ...). */
        DENY,
    }

    /**
     * Classify a top-level navigation. [loginInProgress] is true while the viewer is inside the
     * deployment's sign-in flow (it has left the origin for a Cloudflare Access page); identity
     * provider pages then stay in the WebView so the session cookie lands in its cookie jar.
     */
    fun classifyNavigation(url: String, origin: String, loginInProgress: Boolean): Navigation {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return Navigation.DENY
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Navigation.DENY
        return when (scheme) {
            "https" -> {
                val host = uri.host?.lowercase(Locale.ROOT) ?: return Navigation.DENY
                when {
                    originOf(url) == origin -> Navigation.IN_APP
                    host in GOOGLE_SIGN_IN_HOSTS && loginInProgress -> Navigation.BLOCKED_GOOGLE_SIGN_IN
                    host.endsWith(ACCESS_HOST_SUFFIX) -> Navigation.IN_APP
                    loginInProgress -> Navigation.IN_APP
                    else -> Navigation.EXTERNAL
                }
            }
            "http" -> Navigation.EXTERNAL
            "geo", "google.navigation", "mailto", "tel", "sms" -> Navigation.EXTERNAL
            else -> Navigation.DENY
        }
    }

    fun isAccessLoginHost(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase(Locale.ROOT)
        } catch (_: URISyntaxException) {
            null
        } ?: return false
        return url.startsWith("https://", ignoreCase = true) && host.endsWith(ACCESS_HOST_SUFFIX)
    }

    private fun serialize(host: String, port: Int): String {
        val printableHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port == -1 || port == 443) "https://$printableHost" else "https://$printableHost:$port"
    }
}
