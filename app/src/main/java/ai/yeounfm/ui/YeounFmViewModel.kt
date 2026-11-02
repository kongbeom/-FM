package ai.yeounfm.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import ai.yeounfm.YeounFmApp
import ai.yeounfm.audio.RadioService
import ai.yeounfm.core.*
import ai.yeounfm.data.*
import ai.yeounfm.vehicle.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UiState(
    val loading: Boolean = true, val settings: PrivacySettings = PrivacySettings(),
    val trip: TripSession? = null, val visits: List<VisitCandidate> = emptyList(),
    val completedNames: List<String> = emptyList(), val saved: List<Episode> = emptyList(),
    val episode: Episode? = null, val remainingSeconds: Int? = null,
    val message: String = "", val busy: Boolean = false,
    val error: String = "", val currentDestination: RouteObservation? = null,
    val safety: SafetyState = SafetyState(true, "준비 중"), val providerStatus: String = "기록을 시작해 주세요"
)

class YeounFmViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YeounFmApp
    private val dao = app.database.records()
    private val mutation = app.dataMutex
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private var recording: Job? = null
    init {
        viewModelScope.launch {
            combine(dao.observe(), app.settings.flow, app.safety.state) { rows, settings, safety -> Triple(rows, settings, safety) }.collect { (rows, settings, safety) ->
                val trip = rows.firstOrNull { it.kind == "trip" }?.let(Codec::trip)
                mutable.update { old -> old.copy(
                    loading = false, settings = settings, safety = safety, trip = trip,
                    visits = rows.filter { it.kind == "visit" }.map(Codec::visit).filter { it.tripId == trip?.id }.sortedBy { it.sequence },
                    completedNames = rows.filter { it.kind == "pick" }.map(Codec::pick).filter { it.tripId == trip?.id }.map { it.nickname },
                    saved = rows.filter { it.kind == "episode" }.map(Codec::episode).sortedByDescending { it.createdAt },
                    providerStatus = if (old.trip == null && trip != null && recording?.isActive != true) {
                        if (trip.status == TripStatus.RECORDING) "여정 복원됨 · 여행 기록에서 기록 연결을 재개해 주세요" else "종료한 여정 복원됨 · 기억을 고를 수 있어요"
                    } else old.providerStatus
                ) }
                if (!settings.consent) { recording?.cancel(); recording = null }
            }
        }
    }
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        mutation.withLock {
            try { mutable.update { it.copy(error = "") }; block() } catch (e: EpisodeException) {
                val error = e.message ?: "입력을 확인해 주세요."
                mutable.update { it.copy(error = error) }; message(error)
            }
            catch (e: Exception) {
                mutable.update { it.copy(error = "처리하지 못했습니다. 다시 시도해 주세요. 기록은 기기에 보관됩니다.") }
                android.util.Log.e("여운FM", "Operation failed: ${e.javaClass.simpleName}")
                message("처리하지 못했습니다. 다시 시도해 주세요. 기록은 기기에 보관됩니다.")
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun requireInput() {
        if (app.safety.state.value.locked) throw EpisodeException("정차 후 이용해 주세요.")
        if (!app.settings.flow.first().consent) throw EpisodeException("설정에서 기록·방송 활용에 동의해 주세요.")
    }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun clearMessage() = message("")
    fun consent(guardian: Boolean) = work { app.settings.consent(true, guardian) }
    fun voice(enabled: Boolean) = work {
        if (app.safety.state.value.locked) return@work
        app.settings.voice(enabled)
        if (!enabled) stopPlayback()
    }
    fun mode(demo: Boolean) = work {
        requireInput(); recording?.cancelAndJoin(); recording = null; stopPlayback()
        app.database.withTransaction { dao.clearTrip() }
        app.settings.demo(demo)
        app.rideTime.clear()
        mutable.update { it.copy(episode = null, remainingSeconds = null, currentDestination = null, providerStatus = if (demo) "DEMO · 가상 여정" else "실제 차량 · 기록 시작 후 연결") }
    }
    fun startTrip() = work {
        requireInput()
        recording?.cancelAndJoin(); stopPlayback()
        val demo = mutable.value.settings.demo
        val trip = TripSession(UUID.randomUUID().toString(), System.currentTimeMillis(), dataSource = if (demo) DataSource.DEMO else DataSource.NAVIGATION,
            returnTarget = if (demo) DemoJourneyProvider.RETURN_TARGET else null)
        app.database.withTransaction { dao.clearTrip(); dao.put(Codec.trip(trip)) }
        app.rideTime.begin(trip.id)
        mutable.update { it.copy(episode = null, remainingSeconds = null, currentDestination = null) }
        startProvider(trip, demo)
    }
    fun resumeTrip() = work {
        requireInput()
        val trip = dao.read("trip").firstOrNull()?.let(Codec::trip) ?: throw EpisodeException("여정을 먼저 시작해 주세요.")
        recording?.cancelAndJoin()
        app.rideTime.begin(trip.id)
        startProvider(trip, trip.dataSource == DataSource.DEMO)
    }
    private fun startProvider(trip: TripSession, demo: Boolean) {
        recording = viewModelScope.launch {
            val provider = app.journeyProviderFactory(demo)
            provider.events().catch { message("내비 연결이 중단되었습니다. 장소를 직접 추가할 수 있습니다.") }.collect { event ->
                mutation.withLock {
                    val active = dao.read("trip").firstOrNull()?.let(Codec::trip)
                    if (active?.id != trip.id || !mutable.value.settings.consent) return@withLock
                    when (event) {
                        is JourneyEvent.Candidate -> {
                            if (active.status != TripStatus.RECORDING) return@withLock
                            val existing = dao.read("visit").map(Codec::visit)
                            val candidate = VisitCandidate(UUID.randomUUID().toString(), trip.id, event.name.ifBlank { "이름 없는 장소 · 확인 필요" }.take(80), event.time, event.source, eventKey = event.key)
                            JourneyRules.add(existing, candidate).lastOrNull()?.takeIf { it.id == candidate.id }?.let { dao.put(Codec.visit(it)) }
                        }
                        is JourneyEvent.Remaining -> {
                            app.rideTime.update(trip.id, event.seconds)
                            mutable.update { it.copy(remainingSeconds = app.rideTime.remainingSeconds()) }
                        }
                        is JourneyEvent.Status -> mutable.update { it.copy(providerStatus = event.message) }
                        is JourneyEvent.Destination -> mutable.update { it.copy(currentDestination = event.observation) }
                        is JourneyEvent.Arrived -> handleArrival(active, event.evidence)
                    }
                }
            }
        }
    }
    fun endTrip() = work {
        requireInput()
        val trip = dao.read("trip").firstOrNull()?.let(Codec::trip) ?: return@work
        dao.put(Codec.trip(trip.copy(status = TripStatus.ENDED, endTime = System.currentTimeMillis())))
        if (trip.returnTarget == null) { recording?.cancel(); recording = null }
        mutable.update { it.copy(providerStatus = if (trip.returnTarget != null) "방문 기록 종료 · 지정한 귀가 목적지의 도착 확인은 계속합니다" else "여행 기록 종료 · 기억을 고를 수 있어요") }
    }
    private fun handleArrival(trip: TripSession, evidence: ArrivalEvidence) {
        when (ReturnArrivalMatcher.evaluate(trip.returnTarget, evidence)) {
            ArrivalDecision.RETURN_DESTINATION -> {
                app.rideTime.update(trip.id, 0); stopPlayback()
                mutable.update { it.copy(remainingSeconds = 0, providerStatus = "지정한 귀가 목적지 도착 확인 · 방송 종료") }
            }
            ArrivalDecision.UNVERIFIED -> mutable.update { it.copy(providerStatus = "도착 기록 수신 · 귀가 식별 정보가 부족해 자동 종료하지 않았습니다") }
            ArrivalDecision.OTHER_DESTINATION -> Unit
        }
    }
    fun bindCurrentReturnTarget() = work {
        requireInput()
        val observation = mutable.value.currentDestination?.takeIf { it.isFresh(android.os.SystemClock.elapsedRealtime()) }
            ?: throw EpisodeException("최근 내비 목적지 정보가 없습니다. 내비 연결을 재개한 뒤 지정해 주세요.")
        if (observation.destination.address.isBlank()) throw EpisodeException("목적지 주소를 확인할 수 없어 귀가 목적지로 지정할 수 없습니다.")
        val trip = dao.read("trip").firstOrNull()?.let(Codec::trip) ?: throw EpisodeException("여정을 먼저 시작해 주세요.")
        val bound = trip.copy(returnTarget = observation.destination)
        dao.put(Codec.trip(bound))
        if (recording?.isActive != true) {
            recording?.cancelAndJoin()
            startProvider(bound, bound.dataSource == DataSource.DEMO)
        }
    }
    fun clearReturnTarget() = work {
        requireInput()
        dao.read("trip").firstOrNull()?.let(Codec::trip)?.let { trip ->
            dao.put(Codec.trip(trip.copy(returnTarget = null)))
            if (trip.status == TripStatus.ENDED) {
                recording?.cancel(); recording = null
                mutable.update { it.copy(currentDestination = null, providerStatus = "귀가 도착 확인 연결 해제됨") }
            }
        }
    }
    fun returnDestination(name: String) = work {
        requireInput(); require(name.isNotBlank() && name.length <= 80)
        dao.read("trip").firstOrNull()?.let(Codec::trip)?.let { dao.put(Codec.trip(it.copy(returnDestination = name.trim()))) }
    }
    fun verifyVisit(id: String, verification: Verification) = work {
        requireInput(); stopPlayback()
        val visit = dao.read("visit").map(Codec::visit).first { it.id == id }
        app.database.withTransaction {
            dao.put(Codec.visit(visit.copy(verification = verification)))
            if (verification == Verification.REJECTED) dao.read("pick").map(Codec::pick).filter { it.visitId == id }.forEach { dao.delete("pick", it.id) }
        }
        mutable.update { it.copy(episode = null) }
    }
    fun renameVisit(id: String, name: String) = work {
        requireInput(); require(name.isNotBlank() && name.length <= 80)
        stopPlayback()
        val visit = dao.read("visit").map(Codec::visit).first { it.id == id }
        dao.put(Codec.visit(visit.copy(placeName = name.trim())))
        mutable.update { it.copy(episode = null) }
    }
    fun addVisit(name: String) = work {
        requireInput(); require(name.isNotBlank() && name.length <= 80)
        val trip = dao.read("trip").firstOrNull()?.let(Codec::trip) ?: throw EpisodeException("여정을 먼저 시작해 주세요.")
        val visits = dao.read("visit").map(Codec::visit)
        dao.put(Codec.visit(VisitCandidate(UUID.randomUUID().toString(), trip.id, name.trim(), System.currentTimeMillis(), DataSource.USER, Verification.USER_ADDED, visits.size)))
    }
    fun submitPick(nickname: String, visitId: String, emotion: String, note: String, elapsedMs: Long) = work {
        requireInput()
        require(nickname.isNotBlank() && nickname.length <= 20 && emotion.isNotBlank() && emotion.length <= 40 && note.length <= 120)
        val trip = dao.read("trip").firstOrNull()?.let(Codec::trip) ?: throw EpisodeException("여정을 먼저 시작해 주세요.")
        val visit = dao.read("visit").map(Codec::visit).firstOrNull { it.id == visitId && it.usable && it.tripId == trip.id } ?: throw EpisodeException("확인된 장소를 선택해 주세요.")
        val current = dao.read("pick").map(Codec::pick)
        if (current.any { it.nickname == nickname.trim() }) throw EpisodeException("다른 닉네임을 사용하거나 참여 기록을 초기화해 주세요.")
        if (current.size >= 8) throw EpisodeException("한 방송에 최대 8명까지 참여할 수 있습니다.")
        stopPlayback()
        dao.put(Codec.pick(MemoryPick(UUID.randomUUID().toString(), trip.id, nickname.trim(), visit.id, emotion.trim(), note.trim(), elapsedMs.coerceAtLeast(0))))
        mutable.update { it.copy(episode = null) }
        message("선택을 저장했습니다. 다음 참여자에게 화면을 넘겨주세요.")
    }
    fun resetPicks() = work {
        requireInput(); dao.read("pick").forEach { dao.delete("pick", it.id) }; stopPlayback()
        mutable.update { it.copy(episode = null) }
    }
    fun generate(seconds: Int) = work {
        requireInput(); mutable.update { it.copy(busy = true) }; stopPlayback()
        val rows = dao.snapshot()
        val trip = rows.firstOrNull { it.kind == "trip" }?.let(Codec::trip) ?: throw EpisodeException("여정을 먼저 시작해 주세요.")
        val input = EpisodeInput(trip, rows.filter { it.kind == "visit" }.map(Codec::visit), rows.filter { it.kind == "pick" }.map(Codec::pick), seconds, app.rideTime.remainingSeconds())
        val episode = withContext(Dispatchers.Default) { FallbackGenerator().generate(input) }
        mutable.update { it.copy(episode = episode) }
    }
    fun openEpisode(id: String) = work {
        requireInput(); stopPlayback()
        val episode = dao.read("episode").firstOrNull { it.id == id }?.let(Codec::episode) ?: return@work
        mutable.update { it.copy(episode = episode) }
    }
    fun saveEpisode() = work {
        requireInput()
        val current = mutable.value.episode ?: return@work
        val playback = RadioService.status.value
        val path = if (playback.episodeId == current.id) playback.audioPath else current.audioPath
        val savedPath = path?.let {
            val source = File(it)
            val destination = File(app.filesDir, "episodes/${current.id}.wav").apply { parentFile?.mkdirs() }
            if (source.exists()) { if (source.canonicalPath != destination.canonicalPath) source.copyTo(destination, true); destination.absolutePath } else null
        }
        val saved = current.copy(saved = true, audioPath = savedPath)
        dao.put(Codec.episode(saved)); mutable.update { it.copy(episode = saved) }
        message("방송을 기기에 보관했습니다. 음성이 없으면 다시 듣기에서 합성을 재시도합니다.")
    }
    fun deleteEpisode(id: String) = work {
        requireInput()
        if (RadioService.status.value.episodeId == id) stopPlayback()
        dao.read("episode").firstOrNull { it.id == id }?.let(Codec::episode)?.audioPath?.let { File(it).delete() }
        dao.delete("episode", id)
        File(app.cacheDir, "speech/$id.wav").delete()
        if (mutable.value.episode?.id == id) mutable.update { it.copy(episode = null) }
    }
    fun deleteTrip() = work {
        requireInput(); recording?.cancel(); recording = null; stopPlayback(); dao.clearTrip()
        app.rideTime.clear()
        File(app.cacheDir, "speech").listFiles()?.forEach { it.delete() }
        mutable.update { it.copy(episode = null, remainingSeconds = null, currentDestination = null) }
    }
    fun revokeAndDelete() = work {
        if (app.safety.state.value.locked) throw EpisodeException("정차 후 이용해 주세요.")
        recording?.cancel(); recording = null; stopPlayback()
        app.settings.consent(false); dao.clearAll()
        app.rideTime.clear()
        File(app.filesDir, "episodes").listFiles()?.forEach { it.delete() }
        File(app.cacheDir, "speech").listFiles()?.forEach { it.delete() }
        mutable.update { it.copy(episode = null, remainingSeconds = null, currentDestination = null, providerStatus = "동의 철회 · 모든 기록 삭제됨") }
    }
    fun remaining(seconds: Int) {
        if (!app.safety.automotive && mutable.value.settings.demo) mutable.value.trip?.let { trip ->
            app.rideTime.begin(trip.id)
            app.rideTime.update(trip.id, seconds)
            mutable.update { it.copy(remainingSeconds = app.rideTime.remainingSeconds()) }
        }
    }
    fun simulateDriving(value: Boolean) = app.safety.simulateDriving(value)
    fun arrived() = work {
        if (!app.safety.automotive && mutable.value.settings.demo) {
            val trip = dao.read("trip").firstOrNull()?.let(Codec::trip) ?: return@work
            val now = android.os.SystemClock.elapsedRealtime()
            handleArrival(trip, ArrivalEvidence("집", DemoJourneyProvider.RETURN_TARGET.address, RouteObservation(DemoJourneyProvider.RETURN_TARGET, now), now))
            message("DEMO · 도착 이벤트를 귀가 식별 규칙으로 확인했습니다.")
        }
    }
    private fun stopPlayback() { app.startService(RadioService.commandIntent(app, RadioService.STOP)) }
}
