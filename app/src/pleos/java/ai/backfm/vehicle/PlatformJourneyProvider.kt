package ai.backfm.vehicle

import android.content.Context
import android.content.pm.PackageManager
import ai.backfm.core.DataSource
import ai.backfm.core.DestinationIdentity
import ai.backfm.core.NavigationArrivalTracker
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** API signatures verified against official NaviHelper 3.0.5 and navi-data 3.0.4. */
class PlatformJourneyProvider(private val context: Context) : JourneyProvider {
    companion object { val requiredPermission: String? = "pleos.car.permission.NAVI_ROUTE" }
    override fun events() = callbackFlow<JourneyEvent> {
        val helper = NaviHelper(context)
        val arrivals = NavigationArrivalTracker(android.os.SystemClock::elapsedRealtime)
        val released = AtomicBoolean(false)
        val initialized = AtomicBoolean(false)
        val refresh = launch {
            while (true) {
                delay(15_000)
                if (initialized.get() && !released.get() && context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") == PackageManager.PERMISSION_GRANTED) {
                    runCatching { helper.getDestinationInfo() }.onFailure { trySend(JourneyEvent.Status("잔여시간 갱신 실패 · 마지막 정보에서 경과 시간을 차감합니다.")) }
                }
            }
        }
        val watchdog = launch {
            delay(10_000)
            if (!initialized.get()) {
                trySend(JourneyEvent.Status("NaviHelper 연결 시간 초과 · Demo Mode 또는 수동 기록을 사용해 주세요."))
                close()
            }
        }
        val listener = object : NaviHelperEventListener {
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                trySend(JourneyEvent.Candidate(destinationArrivedInfo.name, System.currentTimeMillis(), DataSource.NAVIGATION))
                trySend(JourneyEvent.Arrived(arrivals.arrival(destinationArrivedInfo.name, destinationArrivedInfo.address)))
            }
            override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
                trySend(JourneyEvent.Candidate(waypointArrivedInfo.name, System.currentTimeMillis(), DataSource.NAVIGATION))
            }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                // A destination is not evidence of a visit. Read only the ETA here.
                trySend(JourneyEvent.Destination(arrivals.observe(DestinationIdentity(destinationInfo.name, destinationInfo.address,
                    destinationInfo.poiId, destinationInfo.poiSubId, destinationInfo.poiProvider))))
                trySend(JourneyEvent.Remaining(destinationInfo.remainTime.takeIf { it >= 0 }))
            }
            override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
                recentDestinationInfoList.take(20).forEach {
                    trySend(JourneyEvent.Candidate(it.name, System.currentTimeMillis(), DataSource.RECENT_DESTINATION, "recent:${it.poiId}:${it.name}"))
                }
            }
            override fun onDestinationChanged() {
                arrivals.clear()
                trySend(JourneyEvent.Destination(null))
                if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") == PackageManager.PERMISSION_GRANTED) runCatching { helper.getDestinationInfo() }
            }
            override fun onRouteCancelled() { arrivals.clear(); trySend(JourneyEvent.Destination(null)) }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") == PackageManager.PERMISSION_GRANTED) runCatching { helper.getDestinationInfo() }
            }
            override fun onError(errorCode: NaviErrorCode) { trySend(JourneyEvent.Status("내비게이션 응답 오류 · 확인된 장소를 직접 추가할 수 있습니다.")) }
        }
        try {
            helper.initialize(
                { if (!released.get()) {
                    initialized.set(true); watchdog.cancel()
                    runCatching {
                        if (context.checkSelfPermission("pleos.car.permission.NAVI_ROUTE") != PackageManager.PERMISSION_GRANTED) {
                            trySend(JourneyEvent.Status("내비 권한 확인 실패 · 설정에서 내비 권한을 허용한 뒤 기록을 재연결해 주세요."))
                            close(); return@runCatching
                        }
                        helper.addListener(listener)
                        helper.getDestinationInfo(); helper.getRecentDestinationInfo(); helper.getRouteStateInfo()
                        trySend(JourneyEvent.Status("실제 NaviHelper 연결됨 · 앱이 관찰한 이벤트만 수집"))
                    }.onFailure { trySend(JourneyEvent.Status("NaviHelper 조회 실패 · 수동 장소 추가를 사용할 수 있습니다.")); close() }
                } },
                { initialized.set(true); watchdog.cancel(); trySend(JourneyEvent.Status("NaviHelper 연결 실패 · Demo Mode 또는 수동 장소 추가를 사용해 주세요.")); close() }
            )
        } catch (_: Exception) {
            trySend(JourneyEvent.Status("NaviHelper 초기화 실패 · 수동 기록을 사용할 수 있습니다."))
            close()
        }
        awaitClose { released.set(true); watchdog.cancel(); refresh.cancel(); runCatching { helper.removeListener(listener); helper.release() } }
    }.buffer(Channel.UNLIMITED)
}
