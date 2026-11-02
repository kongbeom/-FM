package ai.yeounfm.core

enum class DataSource { DEMO, NAVIGATION, RECENT_DESTINATION, USER }
enum class Verification { CONFIRMED, UNVERIFIED, USER_ADDED, REJECTED }
enum class TripStatus { RECORDING, ENDED }
data class TripSession(
    val id: String, val startTime: Long, val endTime: Long? = null,
    val returnDestination: String = "서울 귀가", val status: TripStatus = TripStatus.RECORDING,
    val dataSource: DataSource = DataSource.DEMO, val consentStatus: Boolean = true,
    val returnTarget: DestinationIdentity? = null
)
data class VisitCandidate(
    val id: String, val tripId: String, val placeName: String, val arrivalTime: Long,
    val source: DataSource, val verification: Verification = Verification.UNVERIFIED,
    val sequence: Int = 0, val eventKey: String = ""
) { val usable: Boolean get() = verification == Verification.CONFIRMED || verification == Verification.USER_ADDED }
data class MemoryPick(
    val id: String, val tripId: String, val nickname: String, val visitId: String,
    val emotion: String, val note: String = "", val elapsedMs: Long = 0
)
data class ScriptLine(val section: String, val text: String, val evidence: Set<String>)
data class Episode(
    val id: String, val tripId: String, val title: String, val createdAt: Long,
    val targetSeconds: Int, val lines: List<ScriptLine>, val source: DataSource,
    val saved: Boolean = false, val audioPath: String? = null
) { val script get() = lines.joinToString("\n\n") { it.text } }
data class EpisodeInput(
    val trip: TripSession, val visits: List<VisitCandidate>, val picks: List<MemoryPick>,
    val requestedSeconds: Int = 60, val remainingSeconds: Int? = null
)

object Emotions {
    val choices = listOf("재미있었어요", "감동적이었어요", "아름다웠어요", "신기했어요", "맛있었어요", "조금 무서웠지만 좋았어요", "아쉬웠어요")
}

object JourneyRules {
    fun add(existing: List<VisitCandidate>, candidate: VisitCandidate): List<VisitCandidate> {
        require(candidate.placeName.isNotBlank())
        // Retry identity plus a small arrival window; revisits later in the day remain distinct.
        val duplicate = existing.any {
            it.tripId == candidate.tripId && (
                (candidate.eventKey.isNotBlank() && it.eventKey == candidate.eventKey) ||
                (it.placeName.trim() == candidate.placeName.trim() && it.source == candidate.source &&
                    kotlin.math.abs(it.arrivalTime - candidate.arrivalTime) < 120_000))
        }
        return if (duplicate) existing else existing + candidate.copy(sequence = existing.size)
    }
}
