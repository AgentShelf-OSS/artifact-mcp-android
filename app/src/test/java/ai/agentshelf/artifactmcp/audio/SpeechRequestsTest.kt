package ai.agentshelf.artifactmcp.audio

import ai.agentshelf.artifactmcp.bridge.Protocol
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechRequestsTest {
    private val origin = "https://artifact.example.com"

    @Test fun `speech request satisfies the server's request-authenticity gate`() {
        val spec = SpeechRequests.speech(origin, "trip_1", "Hello \"there\"", "bf_emma", "CF_Authorization=abc", "UA/1")
        assertThat(spec.url).isEqualTo("$origin/trip_1/speech")
        assertThat(spec.method).isEqualTo("POST")
        assertThat(spec.headers).containsEntry("Origin", origin)
        assertThat(spec.headers).containsEntry("X-Artifact-Mutation", "1")
        assertThat(spec.headers).containsEntry("Content-Type", "application/json")
        assertThat(spec.headers).containsEntry("Cookie", "CF_Authorization=abc")
        assertThat(spec.headers).containsEntry("User-Agent", "UA/1")
        assertThat(spec.headers).doesNotContainKey("Sec-Fetch-Site")
    }

    @Test fun `speech body has exactly text and voice`() {
        val spec = SpeechRequests.speech(origin, "trip_1", "Line\nTwo", "pocket_alba", null, null)
        val body = Protocol.parseJson(String(spec.body!!, Charsets.UTF_8)) as JsonObject
        assertThat(body.keys).containsExactly("text", "voice")
        assertThat((body["text"] as JsonPrimitive).content).isEqualTo("Line\nTwo")
        assertThat((body["voice"] as JsonPrimitive).content).isEqualTo("pocket_alba")
        assertThat(spec.headers).doesNotContainKey("Cookie")
    }

    @Test fun `artifact id is validated before it enters a path`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRequests.speech(origin, "../admin", "x", "v", null, null)
        }
    }

    @Test fun `voices request is a plain GET`() {
        val spec = SpeechRequests.voices(origin, "trip_1", "c=1", null)
        assertThat(spec.url).isEqualTo("$origin/trip_1/speech/voices")
        assertThat(spec.method).isEqualTo("GET")
        assertThat(spec.body).isNull()
        assertThat(spec.headers).doesNotContainKey("X-Artifact-Mutation")
    }

    @Test fun `audio url requests stay on the origin`() {
        assertThat(SpeechRequests.audio(origin, "$origin/a/x.mp3", "c=1", null)?.headers).containsEntry("Cookie", "c=1")
        assertThat(SpeechRequests.audio(origin, "https://evil.example/x.mp3", "c=1", null)).isNull()
    }

    @Test fun `first voice from the voices response`() {
        assertThat(SpeechRequests.firstVoice("""{"enabled":true,"voices":[{"id":"bm_george","name":"George"}],"maxChars":1500}"""))
            .isEqualTo("bm_george")
        assertThat(SpeechRequests.firstVoice("""{"enabled":false,"voices":[],"maxChars":1500}""")).isNull()
        assertThat(SpeechRequests.firstVoice("""{"enabled":true,"voices":[{"id":"bad id"}]}""")).isNull()
        assertThat(SpeechRequests.firstVoice("<html>")).isNull()
    }

    @Test fun `status classification`() {
        assertThat(SpeechRequests.classify(200)).isNull()
        assertThat(SpeechRequests.classify(302)).isEqualTo(SpeechException.Kind.SIGNED_OUT)
        assertThat(SpeechRequests.classify(401)).isEqualTo(SpeechException.Kind.SIGNED_OUT)
        assertThat(SpeechRequests.classify(403)).isEqualTo(SpeechException.Kind.SIGNED_OUT)
        assertThat(SpeechRequests.classify(429)).isEqualTo(SpeechException.Kind.BUSY)
        assertThat(SpeechRequests.classify(503)).isEqualTo(SpeechException.Kind.BUSY)
        assertThat(SpeechRequests.classify(400)).isEqualTo(SpeechException.Kind.REJECTED)
        assertThat(SpeechRequests.classify(500)).isEqualTo(SpeechException.Kind.UNAVAILABLE)
    }

    @Test fun `cache key depends on content and voice`() {
        val a = Protocol.AudioItem("s1", "T", "Hello", null, "bf_emma")
        assertThat(ClipResolver.cacheKey("trip", a)).isEqualTo(ClipResolver.cacheKey("trip", a.copy(title = "Other title")))
        assertThat(ClipResolver.cacheKey("trip", a)).isNotEqualTo(ClipResolver.cacheKey("trip", a.copy(text = "Hello!")))
        assertThat(ClipResolver.cacheKey("trip", a)).isNotEqualTo(ClipResolver.cacheKey("trip", a.copy(voice = "af_heart")))
        assertThat(ClipResolver.cacheKey("trip", a)).isNotEqualTo(ClipResolver.cacheKey("log", a))
        assertThat(ClipResolver.cacheKey("trip", a)).matches("[0-9a-f]{64}")
    }

    @Test fun `browse ids round trip`() {
        val id = BrowseTree.triggerId("trip", "stop:1")
        assertThat(BrowseTree.parseTriggerId(id)).isEqualTo("trip" to "stop:1")
        assertThat(BrowseTree.parseTriggerId("t:trip:9:stop:1")).isNull()
        assertThat(BrowseTree.parseTriggerId("q:1")).isNull()
        assertThat(BrowseTree.parseTriggerId("t:../x:1:a")).isNull()
    }

    @Test fun `media controller policy`() {
        val own = "ai.agentshelf.artifactmcp"
        assertThat(ControllerPolicy.allow(own, own, false, false, false)).isTrue()
        assertThat(ControllerPolicy.allow(ControllerPolicy.ANDROID_AUTO_PACKAGE, own, false, false, false)).isTrue()
        assertThat(ControllerPolicy.allow("com.example.other", own, false, true, false)).isTrue()
        assertThat(ControllerPolicy.allow("com.example.other", own, true, false, false)).isTrue()
        assertThat(ControllerPolicy.allow("com.example.other", own, false, false, false)).isFalse()
    }
}
