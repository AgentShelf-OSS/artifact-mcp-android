package ai.agentshelf.artifactmcp.bridge

import ai.agentshelf.artifactmcp.geo.Fix
import ai.agentshelf.artifactmcp.state.Capability
import ai.agentshelf.artifactmcp.state.GrantStore
import ai.agentshelf.artifactmcp.state.InMemoryKeyValueStore
import ai.agentshelf.artifactmcp.state.NoticeStore
import ai.agentshelf.artifactmcp.state.PlayedStore
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test

class BridgeHubTest {
    private val origin = "https://artifact.example.com"
    private var now = 1_000_000L
    private val kv = InMemoryKeyValueStore()
    private val grants = GrantStore(kv)
    private val played = PlayedStore(kv)
    private val notices = NoticeStore(kv)

    private class FakeLocation : LocationControl {
        var demand: LocationDemand? = null
        var allowStart = true
        override fun update(demand: LocationDemand?): Boolean {
            if (demand != null && !allowStart) return false
            this.demand = demand
            return true
        }
    }

    private class FakeAudio : AudioControl {
        val enqueued = mutableListOf<Pair<String, String>>()
        val prefetched = mutableListOf<String>()
        val stopped = mutableListOf<String>()
        override fun enqueue(artifactId: String, label: String, item: Protocol.AudioItem) {
            enqueued += artifactId to item.id
        }
        override fun stop(artifactId: String) {
            stopped += artifactId
        }
        override fun prefetch(artifactId: String, items: List<Protocol.AudioItem>) {
            prefetched += items.map { it.id }
        }
        override fun stopAll() {
            stopped += "*"
        }
    }

    private class FakeNotifier : ArtifactNotifier {
        val shown = mutableListOf<String>()
        override fun show(artifactId: String, label: String, requestId: String, title: String, body: String): Boolean {
            shown += "$artifactId:$title"
            return true
        }
    }

    private class Shell(override val pageArtifactId: String?) : ShellConnection {
        val sent = mutableListOf<JsonObject>()
        override fun post(message: String) {
            sent += Protocol.parseJson(message) as JsonObject
        }
        fun types() = sent.map { (it["type"] as JsonPrimitive).content }
        fun last() = sent.last()
    }

    private val location = FakeLocation()
    private val audio = FakeAudio()
    private val notifier = FakeNotifier()
    private var consentAnswer = true
    private val consentAsked = mutableListOf<Pair<String, Capability>>()
    private lateinit var hub: BridgeHub

    @Before fun setUp() {
        hub = BridgeHub("0.1.0", { now }, { origin }, grants, played, notices, location, audio, notifier)
        hub.consent = ConsentGate { artifactId, _, capability, onResult ->
            consentAsked += artifactId to capability
            if (consentAnswer) grants.grant(artifactId, capability, artifactId, now)
            onResult(consentAnswer)
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as JsonPrimitive).content

    private val watch = """{"v":1,"type":"geo.watch","artifactId":"trip","requestId":"w1","intervalMs":30000}"""
    private val triggers = """{"v":1,"type":"audio.geotrigger","artifactId":"trip","requestId":"g1","items":[
        {"id":"near","title":"Near","text":"Near stop","lat":51.5007,"lon":-0.1246,"radius_m":500},
        {"id":"far","title":"Far","text":"Far stop","lat":48.8584,"lon":2.2945,"radius_m":1000}]}"""

    @Test fun `hello replies ready`() {
        val shell = Shell(null)
        hub.onShellMessage("""{"v":1,"type":"hello"}""", shell)
        assertThat(shell.types()).containsExactly("ready")
        assertThat(shell.last().str("app")).isEqualTo("0.1.0")
    }

    @Test fun `invalid message gets an error reply`() {
        val shell = Shell("trip")
        hub.onShellMessage("""{"v":1,"type":"geo.watch","artifactId":"trip","requestId":"w1","intervalMs":10}""", shell)
        assertThat(shell.types()).containsExactly("error")
        assertThat(shell.last().str("reason")).isEqualTo("invalid")
        assertThat(shell.last().str("requestId")).isEqualTo("w1")
        assertThat(location.demand).isNull()
    }

    @Test fun `a page cannot drive another artifact`() {
        val shell = Shell("other")
        hub.onShellMessage(watch, shell)
        assertThat(shell.types()).containsExactly("error")
        assertThat(shell.last().str("reason")).isEqualTo("denied")
        assertThat(consentAsked).isEmpty()
        assertThat(location.demand).isNull()
    }

    @Test fun `gallery page cannot request capabilities`() {
        val shell = Shell(null)
        hub.onShellMessage(watch, shell)
        assertThat(shell.last().str("reason")).isEqualTo("denied")
    }

    @Test fun `geo watch asks consent, starts location, and delivers throttled fixes`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        assertThat(consentAsked).containsExactly("trip" to Capability.LOCATION)
        assertThat(location.demand).isEqualTo(LocationDemand(30_000L, false, listOf("trip")))

        hub.onFix(Fix(1.0, 2.0, 5.0, now))
        now += 10_000
        hub.onFix(Fix(1.1, 2.1, 5.0, now)) // within the 30 s interval: dropped
        now += 20_000
        hub.onFix(Fix(1.2, 2.2, 5.0, now))
        val positions = shell.sent.filter { it.str("type") == "geo.position" }
        assertThat(positions).hasSize(2)
        assertThat(positions[0].str("requestId")).isEqualTo("w1")
        assertThat(positions[1].str("lat")).isEqualTo("1.2")
    }

    @Test fun `denied consent yields geo error denied`() {
        consentAnswer = false
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        assertThat(shell.types()).containsExactly("geo.error")
        assertThat(shell.last().str("reason")).isEqualTo("denied")
        assertThat(location.demand).isNull()
    }

    @Test fun `service start failure yields geo error unavailable`() {
        location.allowStart = false
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        assertThat(shell.last().str("reason")).isEqualTo("unavailable")
        assertThat(hub.activeWatches).isEmpty()
    }

    @Test fun `unwatch stops the service`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        hub.onShellMessage("""{"v":1,"type":"geo.unwatch","artifactId":"trip","requestId":"w1"}""", shell)
        assertThat(location.demand).isNull()
    }

    @Test fun `watch limit per artifact`() {
        val shell = Shell("trip")
        repeat(BridgeHub.MAX_WATCHES_PER_ARTIFACT) {
            hub.onShellMessage("""{"v":1,"type":"geo.watch","artifactId":"trip","requestId":"w$it","intervalMs":15000}""", shell)
        }
        hub.onShellMessage("""{"v":1,"type":"geo.watch","artifactId":"trip","requestId":"extra","intervalMs":15000}""", shell)
        assertThat(shell.last().str("reason")).isEqualTo("limit")
    }

    @Test fun `new page clears watches but keeps geotriggers`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        hub.onShellMessage(triggers, shell)
        hub.onPageStarted()
        assertThat(hub.activeWatches).isEmpty()
        assertThat(hub.activeTriggers.keys).containsExactly("trip")
        assertThat(location.demand?.intervalMs).isEqualTo(BridgeHub.GEOTRIGGER_INTERVAL_MS)
    }

    @Test fun `no fix within the timeout reports timeout once`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        now += BridgeHub.NO_FIX_TIMEOUT_MS
        hub.checkTimeouts()
        hub.checkTimeouts()
        assertThat(shell.sent.filter { it.str("type") == "geo.error" }.map { it.str("reason") }).containsExactly("timeout")
    }

    @Test fun `geotrigger fires once on first entry and replays until acked`() {
        val shell = Shell("trip")
        hub.onShellMessage(triggers, shell)
        assertThat(audio.prefetched).containsExactly("near", "far").inOrder()
        assertThat(location.demand?.highAccuracy).isTrue()

        // 100 m from "near", accurate fix.
        hub.onFix(Fix(51.5016, -0.1246, 20.0, now))
        hub.onFix(Fix(51.5010, -0.1246, 20.0, now + 15_000))
        assertThat(audio.enqueued).containsExactly("trip" to "near")
        assertThat(shell.sent.count { it.str("type") == "audio.played" }).isEqualTo(1)

        // Played state survives a new hub (process death) and is replayed on hello until acked.
        val revived = BridgeHub("0.1.0", { now }, { origin }, grants, PlayedStore(kv), notices, location, audio, notifier)
        val again = Shell("trip")
        revived.onShellMessage("""{"v":1,"type":"hello"}""", again)
        assertThat(again.types()).containsExactly("ready", "audio.played").inOrder()
        assertThat(again.last().str("id")).isEqualTo("near")
        revived.onShellMessage("""{"v":1,"type":"played.ack","artifactId":"trip","ids":["near"]}""", again)
        val third = Shell("trip")
        revived.onShellMessage("""{"v":1,"type":"hello"}""", third)
        assertThat(third.types()).containsExactly("ready")
    }

    @Test fun `played events are not shown to other artifacts`() {
        val shell = Shell("trip")
        hub.onShellMessage(triggers, shell)
        hub.onFix(Fix(51.5016, -0.1246, 20.0, now))
        val other = Shell("baby-log")
        hub.onShellMessage("""{"v":1,"type":"hello"}""", other)
        assertThat(other.types()).containsExactly("ready")
    }

    @Test fun `empty geotrigger set clears it without consent`() {
        val shell = Shell("trip")
        hub.onShellMessage(triggers, shell)
        consentAsked.clear()
        hub.onShellMessage("""{"v":1,"type":"audio.geotrigger","artifactId":"trip","requestId":"g2","items":[]}""", shell)
        assertThat(consentAsked).isEmpty()
        assertThat(hub.activeTriggers).isEmpty()
        assertThat(location.demand).isNull()
    }

    @Test fun `revoke stops watches and triggers and reports denied on next hello`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        hub.onShellMessage(triggers, shell)
        hub.onPageStarted() // the viewer navigated away; watches end, triggers stay
        hub.onShellMessage(watch, Shell("trip"))
        hub.onPageStarted()

        // Re-add a watch while the trip page is open, then revoke from Settings while it is gone.
        val page = Shell("trip")
        hub.onShellMessage(watch, page)
        hub.onPageStarted()
        hub.revoke("trip", Capability.LOCATION)
        assertThat(grants.isGranted("trip", Capability.LOCATION)).isFalse()
        assertThat(hub.activeTriggers).isEmpty()
        assertThat(location.demand).isNull()

        val back = Shell("trip")
        hub.onShellMessage("""{"v":1,"type":"hello"}""", back)
        val errors = back.sent.filter { it.str("type") == "geo.error" }
        assertThat(errors.map { it.str("requestId") }).containsExactly("g1")
        assertThat(errors.map { it.str("reason") }).containsExactly("denied")

        // Revoked: the next request asks again.
        consentAsked.clear()
        hub.onShellMessage(watch, back)
        assertThat(consentAsked).containsExactly("trip" to Capability.LOCATION)
    }

    @Test fun `revoke while the page is open reports at once`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        hub.revoke("trip", Capability.LOCATION)
        val error = shell.sent.last()
        assertThat(error.str("type")).isEqualTo("geo.error")
        assertThat(error.str("requestId")).isEqualTo("w1")
        assertThat(error.str("reason")).isEqualTo("denied")
        assertThat(notices.load()).isEmpty()
    }

    @Test fun `stop from the notification ends all location use`() {
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        hub.onShellMessage(triggers, shell)
        hub.stopAllLocation()
        assertThat(location.demand).isNull()
        assertThat(shell.sent.filter { it.str("type") == "geo.error" }.map { it.str("requestId") }).containsExactly("w1", "g1")
        assertThat(grants.isGranted("trip", Capability.LOCATION)).isTrue()
    }

    @Test fun `audio queue and stop are forwarded`() {
        val shell = Shell("trip")
        hub.onShellMessage("""{"v":1,"type":"audio.queue","artifactId":"trip","requestId":"q","item":{"id":"i1","title":"T","text":"x"}}""", shell)
        hub.onShellMessage("""{"v":1,"type":"audio.stop","artifactId":"trip"}""", shell)
        assertThat(audio.enqueued).containsExactly("trip" to "i1")
        assertThat(audio.stopped).containsExactly("trip")
        hub.onAudioState("trip", "i1", "playing")
        assertThat(shell.last().str("state")).isEqualTo("playing")
        hub.onAudioState("other", "i1", "playing")
        assertThat(shell.sent.count { it.str("type") == "audio.state" }).isEqualTo(1)
    }

    @Test fun `notify asks consent once and is rate limited`() {
        val shell = Shell("log")
        val msg = """{"v":1,"type":"notify.show","artifactId":"log","requestId":"n","title":"Feed","body":"Due"}"""
        repeat(BridgeHub.NOTIFY_MAX_PER_WINDOW) { hub.onShellMessage(msg, shell) }
        assertThat(notifier.shown).hasSize(BridgeHub.NOTIFY_MAX_PER_WINDOW)
        hub.onShellMessage(msg, shell)
        assertThat(shell.last().str("reason")).isEqualTo("limit")
        now += BridgeHub.NOTIFY_WINDOW_MS + 1
        hub.onShellMessage(msg, shell)
        assertThat(notifier.shown).hasSize(BridgeHub.NOTIFY_MAX_PER_WINDOW + 1)
    }

    @Test fun `without a consent gate requests are denied`() {
        hub.consent = null
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        assertThat(shell.last().str("reason")).isEqualTo("denied")
    }

    @Test fun `titles label the location demand`() {
        hub.setTitle("trip", "Road trip\u0007 · Day 1")
        val shell = Shell("trip")
        hub.onShellMessage(watch, shell)
        assertThat(location.demand?.labels).containsExactly("Road trip · Day 1")
    }
}
