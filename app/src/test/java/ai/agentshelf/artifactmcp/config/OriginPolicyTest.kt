package ai.agentshelf.artifactmcp.config

import ai.agentshelf.artifactmcp.config.OriginPolicy.Navigation
import ai.agentshelf.artifactmcp.config.OriginPolicy.Parsed
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OriginPolicyTest {
    private val origin = "https://artifact.example.com"

    private fun valid(input: String) = (OriginPolicy.normalize(input) as Parsed.Valid).origin
    private fun reason(input: String) = (OriginPolicy.normalize(input) as Parsed.Invalid).reason

    @Test fun `normalizes https origins`() {
        assertThat(valid("https://artifact.example.com")).isEqualTo(origin)
        assertThat(valid("  https://Artifact.Example.COM/ ")).isEqualTo(origin)
        assertThat(valid("https://artifact.example.com:443")).isEqualTo(origin)
        assertThat(valid("https://artifact.example.com:8443")).isEqualTo("https://artifact.example.com:8443")
        assertThat(valid("HTTPS://artifact.example.com")).isEqualTo(origin)
    }

    @Test fun `rejects anything that is not a bare https origin`() {
        assertThat(reason("http://artifact.example.com")).isEqualTo("https_required")
        assertThat(reason("artifact.example.com")).isEqualTo("malformed")
        assertThat(reason("https://artifact.example.com/gallery")).isEqualTo("path")
        assertThat(reason("https://artifact.example.com/?x=1")).isEqualTo("path")
        assertThat(reason("https://artifact.example.com/#x")).isEqualTo("path")
        assertThat(reason("https://user:pw@artifact.example.com")).isEqualTo("userinfo")
        assertThat(reason("")).isEqualTo("empty")
        assertThat(reason("https://")).isEqualTo("malformed")
        assertThat(reason("javascript:alert(1)")).isEqualTo("malformed")
        assertThat(reason("file:///sdcard/x")).isEqualTo("https_required")
    }

    @Test fun `loopback http is accepted only when allowed (debug builds)`() {
        fun debug(input: String) = OriginPolicy.normalize(input, allowLoopbackHttp = true)
        assertThat(debug("http://localhost:8480")).isEqualTo(Parsed.Valid("http://localhost:8480"))
        assertThat(debug("http://127.0.0.1:8480/")).isEqualTo(Parsed.Valid("http://127.0.0.1:8480"))
        assertThat(debug("http://LOCALHOST")).isEqualTo(Parsed.Valid("http://localhost"))
        assertThat(debug("https://artifact.example.com")).isEqualTo(Parsed.Valid(origin))
        // Never another host over http, and the usual path and userinfo rules still apply.
        assertThat(debug("http://artifact.example.com")).isEqualTo(Parsed.Invalid("https_required"))
        assertThat(debug("http://localhost.evil.net:8480")).isEqualTo(Parsed.Invalid("https_required"))
        assertThat(debug("http://10.0.2.2:8480")).isEqualTo(Parsed.Invalid("https_required"))
        assertThat(debug("http://localhost:8480/x")).isEqualTo(Parsed.Invalid("path"))
        assertThat(debug("http://u:p@localhost:8480")).isEqualTo(Parsed.Invalid("userinfo"))
    }

    @Test fun `release builds reject a loopback http origin`() {
        // Release builds pass BuildConfig.DEBUG == false (SetupActivity and MainActivity); the
        // default is the same. Release resources also forbid cleartext (network_security_config).
        for (input in listOf("http://localhost:8480", "http://127.0.0.1:8480", "http://localhost")) {
            assertThat(OriginPolicy.normalize(input, allowLoopbackHttp = false)).isEqualTo(Parsed.Invalid("https_required"))
            assertThat(reason(input)).isEqualTo("https_required")
        }
        // A stored http origin (for example left by a debug build) is not accepted by a release viewer.
        assertThat(OriginPolicy.isAccepted("http://localhost:8480", allowLoopbackHttp = false)).isFalse()
        assertThat(OriginPolicy.isAccepted("http://localhost:8480", allowLoopbackHttp = true)).isTrue()
        assertThat(OriginPolicy.isAccepted(origin)).isTrue()
        assertThat(OriginPolicy.isAccepted("https://Artifact.example.com/")).isFalse()
    }

    @Test fun `a loopback http origin keeps its own pages in the app`() {
        val local = "http://localhost:8480"
        assertThat(OriginPolicy.classifyNavigation("$local/abc", local, false)).isEqualTo(Navigation.IN_APP)
        assertThat(OriginPolicy.classifyNavigation("http://localhost:9999/abc", local, false)).isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.classifyNavigation("http://example.com/", local, false)).isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.viewerArtifactId("$local/trip_1", local)).isEqualTo("trip_1")
        assertThat(OriginPolicy.isViewerTarget("$local/trip_1", local)).isTrue()
    }

    @Test fun `same origin comparison`() {
        assertThat(OriginPolicy.isSameOrigin("https://artifact.example.com/abc?x=1", origin)).isTrue()
        assertThat(OriginPolicy.isSameOrigin("https://ARTIFACT.example.com:443/abc", origin)).isTrue()
        assertThat(OriginPolicy.isSameOrigin("http://artifact.example.com/abc", origin)).isFalse()
        assertThat(OriginPolicy.isSameOrigin("https://artifact.example.com.evil.net/", origin)).isFalse()
        assertThat(OriginPolicy.isSameOrigin("https://artifact.example.com:8443/", origin)).isFalse()
        assertThat(OriginPolicy.isSameOrigin("not a url", origin)).isFalse()
    }

    @Test fun `viewer artifact id comes from the first path segment`() {
        assertThat(OriginPolicy.viewerArtifactId("$origin/trip_1", origin)).isEqualTo("trip_1")
        assertThat(OriginPolicy.viewerArtifactId("$origin/trip_1?tab=map#x", origin)).isEqualTo("trip_1")
        assertThat(OriginPolicy.viewerArtifactId("$origin/", origin)).isNull()
        assertThat(OriginPolicy.viewerArtifactId("https://evil.example/trip_1", origin)).isNull()
        assertThat(OriginPolicy.viewerArtifactId(null, origin)).isNull()
    }

    @Test fun `launch targets must be exact viewer urls`() {
        assertThat(OriginPolicy.isViewerTarget("$origin/trip_1", origin)).isTrue()
        assertThat(OriginPolicy.isViewerTarget("$origin/trip_1/speech", origin)).isFalse()
        assertThat(OriginPolicy.isViewerTarget("$origin/trip_1?x=javascript:1", origin)).isFalse()
        assertThat(OriginPolicy.isViewerTarget("https://evil.example/trip_1", origin)).isFalse()
        assertThat(OriginPolicy.isViewerTarget("javascript:alert(1)", origin)).isFalse()
    }

    @Test fun `navigation classification`() {
        assertThat(OriginPolicy.classifyNavigation("$origin/abc", origin, false)).isEqualTo(Navigation.IN_APP)
        assertThat(OriginPolicy.classifyNavigation("https://team.cloudflareaccess.com/cdn-cgi/access/login/x", origin, false))
            .isEqualTo(Navigation.IN_APP)
        assertThat(OriginPolicy.classifyNavigation("https://accounts.google.com/o/oauth2/auth?x", origin, true))
            .isEqualTo(Navigation.BLOCKED_GOOGLE_SIGN_IN)
        assertThat(OriginPolicy.classifyNavigation("https://github.com/login/oauth/authorize", origin, true))
            .isEqualTo(Navigation.IN_APP)
        assertThat(OriginPolicy.classifyNavigation("https://www.google.com/maps/dir/?api=1&destination=x", origin, false))
            .isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.classifyNavigation("https://accounts.google.com/", origin, false)).isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.classifyNavigation("http://example.com/", origin, true)).isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.classifyNavigation("geo:0,0?q=Luxor", origin, false)).isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.classifyNavigation("google.navigation:q=Luxor", origin, false)).isEqualTo(Navigation.EXTERNAL)
        assertThat(OriginPolicy.classifyNavigation("javascript:alert(1)", origin, false)).isEqualTo(Navigation.DENY)
        assertThat(OriginPolicy.classifyNavigation("file:///etc/hosts", origin, false)).isEqualTo(Navigation.DENY)
        assertThat(OriginPolicy.classifyNavigation("intent://x#Intent;end", origin, false)).isEqualTo(Navigation.DENY)
        assertThat(OriginPolicy.classifyNavigation("content://x/y", origin, false)).isEqualTo(Navigation.DENY)
    }

    @Test fun `same-origin audio url resolution`() {
        assertThat(OriginPolicy.resolveSameOriginUrl("/a/b.mp3", origin)).isEqualTo("$origin/a/b.mp3")
        assertThat(OriginPolicy.resolveSameOriginUrl("$origin/a/b.mp3?x=1", origin)).isEqualTo("$origin/a/b.mp3?x=1")
        assertThat(OriginPolicy.resolveSameOriginUrl("//evil.example/x", origin)).isNull()
        assertThat(OriginPolicy.resolveSameOriginUrl("relative.mp3", origin)).isNull()
        assertThat(OriginPolicy.resolveSameOriginUrl("/a b.mp3", origin)).isNull()
        assertThat(OriginPolicy.resolveSameOriginUrl("https://artifact.example.com@evil.example/x", origin)).isNull()
    }
}
