package ai.backfm.vehicle

import ai.backfm.core.DataSource
import ai.backfm.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed interface JourneyEvent {
    data class Candidate(val name: String, val time: Long, val source: DataSource, val key: String = "") : JourneyEvent
    data class Remaining(val seconds: Int?) : JourneyEvent
    data class Status(val message: String) : JourneyEvent
    data class Destination(val observation: RouteObservation?) : JourneyEvent
    data class Arrived(val evidence: ArrivalEvidence) : JourneyEvent
}
interface JourneyProvider { fun events(): Flow<JourneyEvent> }
class DemoJourneyProvider : JourneyProvider {
    companion object {
        val RETURN_TARGET = DestinationIdentity("집 · DEMO", "DEMO 서울 귀가 지점", "demo-home", "0", "BACKFM_DEMO")
    }
    override fun events(): Flow<JourneyEvent> = flow {
        val now = System.currentTimeMillis()
        emit(JourneyEvent.Status("DEMO · 가상의 춘천 여정"))
        emit(JourneyEvent.Destination(RouteObservation(RETURN_TARGET, android.os.SystemClock.elapsedRealtime())))
        emit(JourneyEvent.Candidate("서울 출발", now - 18_000_000, DataSource.DEMO, "demo:seoul"))
        emit(JourneyEvent.Candidate("춘천 점심 식당", now - 10_800_000, DataSource.DEMO, "demo:lunch"))
        emit(JourneyEvent.Candidate("소양강 스카이워크", now - 7_200_000, DataSource.DEMO, "demo:skywalk"))
        emit(JourneyEvent.Remaining(2400))
    }
}
