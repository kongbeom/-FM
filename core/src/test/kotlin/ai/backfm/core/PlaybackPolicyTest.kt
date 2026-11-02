package ai.backfm.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun pauseDuringLookupOrSynthesisSurvivesPreparationCompletion() {
        val state = PlaybackRequestState()
        val token = state.begin()
        state.pause()
        assertTrue(state.isCurrent(token))
        assertFalse(state.shouldPlay(token))
        state.resume()
        assertTrue(state.shouldPlay(token))
    }
    @Test fun stopInvalidatesAllLateWork() {
        val state = PlaybackRequestState()
        val old = state.begin()
        state.stop()
        assertFalse(state.isCurrent(old))
        state.resume()
        assertFalse(state.shouldPlay(old))
    }
    @Test fun newEpisodeSupersedesOldRequestAndItsPause() {
        val state = PlaybackRequestState()
        val first = state.begin(); state.pause()
        val second = state.begin()
        assertFalse(state.shouldPlay(first))
        assertTrue(state.shouldPlay(second))
    }
    @Test fun synthesisAndLookupTimeAreDeductedUsingMonotonicTime() {
        var clock = 100L
        val budget = RideTimeBudget { clock }
        budget.begin("trip"); budget.update("trip", 60)
        assertTrue(budget.fits(50_000))
        clock += 20_000
        assertEquals(40_000L, budget.remainingMillis())
        assertFalse(budget.fits(50_000))
    }
    @Test fun newerNavigationObservationReplacesOldDeadline() {
        var clock = 0L
        val budget = RideTimeBudget { clock }
        budget.begin("trip"); budget.update("trip", 60)
        clock = 30_000
        budget.update("trip", 90)
        clock = 45_000
        assertEquals(75, budget.remainingSeconds())
    }
    @Test fun resumeUsesRemainingAudioButRestartUsesFullDuration() {
        val budget = RideTimeBudget { 0 }
        budget.begin("trip"); budget.update("trip", 20)
        assertTrue(budget.fits(60_000, 45_000))
        assertFalse(budget.fits(60_000, 0))
    }
    @Test fun expiredDeadlineDoesNotTurnIntoUnknown() {
        var clock = 0L
        val budget = RideTimeBudget { clock }
        budget.begin("trip"); budget.update("trip", 1)
        clock = 10_000
        assertEquals(0L, budget.remainingMillis())
        assertFalse(budget.fits(1))
    }
    @Test fun lateEventsFromPreviousTripCannotResetBudget() {
        val budget = RideTimeBudget { 0 }
        budget.begin("new"); budget.update("new", 10)
        budget.update("old", 1000)
        assertEquals(10, budget.remainingSeconds())
    }
    @Test fun absentOrClearedEtaIsExplicitlyUnknown() {
        val budget = RideTimeBudget { 0 }
        assertNull(budget.remainingMillis())
        budget.begin("trip"); budget.update("trip", null)
        assertNull(budget.remainingMillis())
        budget.update("trip", 1); budget.clear()
        assertNull(budget.remainingMillis())
    }
    @Test fun invalidNewReadingCannotErasePreviouslyKnownDeadline() {
        var clock = 0L
        val budget = RideTimeBudget { clock }
        budget.begin("trip"); budget.update("trip", 60)
        clock = 10_000; budget.update("trip", null); budget.update("trip", -1)
        assertEquals(50, budget.remainingSeconds())
    }
    @Test fun reconnectDoesNotExtendOrClearDeadline() {
        var clock = 0L
        val budget = RideTimeBudget { clock }
        budget.begin("trip"); budget.update("trip", 60)
        clock = 10_000; budget.begin("trip")
        assertEquals(50, budget.remainingSeconds())
    }
}
