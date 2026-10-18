package ai.agentshelf.artifactmcp.geo

import ai.agentshelf.artifactmcp.bridge.Protocol
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One location fix, in WGS84 degrees, metres and epoch milliseconds. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val timestamp: Long)

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance (haversine) in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}

object GeotriggerEngine {
    data class Hit(val item: Protocol.TriggerItem, val distanceM: Double)

    /**
     * Items that fire for [fix]: the device is within `radius_m` of the item, the item has not
     * fired before ([alreadyPlayed]), and the fix is accurate enough to trust (its accuracy
     * radius is not larger than the trigger radius). Hits are ordered nearest first.
     */
    fun hits(fix: Fix, items: List<Protocol.TriggerItem>, alreadyPlayed: Set<String>): List<Hit> {
        if (!fix.lat.isFinite() || !fix.lon.isFinite()) return emptyList()
        return items.asSequence()
            .filter { it.item.id !in alreadyPlayed }
            .filter { fix.accuracyM <= it.radiusM }
            .map { Hit(it, GeoMath.distanceM(fix.lat, fix.lon, it.lat, it.lon)) }
            .filter { it.distanceM <= it.item.radiusM }
            .sortedBy { it.distanceM }
            .toList()
    }
}
