package ai.yeounfm

import android.app.Application
import androidx.room.Room
import ai.yeounfm.data.YeounFmDatabase
import ai.yeounfm.data.Settings
import ai.yeounfm.vehicle.SafetyMonitor

class YeounFmApp : Application() {
    val rideTime = ai.yeounfm.core.RideTimeBudget(android.os.SystemClock::elapsedRealtime)
    // An in-process dependency seam lets instrumentation hold synthesis without a real TTS engine.
    internal var audioRendererFactory: (android.content.Context) -> ai.yeounfm.audio.AudioRenderer = { ai.yeounfm.audio.SpeechRenderer(it) }
    internal var journeyProviderFactory: (Boolean) -> ai.yeounfm.vehicle.JourneyProvider = { demo ->
        if (demo) ai.yeounfm.vehicle.DemoJourneyProvider() else ai.yeounfm.vehicle.PlatformJourneyProvider(this)
    }
    val dataMutex = kotlinx.coroutines.sync.Mutex()
    override fun onCreate() {
        super.onCreate()
        java.io.File(cacheDir, "speech").listFiles()?.forEach { it.delete() }
    }
    val database by lazy { Room.databaseBuilder(this, YeounFmDatabase::class.java, "yeounfm.db").build() }
    val settings by lazy { Settings(this) }
    val safety by lazy { SafetyMonitor(this) }
}
