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

    private val session = """{"v":1,"type":"media.session","artifactId":"book","state":"playing","title":"Chapter 1","artist":"Narrator · Ava","album":"Frankenstein","actions":["play","pause","next","previous","stop"]}"""

    @Test fun `media session parses with the shell's fields`() {
        val m = ok(session) as Inbound.MediaSession
        assertThat(m.artifactId).isEqualTo("book")
        assertThat(m.state).isEqualTo(Protocol.MediaState.PLAYING)
        assertThat(m.title).isEqualTo("Chapter 1")
        assertThat(m.artist).isEqualTo("Narrator · Ava")
        assertThat(m.album).isEqualTo("Frankenstein")
        assertThat(m.actions).containsExactly(
            Protocol.MediaAction.PLAY, Protocol.MediaAction.PAUSE, Protocol.MediaAction.NEXT,
            Protocol.MediaAction.PREVIOUS, Protocol.MediaAction.STOP,
        ).inOrder()
    }

    @Test fun `media session none carries empty strings and no actions`() {
        val m = ok("""{"v":1,"type":"media.session","artifactId":"book","state":"none","title":"","artist":"","album":"","actions":[]}""") as Inbound.MediaSession
        assertThat(m.state).isEqualTo(Protocol.MediaState.NONE)
        assertThat(m.title).isEmpty()
        assertThat(m.actions).isEmpty()
        val paused = ok("""{"v":1,"type":"media.session","artifactId":"book","state":"paused","title":"T","artist":"","album":"","actions":["play"]}""") as Inbound.MediaSession
        assertThat(paused.state).isEqualTo(Protocol.MediaState.PAUSED)
        assertThat(paused.actions).containsExactly(Protocol.MediaAction.PLAY)
    }

    @Test fun `media session rejects unknown fields, states and actions`() {
        invalid(session.replace("\"actions\"", "\"extra\":1,\"actions\""))
        invalid(session.replace("\"playing\"", "\"loading\""))
        invalid(session.replace("\"stop\"]", "\"seek\"]"))
        invalid(session.replace("\"stop\"]", "1]"))
        invalid(session.replace("\"title\":\"Chapter 1\"", "\"title\":5"))
        invalid(session.replace("\"title\":\"Chapter 1\"", "\"title\":\"${"x".repeat(201)}\""))
        invalid(session.replace("\"artifactId\":\"book\"", "\"artifactId\":\"bad id\""))
        invalid("""{"v":1,"type":"media.session","artifactId":"book"}""")
        val error = invalid(session.replace("\"stop\"]", "\"stop\"" + ",\"play\"".repeat(8) + "]"))
        assertThat(error.reason).isEqualTo(Protocol.Reason.LIMIT)
    }

    @Test fun `media session accepts the rewind and speed actions and an optional rate`() {
        val m = ok(session.replace("\"stop\"]", "\"stop\",\"rewind\",\"speed\"],\"rate\":\"1.25\"")) as Inbound.MediaSession
        assertThat(m.actions).containsAtLeast(Protocol.MediaAction.REWIND, Protocol.MediaAction.SPEED)
        assertThat(m.rate).isEqualTo("1.25")
        assertThat((ok(session) as Inbound.MediaSession).rate).isNull()
        for (rate in listOf("1", "0.8", "1.5", "2", "1.75")) {
            assertThat((ok(session.replace("]}", "],\"rate\":\"$rate\"}")) as Inbound.MediaSession).rate).isEqualTo(rate)
        }
        assertThat((ok(session.replace("]}", "],\"rate\":null}")) as Inbound.MediaSession).rate).isNull()
        val none = ok("""{"v":1,"type":"media.session","artifactId":"book","state":"none","title":"","artist":"","album":"","actions":[],"rate":"1"}""") as Inbound.MediaSession
        assertThat(none.rate).isNull()
    }

    @Test fun `media session rejects malformed rates and still rejects unknown actions`() {
        for (rate in listOf("\"fast\"", "1.25", "\"\"", "\"-1\"", "\"1.\"", "\".5\"", "\"01\"", "\"100\"", "\"1.255\"", "\"1e2\"", "\" 1\"")) {
            invalid(session.replace("]}", "],\"rate\":$rate}"))
        }
        invalid(session.replace("\"stop\"]", "\"stop\",\"seek\"]"))
        invalid(session.replace("\"stop\"]", "\"stop\",\"Rewind\"]"))
    }

    @Test fun `media session text accepts 200 characters and blanks control characters`() {
        val m = ok(session.replace("Chapter 1", "x".repeat(200))) as Inbound.MediaSession
        assertThat(m.title).hasLength(200)
        val c = ok(session.replace("Chapter 1", "A\\u0007B")) as Inbound.MediaSession
        assertThat(c.title).isEqualTo("A B")
    }

    @Test fun `media command carries exactly the protocol fields`() {
        val o = Protocol.parseJson(Protocol.mediaCommand("book", Protocol.MediaAction.PAUSE)) as JsonObject
        assertThat(o.keys).containsExactly("v", "type", "artifactId", "action")
        assertThat((o["type"] as JsonPrimitive).content).isEqualTo("media.command")
        assertThat((o["artifactId"] as JsonPrimitive).content).isEqualTo("book")
        assertThat((o["action"] as JsonPrimitive).content).isEqualTo("pause")
        assertThat(Protocol.MediaAction.entries.map { it.wire })
            .containsExactly("play", "pause", "next", "previous", "stop", "rewind", "speed", "forward", "chapter")
        val rewind = Protocol.parseJson(Protocol.mediaCommand("book", Protocol.MediaAction.REWIND)) as JsonObject
        assertThat((rewind["action"] as JsonPrimitive).content).isEqualTo("rewind")
        val speed = Protocol.parseJson(Protocol.mediaCommand("book", Protocol.MediaAction.SPEED)) as JsonObject
        assertThat((speed["action"] as JsonPrimitive).content).isEqualTo("speed")
    }

    @Test fun `ready reply lists the capabilities`() {
        val o = Protocol.parseJson(Protocol.ready("0.1.0")) as JsonObject
        assertThat((o["v"] as JsonPrimitive).content).isEqualTo("1")
        assertThat((o["type"] as JsonPrimitive).content).isEqualTo("ready")
        assertThat((o["app"] as JsonPrimitive).content).isEqualTo("0.1.0")
        assertThat((o["capabilities"] as JsonArray).map { (it as JsonPrimitive).content })
            .containsExactly("geo.watch", "audio.queue", "audio.geotrigger", "notify", "media.mirror", "library", "media.rate", "media.progress").inOrder()
    }

    // ---- media.progress (ADR-0022) ---------------------------------------------------------

    private fun progress(extra: String, state: String = "playing") =
        session.replace("\"playing\"", "\"$state\"").replace("]}", "]$extra}")

    @Test fun `media session accepts buffering and the progress fields`() {
        val m = ok(progress(",\"position\":12.5,\"duration\":300,\"chapterIndex\":3", state = "buffering")) as Inbound.MediaSession
        assertThat(m.state).isEqualTo(Protocol.MediaState.BUFFERING)
        assertThat(m.position).isEqualTo(12.5)
        assertThat(m.duration).isEqualTo(300.0)
        assertThat(m.chapterIndex).isEqualTo(3)
        assertThat(m.error).isNull()
        val bare = ok(session) as Inbound.MediaSession
        assertThat(listOf(bare.position, bare.duration, bare.chapterIndex, bare.error)).containsExactly(null, null, null, null)
        // Bounds: 0 and 86400 seconds, chapter 0, one decimal.
        val edge = ok(progress(",\"position\":0,\"duration\":86400,\"chapterIndex\":0")) as Inbound.MediaSession
        assertThat(edge.position).isEqualTo(0.0)
        assertThat(edge.duration).isEqualTo(86400.0)
        assertThat(edge.chapterIndex).isEqualTo(0)
        assertThat((ok(progress(",\"position\":0.1")) as Inbound.MediaSession).position).isEqualTo(0.1)
        assertThat((ok(progress(",\"position\":86399.9")) as Inbound.MediaSession).position).isEqualTo(86399.9)
    }

    @Test fun `media session rejects malformed progress fields`() {
        for (bad in listOf(
            ",\"position\":-1", ",\"position\":86400.1", ",\"position\":1.25", ",\"position\":\"5\"", ",\"position\":true",
            ",\"position\":null", ",\"position\":[1]",
            ",\"duration\":-0.5", ",\"duration\":90000", ",\"duration\":0.05", ",\"duration\":\"300\"",
            ",\"chapterIndex\":-1", ",\"chapterIndex\":1.5", ",\"chapterIndex\":\"2\"", ",\"chapterIndex\":true", ",\"chapterIndex\":null",
            ",\"progress\":1",
        )) {
            invalid(progress(bad))
        }
    }

    @Test fun `media session error comes only with paused or none and is 1 to 120 characters`() {
        val paused = ok(progress(",\"error\":\"Speech is unavailable\"", state = "paused")) as Inbound.MediaSession
        assertThat(paused.error).isEqualTo("Speech is unavailable")
        val none = ok("""{"v":1,"type":"media.session","artifactId":"book","state":"none","title":"","artist":"","album":"","actions":[],"error":"No connection"}""") as Inbound.MediaSession
        assertThat(none.error).isEqualTo("No connection")
        assertThat((ok(progress(",\"error\":\"${"e".repeat(120)}\"", state = "paused")) as Inbound.MediaSession).error).hasLength(120)
        assertThat((ok(progress(",\"error\":\"a\\u0007b\"", state = "paused")) as Inbound.MediaSession).error).isEqualTo("a b")
        invalid(progress(",\"error\":\"Speech is unavailable\""))
        invalid(progress(",\"error\":\"Speech is unavailable\"", state = "buffering"))
        invalid(progress(",\"error\":\"\"", state = "paused"))
        invalid(progress(",\"error\":\"${"e".repeat(121)}\"", state = "paused"))
        invalid(progress(",\"error\":5", state = "paused"))
    }

    @Test fun `media session none drops progress but keeps the error`() {
        val none = ok("""{"v":1,"type":"media.session","artifactId":"book","state":"none","title":"","artist":"","album":"","actions":[],"position":5,"duration":9,"chapterIndex":1,"error":"Stopped"}""") as Inbound.MediaSession
        assertThat(listOf(none.position, none.duration, none.chapterIndex)).containsExactly(null, null, null)
        assertThat(none.error).isEqualTo("Stopped")
    }

    @Test fun `media session accepts forward and chapter and up to 12 actions`() {
        val all = "\"play\",\"pause\",\"next\",\"previous\",\"stop\",\"rewind\",\"speed\",\"forward\",\"chapter\""
        val m = ok(session.replace("\"play\",\"pause\",\"next\",\"previous\",\"stop\"", all)) as Inbound.MediaSession
        assertThat(m.actions).containsAtLeast(Protocol.MediaAction.FORWARD, Protocol.MediaAction.CHAPTER)
        // 12 entries (duplicates collapse) are accepted, 13 are over the limit.
        ok(session.replace("\"play\",\"pause\",\"next\",\"previous\",\"stop\"", "$all,\"play\",\"play\",\"play\""))
        val over = invalid(session.replace("\"play\",\"pause\",\"next\",\"previous\",\"stop\"", "$all,\"play\",\"play\",\"play\",\"play\""))
        assertThat(over.reason).isEqualTo(Protocol.Reason.LIMIT)
    }

    @Test fun `media chapters parses titles and the empty list`() {
        val m = ok("""{"v":1,"type":"media.chapters","artifactId":"book","chapters":["Letter 1","Chapter 1","${"c".repeat(200)}"]}""") as Inbound.MediaChapters
        assertThat(m.artifactId).isEqualTo("book")
        assertThat(m.chapters).hasSize(3)
        assertThat(m.chapters[2]).hasLength(200)
        val empty = ok("""{"v":1,"type":"media.chapters","artifactId":"book","chapters":[]}""") as Inbound.MediaChapters
        assertThat(empty.chapters).isEmpty()
        val many = (1..500).joinToString(",") { "\"C$it\"" }
        assertThat((ok("""{"v":1,"type":"media.chapters","artifactId":"book","chapters":[$many]}""") as Inbound.MediaChapters).chapters).hasSize(500)
        val control = ok("""{"v":1,"type":"media.chapters","artifactId":"book","chapters":["A\u0007B"]}""") as Inbound.MediaChapters
        assertThat(control.chapters).containsExactly("A B")
    }

    @Test fun `media chapters rejects malformed lists`() {
        val base = """{"v":1,"type":"media.chapters","artifactId":"book","chapters":["A"]}"""
        invalid(base.replace("]}", "],\"extra\":1}"))
        invalid(base.replace("[\"A\"]", "[\"\"]"))
        invalid(base.replace("[\"A\"]", "[\"${"c".repeat(201)}\"]"))
        invalid(base.replace("[\"A\"]", "[1]"))
        invalid(base.replace("[\"A\"]", "[null]"))
        invalid(base.replace("[\"A\"]", "\"A\""))
        invalid("""{"v":1,"type":"media.chapters","artifactId":"book"}""")
        invalid(base.replace("\"book\"", "\"bad id\""))
        val many = (1..501).joinToString(",") { "\"C$it\"" }
        assertThat(invalid("""{"v":1,"type":"media.chapters","artifactId":"book","chapters":[$many]}""").reason).isEqualTo(Protocol.Reason.LIMIT)
    }

    @Test fun `media command sends forward and chapter with its index`() {
        val forward = Protocol.parseJson(Protocol.mediaCommand("book", Protocol.MediaAction.FORWARD)) as JsonObject
        assertThat(forward.keys).containsExactly("v", "type", "artifactId", "action")
        assertThat((forward["action"] as JsonPrimitive).content).isEqualTo("forward")
        val chapter = Protocol.parseJson(Protocol.mediaCommand("book", Protocol.MediaAction.CHAPTER, 7)) as JsonObject
        assertThat(chapter.keys).containsExactly("v", "type", "artifactId", "action", "index")
        assertThat((chapter["action"] as JsonPrimitive).content).isEqualTo("chapter")
        assertThat((chapter["index"] as JsonPrimitive).isString).isFalse()
        assertThat((chapter["index"] as JsonPrimitive).content).isEqualTo("7")
        assertThat(((Protocol.parseJson(Protocol.mediaCommand("book", Protocol.MediaAction.CHAPTER, 499)) as JsonObject)["index"] as JsonPrimitive).content).isEqualTo("499")
        for (bad in listOf<() -> Unit>(
            { Protocol.mediaCommand("book", Protocol.MediaAction.CHAPTER) },
            { Protocol.mediaCommand("book", Protocol.MediaAction.CHAPTER, -1) },
            { Protocol.mediaCommand("book", Protocol.MediaAction.CHAPTER, 500) },
            { Protocol.mediaCommand("book", Protocol.MediaAction.PLAY, 1) },
        )) {
            assertThat(runCatching(bad).isFailure).isTrue()
        }
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
