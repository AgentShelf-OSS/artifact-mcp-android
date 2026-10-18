package ai.agentshelf.artifactmcp.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StoresTest {
    @Test fun `grants are per artifact and capability and revocable`() {
        val kv = InMemoryKeyValueStore()
        val grants = GrantStore(kv)
        grants.grant("trip", Capability.LOCATION, "Road trip", 1L)
        grants.grant("trip", Capability.NOTIFY, "Road trip", 2L)
        grants.grant("log", Capability.NOTIFY, "Baby log", 3L)
        assertThat(grants.isGranted("trip", Capability.LOCATION)).isTrue()
        assertThat(grants.isGranted("log", Capability.LOCATION)).isFalse()
        assertThat(GrantStore(kv).all().map { it.artifactId to it.capability }).containsExactly(
            "log" to Capability.NOTIFY,
            "trip" to Capability.LOCATION,
            "trip" to Capability.NOTIFY,
        ).inOrder()
        grants.revoke("trip", Capability.LOCATION)
        assertThat(GrantStore(kv).isGranted("trip", Capability.LOCATION)).isFalse()
        assertThat(GrantStore(kv).isGranted("trip", Capability.NOTIFY)).isTrue()
        grants.clear()
        assertThat(grants.all()).isEmpty()
    }

    @Test fun `played ids persist and fire only once`() {
        val kv = InMemoryKeyValueStore()
        val played = PlayedStore(kv)
        assertThat(played.markPlayed("trip", "s1", 10L)).isTrue()
        assertThat(played.markPlayed("trip", "s1", 11L)).isFalse()
        val revived = PlayedStore(kv)
        assertThat(revived.playedIds("trip")).containsExactly("s1")
        assertThat(revived.pendingFor("trip")).containsExactly(PlayedEvent("trip", "s1", 10L))
        assertThat(revived.pendingFor("other")).isEmpty()
    }

    @Test fun `acknowledged events are dropped but ids stay played`() {
        val kv = InMemoryKeyValueStore()
        val played = PlayedStore(kv)
        played.markPlayed("trip", "s1", 1L)
        played.markPlayed("trip", "s2", 2L)
        played.markPlayed("log", "s1", 3L)
        played.acknowledge("trip", listOf("s1", "unknown"))
        assertThat(played.pending().map { it.artifactId to it.id }).containsExactly("trip" to "s2", "log" to "s1").inOrder()
        assertThat(played.playedIds("trip")).containsExactly("s1", "s2")
        played.clear()
        assertThat(played.pending()).isEmpty()
        assertThat(played.playedIds("trip")).isEmpty()
    }

    @Test fun `played ids are capped per artifact`() {
        val played = PlayedStore(InMemoryKeyValueStore())
        repeat(PlayedStore.MAX_PLAYED_PER_ARTIFACT + 5) { played.markPlayed("trip", "s$it", it.toLong()) }
        assertThat(played.playedIds("trip")).hasSize(PlayedStore.MAX_PLAYED_PER_ARTIFACT)
        assertThat(played.playedIds("trip")).doesNotContain("s0")
    }

    @Test fun `notices are taken once per artifact`() {
        val kv = InMemoryKeyValueStore()
        val notices = NoticeStore(kv)
        notices.add(Notice("trip", "w1", "denied"))
        notices.add(Notice("trip", "w1", "denied"))
        notices.add(Notice("log", "g1", "denied"))
        assertThat(NoticeStore(kv).take("trip")).containsExactly(Notice("trip", "w1", "denied"))
        assertThat(NoticeStore(kv).take("trip")).isEmpty()
        assertThat(NoticeStore(kv).load()).containsExactly(Notice("log", "g1", "denied"))
    }

    @Test fun `corrupt stored data is ignored`() {
        val kv = InMemoryKeyValueStore()
        kv.put("played:trip", "{not json")
        kv.put("pending_played", "[1,{\"artifactId\":\"trip\"}]")
        kv.put("grant:trip", "[{\"capability\":\"camera\"}]")
        assertThat(PlayedStore(kv).playedIds("trip")).isEmpty()
        assertThat(PlayedStore(kv).pending()).isEmpty()
        assertThat(GrantStore(kv).all()).isEmpty()
    }

    @Test fun `origin store`() {
        val store = OriginStore(InMemoryKeyValueStore())
        assertThat(store.origin).isNull()
        store.origin = "https://a.example"
        assertThat(store.origin).isEqualTo("https://a.example")
    }
}
