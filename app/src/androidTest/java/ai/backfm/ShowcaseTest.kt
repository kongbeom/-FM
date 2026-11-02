package ai.backfm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withText
import ai.backfm.core.*
import ai.backfm.data.Codec
import ai.backfm.ui.BackfmViewModel
import ai.backfm.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic fixture only; leaves an explicitly DEMO episode for screenshot and cold-start QA. */
@RunWith(AndroidJUnit4::class)
class ShowcaseTest {
    @Test fun renderDemoAndLeavePersistentFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as BackfmApp
        app.rideTime.clear()
        val trip = TripSession("showcase-trip", System.currentTimeMillis(), status = TripStatus.ENDED)
        val visits = listOf(
            VisitCandidate("showcase-food", trip.id, "춘천 점심 식당", trip.startTime, DataSource.DEMO, Verification.CONFIRMED),
            VisitCandidate("showcase-sky", trip.id, "소양강 스카이워크", trip.startTime, DataSource.DEMO, Verification.CONFIRMED, 1)
        )
        val picks = listOf(
            MemoryPick("showcase-dad", trip.id, "아빠", visits[1].id, "아름다웠어요"),
            MemoryPick("showcase-child", trip.id, "아이", visits[1].id, "조금 무서웠지만 좋았어요"),
            MemoryPick("showcase-mom", trip.id, "엄마", visits[0].id, "맛있었어요")
        )
        val episode = FallbackGenerator().generate(EpisodeInput(trip, visits, picks)).copy(saved = true)
        runBlocking {
            app.settings.consent(true, true); app.settings.demo(true); app.settings.voice(true)
            app.database.records().clearAll(); app.database.records().put(Codec.trip(trip))
            visits.forEach { app.database.records().put(Codec.visit(it)) }
            picks.forEach { app.database.records().put(Codec.pick(it)) }
            app.database.records().put(Codec.episode(episode))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val end = System.currentTimeMillis() + 10_000
            var ready = false
            while (!ready && System.currentTimeMillis() < end) {
                scenario.onActivity { ready = ViewModelProvider(it)[BackfmViewModel::class.java].state.value.saved.isNotEmpty() }
                Thread.sleep(80)
            }
            assertTrue(ready)
            fun capture(name: String) { scenario.onActivity { activity ->
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                val root = activity.findViewById<View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val file = File(app.getExternalFilesDir(null), "qa/$name.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                assertTrue(file.length() > 1000)
            } }
            capture("home")
            onView(withText("지난 방송")).perform(scrollTo(), click())
            onView(withText("방송 열기 / 다시 듣기")).perform(scrollTo(), click())
            val episodeEnd = System.currentTimeMillis() + 10_000
            var episodeReady = false
            while (!episodeReady && System.currentTimeMillis() < episodeEnd) {
                scenario.onActivity { episodeReady = ViewModelProvider(it)[BackfmViewModel::class.java].state.value.episode != null }
                Thread.sleep(80)
            }
            assertTrue(episodeReady)
            capture("player")
            onView(withText("대본 펼치기 · 선택이 공개됩니다")).perform(scrollTo(), click())
            onView(withText(episode.lines.first { it.section == "같은 여행, 다른 기억" }.text)).perform(scrollTo())
            capture("script")
        }
    }
}
