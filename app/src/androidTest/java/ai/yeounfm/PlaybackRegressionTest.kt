package ai.yeounfm

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.yeounfm.audio.AudioRenderer
import ai.yeounfm.audio.RadioService
import ai.yeounfm.core.*
import ai.yeounfm.data.Codec
import ai.yeounfm.ui.MainActivity
import ai.yeounfm.ui.YeounFmViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Fixed fixture audio tests timing/control only; it does not stand in for generated Korean TTS. */
@RunWith(AndroidJUnit4::class)
class PlaybackRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as YeounFmApp
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var browser: MediaBrowser
    private lateinit var controller: MediaController
    private lateinit var originalFactory: (android.content.Context) -> AudioRenderer
    private lateinit var fixture: File
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("$label: ${RadioService.status.value}", condition())
    }
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            .executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        originalFactory = app.audioRendererFactory
        main { app.startService(RadioService.commandIntent(app, RadioService.STOP)) }
        app.rideTime.clear()
        runBlocking { app.settings.consent(true, true); app.settings.voice(true); app.database.records().clearAll() }
        fixture = File(app.cacheDir, "regression-fixture.wav")
        app.assets.open("fixed-demo.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val latch = CountDownLatch(1)
        main {
            browser = MediaBrowser(app, ComponentName(app, RadioService::class.java), object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() { latch.countDown() }
            }, null)
            browser.connect()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        controller = MediaController(app, browser.sessionToken)
    }
    @After fun cleanup() {
        releases.forEach { it.complete(Unit) }
        main { app.startService(RadioService.commandIntent(app, RadioService.STOP)); browser.disconnect(); app.audioRendererFactory = originalFactory }
        app.rideTime.clear()
        scenario.close()
    }
    private fun episode(path: String? = null) = Episode(UUID.randomUUID().toString(), "fixture-trip", "회귀 테스트 · 고정 오디오 fixture", 0, 60,
        listOf(ScriptLine("테스트", "회귀 테스트용 고정 오디오입니다.", setOf("fixture:audio"))), DataSource.DEMO, audioPath = path)
    private fun prepare(e: Episode) = main { app.startForegroundService(RadioService.commandIntent(app, RadioService.PREPARE).putExtra("episode", Codec.episode(e).json)) }
    private class HeldRender(val entered: CountDownLatch, val release: CompletableDeferred<Unit>, val returned: CountDownLatch)
    private fun holdRenderer(): HeldRender {
        val held = HeldRender(CountDownLatch(1), CompletableDeferred<Unit>(), CountDownLatch(1))
        releases += held.release
        main { app.audioRendererFactory = { AudioRenderer { _, destination ->
            // Deliberately late completion also exercises generation invalidation after stop/replacement.
            withContext(NonCancellable) {
                held.entered.countDown(); held.release.await()
                destination.parentFile!!.mkdirs(); fixture.copyTo(destination, true)
                held.returned.countDown(); destination
            }
        } } }
        return held
    }
    @Test fun pauseDuringSynthesisLeavesReadyAudioPausedUntilExplicitPlay() {
        val held = holdRenderer(); val e = episode(); prepare(e)
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        controller.transportControls.pause()
        waitFor("Pause intent retained") { !RadioService.status.value.playWhenReady }
        waitFor("Host also shows paused") { controller.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED }
        held.release.complete(Unit)
        waitFor("Ready, not playing") { RadioService.status.value.audioPath != null && !RadioService.status.value.preparing }
        Thread.sleep(300); assertFalse(RadioService.status.value.playing)
        controller.transportControls.play(); waitFor("Explicit play starts") { RadioService.status.value.playing }
    }
    @Test fun screenButtonCanCancelAutoplayWhileSynthesisIsPending() {
        val held = holdRenderer()
        val trip = TripSession("ui-pause-trip", 0)
        runBlocking {
            app.database.records().put(Codec.trip(trip))
            app.database.records().put(Codec.visit(VisitCandidate("ui-visit", trip.id, "시연 장소", 0, DataSource.DEMO, Verification.CONFIRMED)))
            app.database.records().put(Codec.pick(MemoryPick("ui-pick", trip.id, "참여자", "ui-visit", "재미있었어요")))
        }
        waitFor("Screen input ready") {
            var ready = false
            scenario.onActivity { ready = ViewModelProvider(it)[YeounFmViewModel::class.java].state.value.completedNames.size == 1 }
            ready
        }
        scenario.onActivity { ViewModelProvider(it)[YeounFmViewModel::class.java].generate(60) }
        waitFor("Screen episode ready") {
            var ready = false
            scenario.onActivity { ready = ViewModelProvider(it)[YeounFmViewModel::class.java].state.value.episode != null }
            ready
        }
        onView(withText("라디오")).perform(scrollTo(), click())
        onView(withText("▶  방송 재생")).perform(scrollTo(), click())
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        onView(withText("Ⅱ  준비 완료 후 재생 취소")).perform(scrollTo(), click())
        waitFor("Screen pause retained") { !RadioService.status.value.playWhenReady }
        held.release.complete(Unit)
        waitFor("Prepared while paused") { RadioService.status.value.audioPath != null && !RadioService.status.value.preparing }
        assertFalse(RadioService.status.value.playing)
        onView(withText("▶  방송 재생")).perform(scrollTo(), click())
        waitFor("Screen resumes explicitly") { RadioService.status.value.playing }
    }
    @Test fun pauseThenPlayDuringPreparationUsesLastIntent() {
        val held = holdRenderer(); prepare(episode())
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        controller.transportControls.pause(); waitFor("Paused") { !RadioService.status.value.playWhenReady }
        controller.transportControls.play(); waitFor("Play intent queued") { RadioService.status.value.playWhenReady }
        held.release.complete(Unit)
        waitFor("Starts after readiness") { RadioService.status.value.playing }
    }
    @Test fun stoppedLibraryPreparationCannotResurrectAfterLateCompletion() {
        val held = holdRenderer(); val e = episode().copy(saved = true)
        runBlocking { app.database.records().put(Codec.episode(e)) }
        controller.transportControls.playFromMediaId(e.id, null)
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        controller.transportControls.stop(); waitFor("Stopped") { RadioService.status.value.episodeId == null }
        held.release.complete(Unit); assertTrue(held.returned.await(10, TimeUnit.SECONDS))
        Thread.sleep(300); assertNull(RadioService.status.value.episodeId); assertFalse(RadioService.status.value.playing)
    }
    @Test fun lateOldPreparationCannotReplaceNewEpisode() {
        val held = holdRenderer(); prepare(episode())
        assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        val newer = episode(fixture.absolutePath); prepare(newer)
        waitFor("New episode starts") { RadioService.status.value.episodeId == newer.id && RadioService.status.value.playing }
        held.release.complete(Unit); assertTrue(held.returned.await(10, TimeUnit.SECONDS))
        Thread.sleep(300); assertEquals(newer.id, RadioService.status.value.episodeId); assertTrue(RadioService.status.value.playing)
    }
    @Test fun synthesisElapsedTimeCanMakeAudioTooLongToStart() {
        val held = holdRenderer()
        app.rideTime.begin("ride"); app.rideTime.update("ride", 46)
        assertTrue(app.rideTime.fits(44_000))
        prepare(episode()); assertTrue(held.entered.await(10, TimeUnit.SECONDS))
        Thread.sleep(3100); held.release.complete(Unit)
        waitFor("Latest budget blocks audio") { RadioService.status.value.error.isNotBlank() }
        assertFalse(RadioService.status.value.playing); assertTrue(app.rideTime.remainingSeconds()!! < 44)
    }
    @Test fun vehicleLibraryDoesNotClearKnownRideLimit() {
        val e = episode(fixture.absolutePath).copy(saved = true)
        runBlocking { app.database.records().put(Codec.episode(e)) }
        app.rideTime.begin("ride"); app.rideTime.update("ride", 1)
        controller.transportControls.playFromMediaId(e.id, null)
        waitFor("Library budget enforced") { RadioService.status.value.episodeId == e.id && RadioService.status.value.error.isNotBlank() }
        assertFalse(RadioService.status.value.playing); assertNotNull(app.rideTime.remainingMillis())
    }
    @Test fun latestEtaAppliesToResumeAndFullReplay() {
        app.rideTime.begin("ride"); app.rideTime.update("ride", 120)
        prepare(episode(fixture.absolutePath)); waitFor("Started") { RadioService.status.value.playing }
        controller.transportControls.pause(); waitFor("Paused") { !RadioService.status.value.playing }
        app.rideTime.update("ride", 1)
        controller.transportControls.play(); waitFor("Resume blocked") { RadioService.status.value.error.isNotBlank() }
        assertFalse(RadioService.status.value.playing)
        app.rideTime.update("ride", 20)
        controller.transportControls.seekTo(30_000); waitFor("Seek applied") { RadioService.status.value.position >= 30_000 }
        controller.transportControls.play(); waitFor("Remaining audio fits") { RadioService.status.value.playing }
        controller.transportControls.skipToPrevious(); waitFor("Full replay blocked") { !RadioService.status.value.playing && RadioService.status.value.error.isNotBlank() }
    }
}
