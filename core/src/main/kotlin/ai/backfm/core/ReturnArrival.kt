package ai.backfm.core

import java.text.Normalizer

/** Only a user-bound return target is persisted. Names are labels, never identifiers. */
data class DestinationIdentity(
    val name: String, val address: String, val poiId: String = "",
    val poiSubId: String? = null, val poiProvider: String = ""
)
data class RouteObservation(val destination: DestinationIdentity, val observedAtMillis: Long) {
    fun isFresh(now: Long) = now - observedAtMillis in 0..ReturnArrivalMatcher.MAX_ROUTE_AGE_MS
}
/** The SDK arrival has no POI ID. Keep its address separate from the observed route. */
data class ArrivalEvidence(val name: String, val address: String, val route: RouteObservation?, val receivedAtMillis: Long)
enum class ArrivalDecision { RETURN_DESTINATION, OTHER_DESTINATION, UNVERIFIED }

object ReturnArrivalMatcher {
    const val MAX_ROUTE_AGE_MS = 45_000L
    private fun addressKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }
    fun evaluate(target: DestinationIdentity?, arrival: ArrivalEvidence): ArrivalDecision {
        if (target == null || target.address.isBlank() || arrival.address.isBlank()) return ArrivalDecision.UNVERIFIED
        if (addressKey(target.address) != addressKey(arrival.address)) return ArrivalDecision.OTHER_DESTINATION
        val route = arrival.route?.takeIf { it.isFresh(arrival.receivedAtMillis) }?.destination ?: return ArrivalDecision.UNVERIFIED
        if (target.poiId.isNotBlank() && route.poiId.isNotBlank()) {
            val samePoi = target.poiId.trim() == route.poiId.trim() &&
                target.poiSubId.orEmpty().trim() == route.poiSubId.orEmpty().trim() &&
                target.poiProvider.trim() == route.poiProvider.trim()
            return if (samePoi) ArrivalDecision.RETURN_DESTINATION else ArrivalDecision.OTHER_DESTINATION
        }
        if (route.address.isBlank()) return ArrivalDecision.UNVERIFIED
        return if (addressKey(target.address) == addressKey(route.address)) ArrivalDecision.RETURN_DESTINATION else ArrivalDecision.OTHER_DESTINATION
    }
}

/** Cleared immediately on destination change/cancellation; arrival carries its own immutable snapshot. */
class NavigationArrivalTracker(private val elapsedMillis: () -> Long) {
    private var route: RouteObservation? = null
    @Synchronized fun observe(destination: DestinationIdentity): RouteObservation =
        RouteObservation(destination, elapsedMillis()).also { route = it }
    @Synchronized fun clear() { route = null }
    @Synchronized fun arrival(name: String, address: String) = ArrivalEvidence(name, address, route, elapsedMillis())
}
