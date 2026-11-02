package ai.backfm.core

import org.junit.Assert.*
import org.junit.Test

class ReturnArrivalTest {
    private val home = DestinationIdentity("집", "테스트시 중앙로 10-1", "home", "0", "test-provider")
    private fun arrival(address: String = home.address, route: DestinationIdentity = home, name: String = "서울 귀가") =
        ArrivalEvidence(name, address, RouteObservation(route, 1000), 2000)
    @Test fun differentDisplayNamesMatchBoundPoiAndArrivalAddress() {
        assertEquals(ArrivalDecision.RETURN_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(name = "우리 집 도착")))
    }
    @Test fun sameNameAtDifferentAddressDoesNotMatch() {
        assertEquals(ArrivalDecision.OTHER_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(address = "테스트시 다른길 20", name = "집")))
    }
    @Test fun differentPoiAtSameAddressDoesNotMatch() {
        assertEquals(ArrivalDecision.OTHER_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(route = home.copy(poiId = "store"))))
    }
    @Test fun providerNamespaceIsPartOfIdentity() {
        assertEquals(ArrivalDecision.OTHER_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(route = home.copy(poiProvider = "other-provider"))))
    }
    @Test fun subIdIsPartOfIdentity() {
        assertEquals(ArrivalDecision.OTHER_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(route = home.copy(poiSubId = "1"))))
    }
    @Test fun noBoundTargetNeverFallsBackToName() {
        assertEquals(ArrivalDecision.UNVERIFIED, ReturnArrivalMatcher.evaluate(null, arrival(name = "집")))
    }
    @Test fun noArrivalAddressCannotBorrowTheCachedPoi() {
        assertEquals(ArrivalDecision.UNVERIFIED, ReturnArrivalMatcher.evaluate(home, arrival(address = "")))
    }
    @Test fun oldRouteObservationCannotStopPlayback() {
        val event = arrival().copy(receivedAtMillis = 46_001)
        assertEquals(ArrivalDecision.UNVERIFIED, ReturnArrivalMatcher.evaluate(home, event))
    }
    @Test fun spacesAndUnicodeWidthNormalizeButAddressNumbersDoNotCollapse() {
        assertEquals(ArrivalDecision.RETURN_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(address = "테스트시  중앙로 １０－１")))
        assertEquals(ArrivalDecision.OTHER_DESTINATION, ReturnArrivalMatcher.evaluate(home, arrival(address = "테스트시 중앙로 101")))
    }
    @Test fun addressOnlyTargetStillRequiresMatchingCurrentRoute() {
        val addressOnly = home.copy(poiId = "", poiSubId = null, poiProvider = "")
        assertEquals(ArrivalDecision.RETURN_DESTINATION, ReturnArrivalMatcher.evaluate(addressOnly, arrival()))
        assertEquals(ArrivalDecision.OTHER_DESTINATION, ReturnArrivalMatcher.evaluate(addressOnly, arrival(route = home.copy(address = "다른 주소"))))
    }
    @Test fun destinationChangeClearsRouteEvidence() {
        val tracker = NavigationArrivalTracker { 1000 }
        tracker.observe(home); tracker.clear()
        assertEquals(ArrivalDecision.UNVERIFIED, ReturnArrivalMatcher.evaluate(home, tracker.arrival("집", home.address)))
    }
    @Test fun capturedArrivalIsIndependentOfLaterDestinationUpdates() {
        var now = 1000L
        val tracker = NavigationArrivalTracker { now }
        tracker.observe(home); now = 1100
        val captured = tracker.arrival("집", home.address)
        tracker.observe(home.copy(poiId = "next", address = "다음 장소"))
        assertEquals(ArrivalDecision.RETURN_DESTINATION, ReturnArrivalMatcher.evaluate(home, captured))
    }
    @Test fun trackerAgeUsesMonotonicObservationTime() {
        var now = 1000L
        val tracker = NavigationArrivalTracker { now }
        tracker.observe(home); now += 45_001
        assertEquals(ArrivalDecision.UNVERIFIED, ReturnArrivalMatcher.evaluate(home, tracker.arrival("집", home.address)))
    }
}
