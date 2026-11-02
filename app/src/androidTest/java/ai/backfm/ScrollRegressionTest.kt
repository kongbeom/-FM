package ai.backfm

import android.widget.ScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.uiautomator.UiDevice
import ai.backfm.audio.RadioService
import ai.backfm.core.*
import ai.backfm.data.Codec
import ai.backfm.ui.BackfmViewModel
import ai.backfm.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as BackfmApp
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var model: BackfmViewModel
    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(40)
        assertTrue(label, condition())
    }
    @Before fun setup() {
        device.executeShellCommand("settings put global window_animation_scale 0")
        device.executeShellCommand("settings put global transition_animation_scale 0")
        device.executeShellCommand("settings put global animator_duration_scale 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync { app.startService(RadioService.commandIntent(app, RadioService.STOP)); app.safety.simulateDriving(false) }
        app.rideTime.clear()
        runBlocking {
            app.settings.consent(true, true); app.settings.demo(true)
            app.database.records().clearAll()
            val trip = TripSession("scroll-trip", 0, status = TripStatus.ENDED)
            app.database.records().put(Codec.trip(trip))
            app.database.records().put(Codec.visit(VisitCandidate("scroll-visit", trip.id, "스크롤 확인 장소", 0, DataSource.DEMO, Verification.CONFIRMED)))
            app.database.records().put(Codec.pick(MemoryPick("scroll-pick", trip.id, "참여자", "scroll-visit", "재미있었어요")))
        }
        scenario.onActivity { model = ViewModelProvider(it)[BackfmViewModel::class.java] }
        waitFor("Fixture loaded") { model.state.value.completedNames.size == 1 && model.state.value.settings.consent }
    }
    @After fun cleanup() {
        scenario.close()
        device.executeShellCommand("wm size reset")
    }
    private fun tap(text: String) { onView(withText(text)).perform(scrollTo(), click()) }
    private fun scrollY(id: Int = R.id.backfm_content_scroll): Int {
        var y = -1
        scenario.onActivity { y = it.findViewById<ScrollView>(id).scrollY }
        return y
    }
    private fun assertStablePosition(expected: Int, id: Int = R.id.backfm_content_scroll) {
        waitFor("Scroll restored") { kotlin.math.abs(scrollY(id) - expected) <= 2 }
        instrumentation.waitForIdleSync(); Thread.sleep(200)
        assertEquals("Selection should keep the viewport", expected.toLong(), scrollY(id).toLong())
    }
    private fun openEmotionStep() {
        tap("기억 선택"); tap("내 차례 시작"); tap("아빠"); tap("스크롤 확인 장소")
        onView(withText("아쉬웠어요")).perform(scrollTo())
    }
    @Test fun emotionChoiceAndAsyncMessageKeepCurrentPosition() {
        openEmotionStep()
        val before = scrollY(); assertTrue(before > 0)
        onView(withText("아쉬웠어요")).perform(click())
        assertStablePosition(before)
        model.message("스크롤 상태 갱신 확인")
        waitFor("Message handled") { model.state.value.message.isBlank() }
        assertStablePosition(before)
    }
    @Test fun durationChoiceAndGenerationKeepBodyAndMenuPosition() {
        tap("방송 준비")
        onView(withId(R.id.backfm_content_scroll)).perform(swipeUp())
        onView(withText("약 3분 · 확장 대화형")).perform(scrollTo())
        val before = scrollY(); val menuBefore = scrollY(R.id.backfm_menu_scroll)
        assertTrue(before > 0)
        onView(withText("약 3분 · 확장 대화형")).perform(click())
        assertStablePosition(before); assertStablePosition(menuBefore, R.id.backfm_menu_scroll)
        onView(withText("봉인된 기억으로 방송 만들기")).check(doesNotExist())
        onView(withText("저장된 기억으로 방송 만들기")).perform(scrollTo())
        val generateY = scrollY()
        onView(withText("저장된 기억으로 방송 만들기")).perform(click())
        waitFor("Episode generated") { model.state.value.episode != null && !model.state.value.busy }
        assertStablePosition(generateY)
    }
    @Test fun participantHandoffStartsAtTopWithoutPreviousAnswer() {
        openEmotionStep(); onView(withText("아쉬웠어요")).perform(click())
        onView(withText("이 마음으로 선택 완료")).perform(scrollTo())
        assertTrue(scrollY() > 0)
        onView(withText("이 마음으로 선택 완료")).perform(click())
        waitFor("Pick stored") { model.state.value.completedNames.size == 2 }
        assertStablePosition(0)
        onView(withText("아쉬웠어요")).check(doesNotExist())
        onView(withText("스크롤 확인 장소")).check(doesNotExist())
        tap("내 차례 시작")
        assertStablePosition(0)
    }
}
