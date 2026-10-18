package ai.agentshelf.artifactmcp.geo

import ai.agentshelf.artifactmcp.bridge.Protocol
import android.location.LocationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeotriggersTest {
    private fun trigger(id: String, lat: Double, lon: Double, radius: Double) =
        Protocol.TriggerItem(Protocol.AudioItem(id, id, "text", null, null), lat, lon, radius)

    @Test fun `haversine matches known distances`() {
        // London (Big Ben) to Paris (Eiffel Tower): about 340.6 km.
        val d = GeoMath.distanceM(51.5007, -0.1246, 48.8584, 2.2945)
        assertThat(d).isWithin(1_500.0).of(340_600.0)
        assertThat(GeoMath.distanceM(10.0, 10.0, 10.0, 10.0)).isEqualTo(0.0)
        // One degree of latitude is about 111.2 km.
        assertThat(GeoMath.distanceM(0.0, 0.0, 1.0, 0.0)).isWithin(100.0).of(111_195.0)
        // Across the antimeridian.
        assertThat(GeoMath.distanceM(0.0, 179.9, 0.0, -179.9)).isWithin(50.0).of(22_239.0)
    }

    @Test fun `fires inside the radius only`() {
        val items = listOf(trigger("a", 0.0, 0.0, 500.0))
        assertThat(GeotriggerEngine.hits(Fix(0.004, 0.0, 10.0, 0L), items, emptySet())).hasSize(1) // ~445 m
        assertThat(GeotriggerEngine.hits(Fix(0.005, 0.0, 10.0, 0L), items, emptySet())).isEmpty() // ~556 m
    }

    @Test fun `already played items never fire again`() {
        val items = listOf(trigger("a", 0.0, 0.0, 500.0))
        assertThat(GeotriggerEngine.hits(Fix(0.0, 0.0, 10.0, 0L), items, setOf("a"))).isEmpty()
    }

    @Test fun `inaccurate fixes are ignored`() {
        val items = listOf(trigger("a", 0.0, 0.0, 500.0))
        assertThat(GeotriggerEngine.hits(Fix(0.0, 0.0, 800.0, 0L), items, emptySet())).isEmpty()
        assertThat(GeotriggerEngine.hits(Fix(0.0, 0.0, 500.0, 0L), items, emptySet())).hasSize(1)
    }

    @Test fun `several hits are ordered nearest first`() {
        val items = listOf(trigger("far", 0.0, 0.0, 5_000.0), trigger("near", 0.01, 0.0, 5_000.0))
        val hits = GeotriggerEngine.hits(Fix(0.011, 0.0, 10.0, 0L), items, emptySet())
        assertThat(hits.map { it.item.item.id }).containsExactly("near", "far").inOrder()
    }

    @Test fun `non-finite fixes are ignored`() {
        val items = listOf(trigger("a", 0.0, 0.0, 500.0))
        assertThat(GeotriggerEngine.hits(Fix(Double.NaN, 0.0, 1.0, 0L), items, emptySet())).isEmpty()
    }

    @Test fun `provider choice prefers fused`() {
        assertThat(LocationService.chooseProvider(31, true, true, true, false)).isEqualTo(LocationManager.FUSED_PROVIDER)
        assertThat(LocationService.chooseProvider(29, false, true, true, true)).isEqualTo(LocationManager.GPS_PROVIDER)
        assertThat(LocationService.chooseProvider(29, false, true, true, false)).isEqualTo(LocationManager.NETWORK_PROVIDER)
        assertThat(LocationService.chooseProvider(29, false, true, false, false)).isEqualTo(LocationManager.GPS_PROVIDER)
        assertThat(LocationService.chooseProvider(29, false, false, false, true)).isNull()
    }
}
