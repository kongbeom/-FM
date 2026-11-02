package ai.backfm

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.*
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.assertion.ViewAssertions
import ai.backfm.audio.RadioService
import ai.backfm.core.*
import ai.backfm.data.Codec
import ai.backfm.ui.MainActivity
import ai.backfm.ui.BackfmViewModel
import ai.backfm.ui.UiState
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackfmFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as BackfmApp
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        app.rideTime.clear()
        instrumentation.runOnMainSync { app.startService(RadioService.commandIntent(app, RadioService.STOP)); app.safety.simulateDriving(false) }
        runBlocking { app.settings.consent(false); app.settings.demo(true); app.settings.voice(true); app.database.records().clearAll() }
        device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        device.waitForIdle()
    }
    @After fun teardown() { scenario.close() }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline && !predicate()) Thread.sleep(100)
        assertTrue("$message; playback=${RadioService.status.value}", predicate())
    }
    private fun click(label: String) {
        val view = onView(ViewMatchers.withText(label))
        try { view.check(ViewAssertions.matches(ViewMatchers.isDisplayed())) }
        catch (_: AssertionError) { view.perform(ViewActions.scrollTo()) }
        view.perform(ViewActions.click())
    }
    private fun state(): UiState { var result: UiState? = null; scenario.onActivity { result = ViewModelProvider(it)[BackfmViewModel::class.java].state.value }; return result!! }
    @Test fun generationButtonCreatesEpisodeFromPersistedPicks() {
        runBlocking {
            app.settings.consent(true, true)
            val trip = TripSession("probe", 0, dataSource = DataSource.DEMO)
            app.database.records().put(Codec.trip(trip))
            app.database.records().put(Codec.visit(VisitCandidate("sky", "probe", "소양강 스카이워크", 0, DataSource.DEMO, Verification.CONFIRMED)))
            app.database.records().put(Codec.pick(MemoryPick("dad", "probe", "아빠", "sky", "아름다웠어요")))
        }
        waitFor("Probe input ready") { state().completedNames.size == 1 && state().settings.consent }
        click("방송 준비"); click("저장된 기억으로 방송 만들기")
        waitFor("Probe generated") { state().episode != null }
    }
    @Test fun fixedSampleButtonsPauseAndResume() {
        runBlocking { app.settings.consent(true, true) }
        waitFor("Consent ready") { state().settings.consent }
        click("라디오"); click("고정 시연 샘플 듣기 · 현재 답변 미반영")
        waitFor("Sample starts") { RadioService.status.value.playing }
        click("샘플 일시정지")
        waitFor("Sample pauses") { !RadioService.status.value.playing }
        click("샘플 이어 듣기")
        waitFor("Sample resumes") { RadioService.status.value.playing }
    }
    private fun agreeAndStart() {
        click("설정")
        click("위 기록·방송 활용에 동의합니다")
        click("모든 참여자가 공개에 동의하며, 아동 참여 시 보호자가 동의합니다")
        click("동의하고 시작")
        waitFor("Consent persisted") { runBlocking { app.database.records().snapshot() }.isEmpty() && !device.hasObject(By.text("동의하고 시작")) }
        click("춘천 DEMO 여정 불러오기")
        waitFor("Demo journey loaded") { runBlocking { app.database.records().read("visit").size } == 3 }
        click("여행 기록")
        state().visits.forEach { visit ->
            onView(allOf(ViewMatchers.withText("실제로 방문했어요"), ViewMatchers.withParent(ViewMatchers.hasDescendant(ViewMatchers.withText("${visit.sequence + 1}.  ${visit.placeName}")))))
                .perform(ViewActions.scrollTo(), ViewActions.click())
            waitFor("Visit confirmed") { state().visits.first { it.id == visit.id }.usable }
        }
        waitFor("Candidates confirmed") { runBlocking { app.database.records().read("visit").map(Codec::visit).all { it.usable } } }
    }
    private fun pick(name: String, place: String, emotion: String, count: Int) {
        click("기억 선택"); click("내 차례 시작"); click(name); click(place); click(emotion); click("이 마음으로 선택 완료")
        waitFor("Memory sealed") { runBlocking { app.database.records().read("pick").size } == count }
        assertFalse("No prior emotion on handoff", device.hasObject(By.text(emotion)))
        assertFalse("No prior place on handoff", device.hasObject(By.text(place)))
    }
    @Test fun familyJourneyRevealPlaybackSaveRestoreAndDelete() {
        agreeAndStart()
        pick("아빠", "소양강 스카이워크", "아름다웠어요", 1)
        pick("아이", "소양강 스카이워크", "조금 무서웠지만 좋았어요", 2)
        pick("엄마", "춘천 점심 식당", "맛있었어요", 3)
        click("방송 준비"); click("저장된 기억으로 방송 만들기")
        waitFor("Generation finished") { state().episode != null }
        click("완성된 방송으로 이동")
        click("대본 펼치기 · 선택이 공개됩니다")
        assertTrue(state().episode!!.script.contains("마음은 달랐네요"))
        // No Korean engine on the public AOSP emulator: require an explicit failure, never fake playback.
        click("▶  방송 재생")
        waitFor("TTS produced audio or an explicit failure") { !RadioService.status.value.preparing && (RadioService.status.value.error.isNotBlank() || RadioService.status.value.audioPath != null) }
        click("이 방송을 내 기기에 보관"); click("확인")
        waitFor("Explicitly saved") { runBlocking { app.database.records().read("episode").size } == 1 }
        click("고정 시연 샘플 듣기 · 현재 답변 미반영")
        waitFor("Fixed sample playing") { RadioService.status.value.episodeId == "fixed-sample" && RadioService.status.value.playing }
        assertTrue(RadioService.status.value.duration > 10_000)
        device.pressHome()
        val before = RadioService.status.value.position
        waitFor("Background playback advances") { RadioService.status.value.position > before + 600 }
        device.executeShellCommand("am start -n ${app.packageName}/ai.backfm.ui.MainActivity")
        click("라디오")
        click("샘플 일시정지")
        waitFor("Pause applied") { !RadioService.status.value.playing }
        click("샘플 이어 듣기")
        waitFor("Resume applied") { RadioService.status.value.playing }
        click("DEMO · 내비 안내 우선 시뮬레이션")
        waitFor("Navigation interrupted speech") { !RadioService.status.value.playing }
        waitFor("Navigation ended, resumed") { RadioService.status.value.playing }
        click("DEMO · 귀가 목적지 도착")
        waitFor("Arrival stops") { RadioService.status.value.episodeId == null }
        scenario.recreate()
        click("지난 방송"); click("방송 열기 / 다시 듣기")
        assertNotNull(device.wait(Until.findObject(By.text("우리의 귀갓길 · 3개의 기억")), 4000))
        click("지난 방송"); click("이 방송 삭제"); click("확인")
        waitFor("Episode removed") { runBlocking { app.database.records().read("episode").isEmpty() } }
        click("설정"); click("동의 철회 및 모든 데이터 삭제"); click("확인")
        waitFor("All data removed") { runBlocking { app.database.records().snapshot().isEmpty() } }
    }
    @Test fun drivingLocksInputsAndDoesNotExposePrivateChoices() {
        agreeAndStart()
        pick("아빠", "소양강 스카이워크", "아름다웠어요", 1)
        instrumentation.runOnMainSync { app.safety.simulateDriving(true) }
        assertNotNull(device.wait(Until.findObject(By.text("지금은 귀 기울일 시간")), 5000))
        assertFalse(device.hasObject(By.text("내 차례 시작")))
        click("DEMO · 정차 상태로 전환")
        assertNotNull(device.wait(Until.findObject(By.text("내 차례 시작")), 5000))
    }
    @Test fun roomCodecPersistsEvidenceAndNeverAutoConfirmsRecentDestination() = runBlocking {
        val visit = VisitCandidate("visit", "trip", "확인 안 된 호텔", 123, DataSource.RECENT_DESTINATION)
        app.database.records().put(Codec.visit(visit))
        val restored = Codec.visit(app.database.records().read("visit").single())
        assertEquals(visit, restored); assertFalse(restored.usable)
        val episode = Episode("episode", "trip", "검증", 123, 30, listOf(ScriptLine("공개", "직접 남긴 기억", setOf("pick:a", "visit:b"))), DataSource.DEMO, true)
        app.database.records().put(Codec.episode(episode))
        assertEquals(episode, Codec.episode(app.database.records().read("episode").single()))
    }
}
