package ai.yeounfm

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import ai.yeounfm.audio.RadioService
import ai.yeounfm.core.*
import ai.yeounfm.data.Codec
import ai.yeounfm.data.Record
import ai.yeounfm.ui.YeounFmViewModel
import ai.yeounfm.ui.MainActivity
import ai.yeounfm.vehicle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Inject navigation events; use the real repository, ViewModel and MediaPlayer. No live SDK claim. */
@RunWith(AndroidJUnit4::class)
class ArrivalRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as YeounFmApp
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: YeounFmViewModel
    private lateinit var originalFactory: (Boolean) -> JourneyProvider
    private lateinit var events: Channel<JourneyEvent>
    private val home = DestinationIdentity("집", "시연시 귀가로 10-1", "test-home", "0", "YEOUNFM_TEST")
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("$message: ${RadioService.status.value}", condition())
    }
    private fun send(event: JourneyEvent) = runBlocking { events.send(event) }
    private fun evidence(route: DestinationIdentity = home, address: String = home.address, name: String = "우리 집") =
        SystemClock.elapsedRealtime().let { ArrivalEvidence(name, address, RouteObservation(route, it), it) }
    private fun barrier() {
        val marker = "arrival-test-${SystemClock.elapsedRealtimeNanos()}"
        send(JourneyEvent.Status(marker)); waitFor("Events processed") { model.state.value.providerStatus == marker }
        Thread.sleep(100)
    }
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        originalFactory = app.journeyProviderFactory
        events = Channel(Channel.UNLIMITED)
        instrumentation.runOnMainSync {
            app.startService(RadioService.commandIntent(app, RadioService.STOP))
            app.safety.simulateDriving(false)
            app.journeyProviderFactory = { object : JourneyProvider { override fun events() = events.receiveAsFlow() } }
        }
        app.rideTime.clear()
        runBlocking { app.settings.consent(true, true); app.settings.demo(false); app.settings.voice(true); app.database.records().clearAll() }
        scenario.onActivity { model = ViewModelProvider(it)[YeounFmViewModel::class.java] }
        waitFor("Settings ready") { !model.state.value.loading && model.state.value.settings.consent && !model.state.value.settings.demo }
        runBlocking { model.startTrip().join() }
        waitFor("Trip started") { model.state.value.trip != null }
        send(JourneyEvent.Destination(RouteObservation(home, SystemClock.elapsedRealtime())))
        send(JourneyEvent.Remaining(2400))
        waitFor("Destination received") { model.state.value.currentDestination?.destination == home }
    }
    @After fun cleanup() {
        scenario.close(); events.close()
        instrumentation.runOnMainSync { app.journeyProviderFactory = originalFactory; app.startService(RadioService.commandIntent(app, RadioService.STOP)) }
        app.rideTime.clear()
    }
    private fun bind() {
        runBlocking { model.bindCurrentReturnTarget().join(); model.returnDestination("서울 귀가").join() }
        waitFor("Target bound") { model.state.value.trip?.returnTarget == home }
    }
    private fun playSample() {
        instrumentation.runOnMainSync { app.startForegroundService(RadioService.commandIntent(app, RadioService.SAMPLE)) }
        waitFor("Sample playing") { RadioService.status.value.playing }
    }
    @Test fun differentNamesStopEvenWhenNewestVisitIsUnrelated() {
        bind(); playSample()
        send(JourneyEvent.Candidate("관련 없는 최근 목적지", System.currentTimeMillis() + 3600_000, DataSource.RECENT_DESTINATION))
        send(JourneyEvent.Arrived(evidence(name = "우리집")))
        waitFor("Return arrival stops actual player") { RadioService.status.value.episodeId == null }
        assertEquals(0L, app.rideTime.remainingMillis())
    }
    @Test fun sameDisplayNameAtDifferentDestinationDoesNotStop() {
        bind(); playSample()
        val other = home.copy(poiId = "other-home", address = "시연시 다른로 20")
        send(JourneyEvent.Destination(RouteObservation(other, SystemClock.elapsedRealtime())))
        send(JourneyEvent.Candidate("서울 귀가", System.currentTimeMillis(), DataSource.NAVIGATION))
        send(JourneyEvent.Arrived(evidence(other, other.address, "서울 귀가"))); barrier()
        assertTrue(RadioService.status.value.playing)
    }
    @Test fun missingAndStaleRouteCannotBorrowTargetIdentity() {
        bind(); playSample()
        val now = SystemClock.elapsedRealtime()
        send(JourneyEvent.Destination(null))
        send(JourneyEvent.Arrived(ArrivalEvidence("서울 귀가", home.address, null, now))); barrier()
        assertTrue(RadioService.status.value.playing)
        send(JourneyEvent.Arrived(ArrivalEvidence("서울 귀가", home.address, RouteObservation(home, now - 45_001), now))); barrier()
        assertTrue(RadioService.status.value.playing)
    }
    @Test fun endedVisitRecordingStillDetectsBoundReturnWithoutAddingVisits() {
        bind()
        runBlocking { model.endTrip().join() }
        waitFor("Visit recording ended") { model.state.value.trip?.status == TripStatus.ENDED }
        val before = runBlocking { app.database.records().read("visit").size }
        playSample()
        send(JourneyEvent.Candidate("기록 종료 후 장소", System.currentTimeMillis(), DataSource.NAVIGATION))
        send(JourneyEvent.Arrived(evidence())); waitFor("Read-only arrival monitor stops") { RadioService.status.value.episodeId == null }
        assertEquals(before, runBlocking { app.database.records().read("visit").size })
    }
    @Test fun bindingAfterRecordingEndedRestartsReadOnlyArrivalMonitoring() {
        runBlocking { model.endTrip().join() }
        waitFor("Recording ended before binding") { model.state.value.trip?.status == TripStatus.ENDED }
        bind(); playSample()
        send(JourneyEvent.Candidate("방문 저장 금지", System.currentTimeMillis(), DataSource.NAVIGATION))
        send(JourneyEvent.Arrived(evidence()))
        waitFor("New read-only connection receives arrival") { RadioService.status.value.episodeId == null }
        assertTrue(runBlocking { app.database.records().read("visit").isEmpty() })
    }
    @Test fun uiBindingPersistsIdentityAndLegacyNameDoesNotBecomeIdentity() {
        onView(withText("여행 기록")).perform(scrollTo(), click())
        onView(withText("현재 내비 목적지를 귀가 목적지로 지정")).perform(scrollTo(), click())
        onView(withText("확인")).perform(click())
        waitFor("UI target persisted") { model.state.value.trip?.returnTarget == home }
        val stored = runBlocking { app.database.records().read("trip").single() }
        assertEquals(home, Codec.trip(stored).returnTarget)
        val oldJson = JSONObject(stored.json).apply { remove("returnTarget") }
        assertNull(Codec.trip(Record("trip", stored.id, oldJson.toString())).returnTarget)
        runBlocking { model.returnDestination("서울 귀가").join() }
        assertEquals(home, runBlocking { Codec.trip(app.database.records().read("trip").single()).returnTarget })
        runBlocking { model.clearReturnTarget().join() }
        assertNull(runBlocking { Codec.trip(app.database.records().read("trip").single()).returnTarget })
        playSample(); send(JourneyEvent.Arrived(evidence())); barrier()
        assertTrue(RadioService.status.value.playing)
    }
}
