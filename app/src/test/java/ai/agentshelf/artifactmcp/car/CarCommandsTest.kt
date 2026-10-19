package ai.agentshelf.artifactmcp.car

import ai.agentshelf.artifactmcp.bridge.Protocol.MediaAction
import ai.agentshelf.artifactmcp.state.InMemoryKeyValueStore
import androidx.media3.session.CommandButton
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarCommandsTest {
    private val labels = CarCommands.Labels("Back 15 s", "Speed %1\$s")

    @Test fun `custom commands map to the rewind and speed actions`() {
        assertThat(CarCommands.action(CarCommands.REWIND)).isEqualTo(MediaAction.REWIND)
        assertThat(CarCommands.action(CarCommands.SPEED)).isEqualTo(MediaAction.SPEED)
        assertThat(CarCommands.action("something.else")).isNull()
    }

    @Test fun `buttons follow what the source supports`() {
        assertThat(CarCommands.buttons(false, false, null, labels)).isEmpty()
        val both = CarCommands.buttons(true, true, "1.5", labels)
        assertThat(both.map { it.command }).containsExactly(CarCommands.REWIND, CarCommands.SPEED).inOrder()
        assertThat(both[0].displayName).isEqualTo("Back 15 s")
        assertThat(both[0].icon).isEqualTo(CommandButton.ICON_SKIP_BACK_15)
        assertThat(both[1].displayName).isEqualTo("Speed 1.5×")
        assertThat(both[1].icon).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_5)
        // Neither takes the previous or next slot.
        assertThat(both.flatMap { it.slots }).containsNoneOf(CommandButton.SLOT_BACK, CommandButton.SLOT_FORWARD, CommandButton.SLOT_CENTRAL)
        assertThat(CarCommands.buttons(false, true, null, labels).single().command).isEqualTo(CarCommands.SPEED)
    }

    @Test fun `speed label and icon show the current rate`() {
        assertThat(CarCommands.rateLabel("1")).isEqualTo("1×")
        assertThat(CarCommands.rateLabel("1.25")).isEqualTo("1.25×")
        assertThat(CarCommands.rateLabel("1.50")).isEqualTo("1.5×")
        assertThat(CarCommands.rateLabel("0.8")).isEqualTo("0.8×")
        assertThat(CarCommands.rateLabel(null)).isEqualTo("1×")
        assertThat(CarCommands.rateLabel("fast")).isEqualTo("1×")
        val quarter = CarCommands.buttons(false, true, "1.25", labels).single()
        assertThat(quarter.customIcon).isEqualTo("ic_speed_1_25")
        assertThat(CarCommands.buttons(false, true, "1", labels).single().icon).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_0)
        assertThat(CarCommands.buttons(false, true, "2", labels).single().icon).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_2_0)
        assertThat(CarCommands.buttons(false, true, "3", labels).single().icon).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED)
    }

    @Test fun `native speed cycles 1, 1_25, 1_5 and rewind stops at zero`() {
        assertThat(CarCommands.nextNativeRate(1f)).isEqualTo(1.25f)
        assertThat(CarCommands.nextNativeRate(1.25f)).isEqualTo(1.5f)
        assertThat(CarCommands.nextNativeRate(1.5f)).isEqualTo(1f)
        assertThat(CarCommands.nextNativeRate(0.7f)).isEqualTo(1f)
        assertThat(CarCommands.rateString(1f)).isEqualTo("1")
        assertThat(CarCommands.rateString(1.25f)).isEqualTo("1.25")
        assertThat(CarCommands.rewindTarget(40_000)).isEqualTo(25_000)
        assertThat(CarCommands.rewindTarget(4_000)).isEqualTo(0)
    }
}

class RecentStoreTest {
    private val labels = CarCommands.Labels("Back 15 s", "Speed %1\$s")
    private val origin = "https://artifact.example.com"
    private fun item(id: String, at: Long, title: String = "T $id") = RecentItem(origin, id, title, "homelab", "Family", null, at)

    @Test fun `recent items are newest first, unique and capped at 20`() {
        val store = RecentStore(InMemoryKeyValueStore())
        (1..25).forEach { store.record(item("a$it", it * 100_000L)) }
        store.record(item("a3", 9_000_000L))
        val all = store.forOrigin(origin)
        assertThat(all).hasSize(RecentStore.MAX)
        assertThat(all.first().artifactId).isEqualTo("a3")
        assertThat(all.map { it.artifactId }.toSet()).hasSize(RecentStore.MAX)
        assertThat(store.forOrigin("https://other.example.com")).isEmpty()
        assertThat(store.forOrigin(null)).isEmpty()
    }

    @Test fun `unchanged entries are not rewritten within a minute and listeners hear changes`() {
        val kv = InMemoryKeyValueStore()
        val store = RecentStore(kv)
        var heard = 0
        store.addListener { heard++ }
        store.record(item("a", 1_000))
        store.record(item("a", 2_000))
        assertThat(heard).isEqualTo(1)
        assertThat(store.all().single().at).isEqualTo(1_000)
        store.record(item("a", 1_000 + RecentStore.MIN_TOUCH_MS + 1))
        assertThat(heard).isEqualTo(2)
        store.record(item("a", 200_000, title = "Renamed"))
        assertThat(store.all().single().title).isEqualTo("Renamed")
        store.clear()
        assertThat(store.all()).isEmpty()
    }

    @Test fun `known progress is kept and bad entries are dropped`() {
        val kv = InMemoryKeyValueStore()
        val store = RecentStore(kv)
        store.record(item("a", 1).copy(fraction = 0.5))
        store.record(item("a", 500_000))
        assertThat(store.all().single().fraction).isEqualTo(0.5)
        kv.put("car_recent", """[{"origin":"$origin","artifactId":"../x","at":1},{"origin":"$origin","artifactId":"ok","at":2,"fraction":7}]""")
        assertThat(store.all().map { it.artifactId to it.fraction }).containsExactly("ok" to 1.0)
        kv.put("car_recent", "not json")
        assertThat(store.all()).isEmpty()
    }

    @Test fun `forward is a third button and speed moves to the overflow`() {
        assertThat(CarCommands.action(CarCommands.FORWARD)).isEqualTo(MediaAction.FORWARD)
        val all = CarCommands.buttons(true, true, "1", labels, forward = true)
        assertThat(all.map { it.command }).containsExactly(CarCommands.REWIND, CarCommands.FORWARD, CarCommands.SPEED).inOrder()
        assertThat(all.size).isAtMost(CarCommands.MAX_BUTTONS)
        assertThat(all[1].icon).isEqualTo(CommandButton.ICON_SKIP_FORWARD_30)
        assertThat(all[1].displayName).isEqualTo("Forward 30 s")
        assertThat(all[1].slots.first()).isEqualTo(CommandButton.SLOT_FORWARD_SECONDARY)
        assertThat(all[2].slots).containsExactly(CommandButton.SLOT_OVERFLOW)
        // Without forward, speed keeps the secondary slot.
        assertThat(CarCommands.buttons(true, true, "1", labels).last().slots.first()).isEqualTo(CommandButton.SLOT_FORWARD_SECONDARY)
    }

    @Test fun `forward target is 30 s on, within the duration when known`() {
        assertThat(CarCommands.forwardTarget(10_000, 100_000)).isEqualTo(40_000)
        assertThat(CarCommands.forwardTarget(90_000, 100_000)).isEqualTo(100_000)
        assertThat(CarCommands.forwardTarget(90_000, androidx.media3.common.C.TIME_UNSET)).isEqualTo(120_000)
    }

    @Test fun `played share is stored, small changes are throttled, pause updates it in place`() {
        val store = RecentStore(InMemoryKeyValueStore())
        var heard = 0
        store.addListener { heard++ }
        store.record(item("a", 1_000).copy(fraction = 0.10))
        store.record(item("a", 2_000).copy(fraction = 0.104))
        assertThat(store.all().single().fraction).isEqualTo(0.10)
        store.record(item("a", 3_000).copy(fraction = 0.2))
        assertThat(store.all().single().fraction).isEqualTo(0.2)
        assertThat(heard).isEqualTo(2)
        store.record(item("b", 4_000))
        store.updateFraction(origin, "a", 0.5)
        assertThat(store.all().map { it.artifactId to it.fraction }).containsExactly("b" to null, "a" to 0.5).inOrder()
        store.updateFraction(origin, "missing", 0.5)
        assertThat(store.all()).hasSize(2)
        store.updateFraction(origin, "a", 3.0)
        assertThat(store.all().last().fraction).isEqualTo(1.0)
    }

    @Test fun `car errors explain why resumption or search cannot play`() {
        val ready = ai.agentshelf.artifactmcp.library.LibraryView.Ready(ai.agentshelf.artifactmcp.library.Library(emptyList()), false)
        assertThat(CarErrors.resumption(false, true, ready, true, true)).isEqualTo(CarErrors.Kind.NOT_SET_UP)
        assertThat(CarErrors.resumption(true, false, ready, true, true)).isEqualTo(CarErrors.Kind.NOTHING_TO_RESUME)
        assertThat(CarErrors.resumption(true, true, ai.agentshelf.artifactmcp.library.LibraryView.SignedOut, true, true)).isEqualTo(CarErrors.Kind.SIGNED_OUT)
        assertThat(CarErrors.resumption(true, true, ready, false, true)).isEqualTo(CarErrors.Kind.OFFLINE)
        assertThat(CarErrors.resumption(true, true, ready, true, true)).isNull()
        // Metadata only (the resume card after a reboot) works offline and signed out.
        assertThat(CarErrors.resumption(true, true, ai.agentshelf.artifactmcp.library.LibraryView.SignedOut, false, false)).isNull()
        assertThat(CarErrors.searchMiss(ready, true)).isEqualTo(CarErrors.Kind.NO_MATCH)
        assertThat(CarErrors.searchMiss(ai.agentshelf.artifactmcp.library.LibraryView.SignedOut, true)).isEqualTo(CarErrors.Kind.SIGNED_OUT)
        assertThat(CarErrors.searchMiss(ai.agentshelf.artifactmcp.library.LibraryView.Unavailable, false)).isEqualTo(CarErrors.Kind.OFFLINE)
        assertThat(CarErrors.searchMiss(ai.agentshelf.artifactmcp.library.LibraryView.Unavailable, true)).isEqualTo(CarErrors.Kind.LIBRARY_UNAVAILABLE)
        assertThat(CarErrors.Kind.SIGNED_OUT.sessionCode).isEqualTo(androidx.media3.session.SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED)
    }
}
