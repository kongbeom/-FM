package ai.backfm

import android.app.Application
import androidx.room.Room
import ai.backfm.data.BackfmDatabase
import ai.backfm.data.Settings
import ai.backfm.vehicle.SafetyMonitor

class BackfmApp : Application() {
    val rideTime = ai.backfm.core.RideTimeBudget(android.os.SystemClock::elapsedRealtime)
    // An in-process dependency seam lets instrumentation hold synthesis without a real TTS engine.
    internal var audioRendererFactory: (android.content.Context) -> ai.backfm.audio.AudioRenderer = { ai.backfm.audio.SpeechRenderer(it) }
    internal var journeyProviderFactory: (Boolean) -> ai.backfm.vehicle.JourneyProvider = { demo ->
        if (demo) ai.backfm.vehicle.DemoJourneyProvider() else ai.backfm.vehicle.PlatformJourneyProvider(this)
    }
    val dataMutex = kotlinx.coroutines.sync.Mutex()
    override fun onCreate() {
        super.onCreate()
        java.io.File(cacheDir, "speech").listFiles()?.forEach { it.delete() }
    }
    val database by lazy { Room.databaseBuilder(this, BackfmDatabase::class.java, "backfm.db").build() }
    val settings by lazy { Settings(this) }
    val safety by lazy { SafetyMonitor(this) }
}
