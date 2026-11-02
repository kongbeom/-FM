package ai.yeounfm.core

/** One generation token covers settings, library lookup, synthesis and player preparation. */
class PlaybackRequestState {
    private var revision = 0L
    var wantsPlay = false
        private set
    fun begin(): Long { revision++; wantsPlay = true; return revision }
    fun pause() { wantsPlay = false }
    fun resume() { wantsPlay = true }
    fun stop() { revision++; wantsPlay = false }
    fun isCurrent(token: Long) = revision == token
    fun shouldPlay(token: Long) = isCurrent(token) && wantsPlay
}

/** A navigation observation is a countdown, not a reusable number of seconds. */
class RideTimeBudget(private val elapsedMillis: () -> Long) {
    private data class Observation(val tripId: String, val milliseconds: Long?, val at: Long)
    @Volatile private var observation: Observation? = null
    @Synchronized fun begin(tripId: String) {
        if (observation?.tripId != tripId) observation = Observation(tripId, null, elapsedMillis())
    }
    @Synchronized fun update(tripId: String, seconds: Int?) {
        if (observation?.tripId != tripId) return
        // A failed reading must not erase or restart a previously known countdown.
        if (seconds == null || seconds < 0) return
        observation = Observation(tripId, seconds.toLong() * 1000, elapsedMillis())
    }
    @Synchronized fun clear() { observation = null }
    fun remainingMillis(): Long? {
        val sample = observation ?: return null
        val remaining = sample.milliseconds ?: return null
        return (remaining - (elapsedMillis() - sample.at).coerceAtLeast(0)).coerceAtLeast(0)
    }
    fun remainingSeconds(): Int? = remainingMillis()?.div(1000)?.toInt()
    fun fits(durationMillis: Int, positionMillis: Int = 0): Boolean {
        val remaining = remainingMillis() ?: return true // No ETA is explicitly unknown, not invented.
        val content = (durationMillis.toLong() - positionMillis.coerceAtLeast(0)).coerceAtLeast(0)
        return content <= remaining
    }
}
