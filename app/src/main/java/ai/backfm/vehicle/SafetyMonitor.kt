package ai.backfm.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SafetyState(val locked: Boolean, val label: String)
/** Automotive input fails closed until the system explicitly reports unrestricted UX. */
class SafetyMonitor(context: Context) {
    val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private val current = MutableStateFlow(SafetyState(automotive, if (automotive) "차량 제한 상태 확인 중 · 입력 잠금" else "DEMO · 정차 시뮬레이션"))
    val state = current.asStateFlow()
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    init {
        if (automotive) runCatching {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (!ready) { current.value = SafetyState(true, "차량 연결 끊김 · 입력 잠금"); return@createCar }
                runCatching {
                    manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                    val applyState: (Boolean) -> Unit = { restricted -> current.value = SafetyState(restricted, if (restricted) "주행 제한 · 차량 미디어 컨트롤을 이용해 주세요" else "시스템 확인 · 정차 중") }
                    manager!!.registerListener { applyState(it.isRequiresDistractionOptimization) }
                    applyState(manager!!.currentCarUxRestrictions.isRequiresDistractionOptimization)
                }.onFailure { current.value = SafetyState(true, "차량 제한 조회 실패 · 입력 잠금") }
            }
        }.onFailure { current.value = SafetyState(true, "차량 API 사용 불가 · 입력 잠금") }
    }
    fun simulateDriving(value: Boolean) { if (!automotive) current.value = SafetyState(value, if (value) "DEMO · 주행 시뮬레이션 / 입력 잠금" else "DEMO · 정차 시뮬레이션") }
}
