package ai.yeounfm.core

import java.util.UUID

class EpisodeException(message: String) : IllegalArgumentException(message)

/** No unconstrained model output is ever admitted to a factual sentence. */
class FallbackGenerator {
    fun generate(input: EpisodeInput, now: Long = System.currentTimeMillis()): Episode {
        if (!input.trip.consentStatus) throw EpisodeException("기록·방송 활용 동의가 필요합니다.")
        if (input.picks.isEmpty()) throw EpisodeException("한 명 이상의 기억을 먼저 선택해 주세요.")
        val visits = input.visits.filter { it.usable && it.tripId == input.trip.id }.associateBy { it.id }
        if (input.picks.any { it.tripId != input.trip.id || it.visitId !in visits || it.emotion.isBlank() || it.nickname.isBlank() })
            throw EpisodeException("확인된 방문 장소와 참여자의 기억을 다시 확인해 주세요.")
        if (input.picks.map { it.id }.distinct().size != input.picks.size) throw EpisodeException("중복된 참여 기록입니다.")
        val duration = fitDuration(input.requestedSeconds, input.remainingSeconds)
        val tripEvidence = setOf("trip:${input.trip.id}")
        val lines = mutableListOf(ScriptLine("오프닝", if (duration == 30) "같은 여행, 서로 다른 기억. 여운 에프엠, 지금 공개합니다." else "같은 여행, 서로 다른 기억. 여기는 여운 에프엠입니다. 오늘 여러분이 직접 고른 기억을 지금 공개합니다.", tripEvidence))
        val chosenVisits = input.picks.map { visits.getValue(it.visitId) }.distinctBy { it.id }
        lines += ScriptLine("오늘의 장소", "오늘의 기억에 등장하는 장소는 ${chosenVisits.joinToString(", ") { it.placeName }}입니다.", chosenVisits.map { "visit:${it.id}" }.toSet())
        input.picks.forEach { pick ->
            val visit = visits.getValue(pick.visitId)
            val note = if (pick.note.isNotBlank() && duration != 30) " 직접 남긴 한마디도 있습니다. ${pick.note}" else ""
            val reveal = if (duration == 30) "${pick.nickname} 님, ${visit.placeName}. ‘${pick.emotion}’." else "${pick.nickname} 님이 고른 곳은 ${visit.placeName}. 선택한 마음은 ‘${pick.emotion}’입니다.$note"
            lines += ScriptLine("기억 공개", reveal, setOf("visit:${visit.id}", "pick:${pick.id}"))
        }
        if (input.picks.size == 1) {
            lines += ScriptLine("나의 하이라이트", "오늘은 한 사람의 기억으로 만든 하이라이트입니다. 이 순간을 여러분의 속도로 간직해 보세요.", setOf("pick:${input.picks.single().id}"))
        } else {
            // Cover every pair once, with a bounded number of comparisons for short episodes.
            val pairs = input.picks.flatMapIndexed { index, left -> input.picks.drop(index + 1).map { left to it } }
                .sortedByDescending { (a, b) -> if (a.visitId == b.visitId && a.emotion != b.emotion) 1 else 0 }
                .take(if (duration == 30) 1 else if (duration == 60) 3 else 8)
            pairs.forEach { (a, b) ->
                val samePlace = a.visitId == b.visitId
                val sameEmotion = a.emotion == b.emotion
                val text = when {
                    samePlace && !sameEmotion -> "${a.nickname} 님과 ${b.nickname} 님은 같은 ${visits.getValue(a.visitId).placeName}를 골랐지만 마음은 달랐네요. ‘${a.emotion}’, 그리고 ‘${b.emotion}’. 같은 장소에도 서로 다른 기억이 남았습니다."
                    !samePlace && sameEmotion -> "${a.nickname} 님과 ${b.nickname} 님이 고른 장소는 달랐지만, ‘${a.emotion}’라는 마음은 같았습니다. 다른 장소에서 같은 마음을 발견했네요."
                    samePlace && sameEmotion -> "${a.nickname} 님과 ${b.nickname} 님은 장소도 마음도 같았습니다. 함께 공감하는 오늘의 순간이네요."
                    else -> "${a.nickname} 님과 ${b.nickname} 님은 장소도 마음도 다르게 골랐습니다. 서로의 취향을 새롭게 알아가는 시간이네요."
                }
                val shortText = when {
                    samePlace && !sameEmotion -> "${a.nickname} 님과 ${b.nickname} 님은 같은 장소, 다른 마음을 골랐네요."
                    !samePlace && sameEmotion -> "${a.nickname} 님과 ${b.nickname} 님은 다른 장소, 같은 마음을 골랐네요."
                    samePlace && sameEmotion -> "두 분은 장소도 마음도 같았네요."
                    else -> "서로 다른 장소와 마음, 각자의 취향이네요."
                }
                lines += ScriptLine("같은 여행, 다른 기억", if (duration == 30) shortText else text, setOf("pick:${a.id}", "pick:${b.id}", "visit:${a.visitId}", "visit:${b.visitId}"))
            }
        }
        if (duration == 180) {
            input.picks.forEach { pick ->
                lines += ScriptLine("기억을 잇는 질문", "${pick.nickname} 님의 ‘${pick.emotion}’라는 선택을 다시 떠올려 볼까요. 이 마음을 고른 이유가 더 있다면 함께 나눠도 좋겠습니다. 말하고 싶지 않다면 그 마음 그대로 간직해도 괜찮아요. 누군가의 선택에 정답을 매기지 않고, 각자가 기억하는 방식을 들어 주세요.", setOf("pick:${pick.id}"))
            }
            lines += ScriptLine("대화의 시간", "서로의 선택을 들어 보니 어떤가요? 나와 같은 마음도, 미처 몰랐던 마음도 있었을 거예요. 잠시 이야기를 나눌 시간을 드릴게요.", input.picks.map { "pick:${it.id}" }.toSet())
        }
        lines += ScriptLine("클로징", if (duration == 30) "여러분의 기억을 함께 나눠 보세요. 안전한 귀갓길 되세요." else "함께한 하루를 각자의 마음으로 기억해 주셔서 고맙습니다. 오늘의 여운 에프엠은 여기까지입니다. 안전하고 편안한 귀갓길 되세요.", tripEvidence)
        val episode = Episode(UUID.randomUUID().toString(), input.trip.id, "우리의 귀갓길 · ${input.picks.size}개의 기억", now, duration, lines, input.trip.dataSource)
        check(ProvenanceValidator.validate(episode, input))
        return episode
    }

    fun fitDuration(requested: Int, remaining: Int?): Int {
        require(requested in listOf(30, 60, 180))
        if (remaining != null && remaining < 30) throw EpisodeException("남은 이동시간이 30초 미만입니다. 도착 후 만들어 주세요.")
        return listOf(180, 60, 30).first { it <= requested && (remaining == null || it <= remaining) }
    }
}

object ProvenanceValidator {
    fun validate(episode: Episode, input: EpisodeInput): Boolean {
        val allowed = setOf("trip:${input.trip.id}") + input.visits.filter { it.usable && it.tripId == input.trip.id }.map { "visit:${it.id}" } + input.picks.map { "pick:${it.id}" }
        return episode.tripId == input.trip.id && episode.lines.isNotEmpty() && episode.lines.all { line ->
            line.text.isNotBlank() && line.evidence.isNotEmpty() && allowed.containsAll(line.evidence)
        }
    }
}
