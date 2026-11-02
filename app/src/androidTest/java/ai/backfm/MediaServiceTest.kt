package ai.backfm

import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.backfm.audio.RadioService
import ai.backfm.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaServiceTest {
    @Test fun mediaBrowserControlsAndRealAudioFocusTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as BackfmApp
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        app.rideTime.clear()
        var browser: MediaBrowser? = null
        fun waitFor(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(80)
            assertTrue(message, condition())
        }
        try {
            runBlocking { app.settings.consent(true, true); app.settings.voice(true) }
            val connected = CountDownLatch(1)
            instrumentation.runOnMainSync {
                browser = MediaBrowser(app, ComponentName(app, RadioService::class.java), object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.countDown() }
                }, null)
                browser!!.connect()
            }
            assertTrue("MBS connects", connected.await(10, TimeUnit.SECONDS))
            val childrenReady = CountDownLatch(1)
            var podcastFolder = false
            instrumentation.runOnMainSync { browser!!.subscribe("podcasts", object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                    podcastFolder = children.any { it.mediaId == "episodes" && it.isBrowsable }; childrenReady.countDown()
                }
            }) }
            assertTrue(childrenReady.await(10, TimeUnit.SECONDS)); assertTrue(podcastFolder)
            instrumentation.runOnMainSync { app.startForegroundService(RadioService.commandIntent(app, RadioService.SAMPLE)) }
            waitFor("Fixed sample starts") { RadioService.status.value.playing }
            val controller = MediaController(app, browser!!.sessionToken)
            controller.transportControls.pause()
            waitFor("MediaSession pause") { !RadioService.status.value.playing }
            controller.transportControls.play()
            waitFor("MediaSession play") { RadioService.status.value.playing }
            val manager = app.getSystemService(AudioManager::class.java)
            fun competingFocus(gain: Int) = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ }, Handler(Looper.getMainLooper())).build()
            val transient = competingFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(transient))
            waitFor("Real transient focus pauses speech") { !RadioService.status.value.playing }
            manager.abandonAudioFocusRequest(transient)
            waitFor("Real focus regain resumes speech") { RadioService.status.value.playing }
            val permanent = competingFocus(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(permanent))
            waitFor("Permanent loss pauses") { !RadioService.status.value.playing }
            manager.abandonAudioFocusRequest(permanent)
            Thread.sleep(800)
            assertFalse("Permanent loss does not auto-resume", RadioService.status.value.playing)
        } finally {
            instrumentation.runOnMainSync { browser?.disconnect(); app.startService(RadioService.commandIntent(app, RadioService.STOP)) }
            scenario.close()
        }
    }
}
