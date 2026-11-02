package ai.backfm.vehicle

import android.content.Context
import kotlinx.coroutines.flow.flow

class PlatformJourneyProvider(@Suppress("UNUSED_PARAMETER") context: Context) : JourneyProvider {
    companion object { val requiredPermission: String? = null }
    override fun events() = flow<JourneyEvent> { emit(JourneyEvent.Status("이 APK에는 차량 SDK가 없습니다. Pleos APK를 사용하거나 Demo Mode로 전환해 주세요.")) }
}
