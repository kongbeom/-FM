package ai.yeounfm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.yeounfm.vehicle.PlatformJourneyProvider
import ai.yeounfm.vehicle.JourneyEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a public Android image without Pleos navigation services. */
@RunWith(AndroidJUnit4::class)
class NaviAvailabilityTest {
    @Test fun missingPleosServiceFailsGracefully() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = withTimeout(15_000) { PlatformJourneyProvider(context).events().first { it is JourneyEvent.Status } }
        val message = (event as JourneyEvent.Status).message
        println("Navi absence result: $message")
        assertTrue(message.contains("실패") || message.contains("시간 초과"))
    }
}
