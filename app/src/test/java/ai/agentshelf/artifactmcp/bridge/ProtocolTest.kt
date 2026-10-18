package ai.agentshelf.artifactmcp.bridge

import ai.agentshelf.artifactmcp.bridge.Protocol.Inbound
import ai.agentshelf.artifactmcp.bridge.Protocol.ParseResult
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ProtocolTest {
    private val origin = "https://artifact.example.com"

    private fun ok(raw: String): Inbound {
        val result = Protocol.parse(raw, origin)
        assertThat(result).isInstanceOf(ParseResult.Ok::class.java)
        return (result as ParseResult.Ok).message
    }

    private fun invalid(raw: String): ParseResult.Invalid {
        val result = Protocol.parse(raw, origin)
        assertThat(result).isInstanceOf(ParseResult.Invalid::class.java)
        return result as ParseResult.Invalid
    }

    private fun item(extra: String = "", id: String = "a1", title: String = "Stop", content: String = "\"text\":\"Hello\"") =
        """{"id":"$id","title":"$title",$content$extra}"""

    @Test fun `hello parses`() {
        assertThat(ok("""{"v":1,"type":"hello"}""")).isEqualTo(Inbound.Hello)
    }

    @Test fun `version must be the integer 1`() {
        invalid("""{"v":2,"type":"hello"}""")
        invalid("""{"v":"1","type":"hello"}""")
        invalid("""{"v":1.0,"type":"hello"}""")
        invalid("""{"type":"hello"}""")
    }

    @Test fun `non-object and malformed input is invalid`() {
        invalid("[]")
        invalid("\"hello\"")
        invalid("{")
        invalid("")
        invalid("""{"v":1,"type":"hello"} trailing""")
    }

    @Test fun `oversized message is invalid`() {
        val huge = """{"v":1,"type":"hello","pad":"${"x".repeat(Protocol.MAX_MESSAGE_CHARS)}"}"""
        invalid(huge)
    }

    @Test fun `unknown type is unsupported and echoes valid ids`() {
        val result = invalid("""{"v":1,"type":"camera.open","artifactId":"abc","requestId":"r1"}""")
        assertThat(result.reason).isEqualTo(Protocol.Reason.UNSUPPORTED)
        assertThat(result.artifactId).isEqualTo("abc")
        assertThat(result.requestId).isEqualTo("r1")
    }

    @Test fun `invalid ids are not echoed`() {
        val result = invalid("""{"v":1,"type":"geo.watch","artifactId":"../x","requestId":"r1","intervalMs":1}""")
        assertThat(result.artifactId).isNull()
        assertThat(result.requestId).isEqualTo("r1")
    }

    @Test fun `unknown keys are rejected`() {
        invalid("""{"v":1,"type":"hello","extra":true}""")
        invalid("""{"v":1,"type":"geo.unwatch","artifactId":"a","requestId":"r","x":1}""")
    }

    @Test fun `geo watch parses with defaults and bounds`() {
        val watch = ok("""{"v":1,"type":"geo.watch","artifactId":"trip_1","requestId":"w1","intervalMs":15000}""") as Inbound.GeoWatch
        assertThat(watch.intervalMs).isEqualTo(15_000L)
        assertThat(watch.highAccuracy).isFalse()
        val high = ok("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w","intervalMs":60000,"highAccuracy":true}""") as Inbound.GeoWatch
        assertThat(high.highAccuracy).isTrue()
        invalid("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w","intervalMs":14999}""")
        invalid("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w","intervalMs":3600001}""")
        invalid("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w","intervalMs":"15000"}""")
        invalid("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w","intervalMs":15000.5}""")
        invalid("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w","intervalMs":15000,"highAccuracy":"yes"}""")
        invalid("""{"v":1,"type":"geo.watch","artifactId":"t","requestId":"w"}""")
    }

    @Test fun `artifact id follows the server pattern`() {
        ok("""{"v":1,"type":"audio.stop","artifactId":"${"a".repeat(64)}"}""")
        invalid("""{"v":1,"type":"audio.stop","artifactId":"${"a".repeat(65)}"}""")
        invalid("""{"v":1,"type":"audio.stop","artifactId":"a b"}""")
        invalid("""{"v":1,"type":"audio.stop","artifactId":"a/b"}""")
        invalid("""{"v":1,"type":"audio.stop","artifactId":""}""")
        invalid("""{"v":1,"type":"audio.stop","artifactId":7}""")
    }

    @Test fun `request id length and control characters`() {
        ok("""{"v":1,"type":"geo.unwatch","artifactId":"a","requestId":"${"r".repeat(64)}"}""")
        invalid("""{"v":1,"type":"geo.unwatch","artifactId":"a","requestId":"${"r".repeat(65)}"}""")
        invalid("""{"v":1,"type":"geo.unwatch","artifactId":"a","requestId":"r\u0001"}""")
        invalid("""{"v":1,"type":"geo.unwatch","artifactId":"a","requestId":""}""")
    }

    @Test fun `audio queue with text`() {
        val q = ok("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(",\"voice\":\"bf_emma\"")}}""") as Inbound.AudioQueue
        assertThat(q.item.text).isEqualTo("Hello")
        assertThat(q.item.voice).isEqualTo("bf_emma")
        assertThat(q.item.url).isNull()
    }

    @Test fun `audio queue url must be same origin`() {
        val q = ok("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"/a/clip.mp3\"")}}""") as Inbound.AudioQueue
        assertThat(q.item.url).isEqualTo("$origin/a/clip.mp3")
        ok("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"$origin/x.wav\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"https://evil.example/x.wav\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"http://artifact.example.com/x.wav\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"//evil.example/x.wav\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"https://user@artifact.example.com/x.wav\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"url\":\"javascript:alert(1)\"")}}""")
    }

    @Test fun `audio item needs exactly one of text and url`() {
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"text\":\"x\",\"url\":\"/x\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":{"id":"a1","title":"Stop"}}""")
    }

    @Test fun `audio item length limits`() {
        ok("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"text\":\"${"t".repeat(1500)}\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"text\":\"${"t".repeat(1501)}\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"text\":\"\"")}}""")
        ok("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(title = "T".repeat(120))}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(title = "T".repeat(121))}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(id = "i".repeat(65))}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(extra = ",\"voice\":\"bad voice\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(extra = ",\"lat\":1")}}""")
        ok("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"text\":\"line\\nnext\\tTab\"")}}""")
        invalid("""{"v":1,"type":"audio.queue","artifactId":"a","requestId":"r","item":${item(content = "\"text\":\"bell\\u0007\"")}}""")
    }

    private fun trigger(lat: String = "51.5", lon: String = "-0.12", radius: String = "500", id: String = "s1") =
        """{"id":"$id","title":"Stop","text":"Hi","lat":$lat,"lon":$lon,"radius_m":$radius}"""

    @Test fun `geotrigger parses and validates ranges`() {
        val g = ok("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger()}]}""") as Inbound.AudioGeotrigger
        assertThat(g.items).hasSize(1)
        assertThat(g.items[0].radiusM).isEqualTo(500.0)
        ok("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[]}""")
        ok("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(radius = "10000")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(radius = "499")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(radius = "10001")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(lat = "91")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(lon = "-180.5")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(lat = "\"51\"")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger(lat = "true")}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[${trigger()},${trigger()}]}""")
        invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":{}}""")
    }

    @Test fun `geotrigger allows at most 50 items`() {
        val fifty = (1..50).joinToString(",") { trigger(id = "s$it") }
        ok("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[$fifty]}""")
        val result = invalid("""{"v":1,"type":"audio.geotrigger","artifactId":"a","requestId":"g","items":[$fifty,${trigger(id = "s51")}]}""")
        assertThat(result.reason).isEqualTo(Protocol.Reason.LIMIT)
    }

    @Test fun `notify show validates title and body`() {
        val n = ok("""{"v":1,"type":"notify.show","artifactId":"a","requestId":"n","title":"Feed due","body":"Last feed 3h ago"}""") as Inbound.NotifyShow
        assertThat(n.title).isEqualTo("Feed due")
        ok("""{"v":1,"type":"notify.show","artifactId":"a","requestId":"n","title":"T","body":""}""")
        invalid("""{"v":1,"type":"notify.show","artifactId":"a","requestId":"n","title":"","body":"b"}""")
        invalid("""{"v":1,"type":"notify.show","artifactId":"a","requestId":"n","title":"T","body":"${"b".repeat(1501)}"}""")
        invalid("""{"v":1,"type":"notify.show","artifactId":"a","requestId":"n","title":"T"}""")
    }

    @Test fun `played ack parses ids`() {
        val ack = ok("""{"v":1,"type":"played.ack","artifactId":"a","ids":["s1","s2"]}""") as Inbound.PlayedAck
        assertThat(ack.ids).containsExactly("s1", "s2").inOrder()
        invalid("""{"v":1,"type":"played.ack","artifactId":"a","ids":[1]}""")
        invalid("""{"v":1,"type":"played.ack","artifactId":"a","ids":"s1"}""")
    }

    @Test fun `ready reply lists the four capabilities`() {
        val o = Protocol.parseJson(Protocol.ready("0.1.0")) as JsonObject
        assertThat((o["v"] as JsonPrimitive).content).isEqualTo("1")
        assertThat((o["type"] as JsonPrimitive).content).isEqualTo("ready")
        assertThat((o["app"] as JsonPrimitive).content).isEqualTo("0.1.0")
        assertThat((o["capabilities"] as JsonArray).map { (it as JsonPrimitive).content })
            .containsExactly("geo.watch", "audio.queue", "audio.geotrigger", "notify").inOrder()
    }

    @Test fun `outbound messages carry the protocol field names`() {
        val pos = Protocol.parseJson(Protocol.geoPosition("a", "w", 1.5, 2.5, 10.0, 99L)) as JsonObject
        assertThat(pos.keys).containsExactly("v", "type", "artifactId", "requestId", "lat", "lon", "accuracy", "timestamp")
        val err = Protocol.parseJson(Protocol.geoError("a", "w", "denied")) as JsonObject
        assertThat(err.keys).containsExactly("v", "type", "artifactId", "requestId", "reason")
        val state = Protocol.parseJson(Protocol.audioState("a", "s1", "playing")) as JsonObject
        assertThat(state.keys).containsExactly("v", "type", "artifactId", "id", "state")
        val played = Protocol.parseJson(Protocol.audioPlayed("a", "s1", 5L)) as JsonObject
        assertThat(played.keys).containsExactly("v", "type", "artifactId", "id", "at")
        val error = Protocol.parseJson(Protocol.error(null, null, "invalid")) as JsonObject
        assertThat(error["artifactId"]).isEqualTo(JsonNull)
        assertThat((error["type"] as JsonPrimitive).content).isEqualTo("error")
    }

    @Test fun `outbound strings are JSON-escaped`() {
        val raw = Protocol.audioState("a", "id\"with\\quote", "done")
        val o = Protocol.parseJson(raw) as JsonObject
        assertThat((o["id"] as JsonPrimitive).content).isEqualTo("id\"with\\quote")
    }
}
