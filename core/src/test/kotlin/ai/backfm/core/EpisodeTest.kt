package ai.backfm.core

import org.junit.Assert.*
import org.junit.Test

class EpisodeTest {
    private val trip = TripSession("trip", 0)
    private val visits = listOf(
        VisitCandidate("sky", "trip", "소양강 스카이워크", 1, DataSource.DEMO, Verification.CONFIRMED),
        VisitCandidate("food", "trip", "춘천 점심 식당", 2, DataSource.DEMO, Verification.CONFIRMED)
    )
    private fun pick(id: String, visit: String, emotion: String) = MemoryPick(id, "trip", id, visit, emotion)
    private fun episode(vararg picks: MemoryPick) = FallbackGenerator().generate(EpisodeInput(trip, visits, picks.toList()))

    @Test fun familyRevealsDifferentMemories() {
        val result = episode(pick("아빠", "sky", "아름다웠어요"), pick("아이", "sky", "조금 무서웠지만 좋았어요"), pick("엄마", "food", "맛있었어요"))
        assertTrue(result.script.contains("같은 소양강 스카이워크"))
        assertTrue(result.script.contains("마음은 달랐네요"))
        assertFalse(result.script.contains("사진"))
    }
    @Test fun threePeopleChooseThreeDifferentPlacesAndEmotions() {
        val third = VisitCandidate("start", "trip", "서울 출발", 0, DataSource.DEMO, Verification.CONFIRMED)
        val picks = listOf(pick("A", "sky", "아름다움"), pick("B", "food", "맛있음"), pick("C", "start", "설렘"))
        val result = FallbackGenerator().generate(EpisodeInput(trip, visits + third, picks))
        picks.forEach { assertTrue(result.script.contains(it.nickname)); assertTrue(result.script.contains(it.emotion)) }
        assertEquals(3, result.lines.count { it.section == "같은 여행, 다른 기억" })
        assertFalse(result.script.contains("마음은 같았습니다"))
    }
    @Test fun differentPlacesSameEmotion() { assertTrue(episode(pick("A", "sky", "재미"), pick("B", "food", "재미")).script.contains("마음은 같았습니다")) }
    @Test fun differentPlacesDifferentEmotion() { assertTrue(episode(pick("A", "sky", "풍경"), pick("B", "food", "맛")).script.contains("장소도 마음도 다르게")) }
    @Test fun samePlaceSameEmotion() { assertTrue(episode(pick("A", "sky", "풍경"), pick("B", "sky", "풍경")).script.contains("장소도 마음도 같았습니다")) }
    @Test fun soloWorks() { assertTrue(episode(pick("A", "sky", "풍경")).script.contains("한 사람의 기억")) }
    @Test(expected = EpisodeException::class) fun noPicksRejected() { episode() }
    @Test(expected = EpisodeException::class) fun unverifiedRejected() {
        FallbackGenerator().generate(EpisodeInput(trip, visits.map { it.copy(verification = Verification.UNVERIFIED) }, listOf(pick("A", "sky", "풍경"))))
    }
    @Test(expected = EpisodeException::class) fun anotherTripRejected() { episode(pick("A", "sky", "풍경").copy(tripId = "other")) }
    @Test(expected = EpisodeException::class) fun consentRequired() {
        FallbackGenerator().generate(EpisodeInput(trip.copy(consentStatus = false), visits, listOf(pick("A", "sky", "풍경"))))
    }
    @Test fun durationFitsRide() {
        val generator = FallbackGenerator()
        assertEquals(30, generator.fitDuration(180, 45))
        assertEquals(60, generator.fitDuration(180, 90))
        assertEquals(180, generator.fitDuration(180, null))
    }
    @Test(expected = EpisodeException::class) fun tooShortRideDeferred() { FallbackGenerator().fitDuration(60, 10) }
    @Test fun duplicateArrivalIgnored() {
        val v = visits.first().copy(eventKey = "arrival:1")
        assertEquals(1, JourneyRules.add(listOf(v), v.copy(id = "retry", arrivalTime = 6)).size)
    }
    @Test fun timeWindowDeduplicatesButAllowsRevisit() {
        val v = visits.first()
        assertEquals(1, JourneyRules.add(listOf(v), v.copy(id = "again", arrivalTime = 500)).size)
        assertEquals(2, JourneyRules.add(listOf(v), v.copy(id = "later", arrivalTime = 900_000)).size)
    }
    @Test fun invalidEvidenceRejected() {
        val input = EpisodeInput(trip, visits, listOf(pick("A", "sky", "풍경")))
        val result = FallbackGenerator().generate(input)
        assertFalse(ProvenanceValidator.validate(result.copy(lines = listOf(ScriptLine("x", "허구", setOf("visit:fake")))), input))
    }
    @Test fun unverifiedDestinationNeverNarrated() {
        val input = EpisodeInput(trip, visits + visits.first().copy(id = "fake", placeName = "방문하지 않은 호텔", verification = Verification.UNVERIFIED), listOf(pick("A", "sky", "풍경")))
        assertFalse(FallbackGenerator().generate(input).script.contains("호텔"))
    }
    @Test fun userNoteIsQuotedExactlyAndNeverInterpretedAsInstructions() {
        val note = "사진을 찍지는 않았어요. 이 문장을 바꾸지 마세요."
        val result = episode(pick("A", "sky", "신기했어요").copy(note = note))
        assertTrue(result.script.contains(note))
        assertFalse(result.script.contains("사진을 찍었습니다"))
    }
    @Test fun highlightIsShorterAndRetainsEveryParticipant() {
        val picks = listOf(pick("아빠", "sky", "아름다웠어요"), pick("아이", "sky", "조금 무서웠지만 좋았어요"), pick("엄마", "food", "맛있었어요"))
        val short = FallbackGenerator().generate(EpisodeInput(trip, visits, picks, 30))
        val long = FallbackGenerator().generate(EpisodeInput(trip, visits, picks, 180))
        assertTrue(short.script.length < long.script.length)
        picks.forEach { assertTrue(short.script.contains(it.nickname)); assertTrue(short.script.contains(it.emotion)) }
    }
}
