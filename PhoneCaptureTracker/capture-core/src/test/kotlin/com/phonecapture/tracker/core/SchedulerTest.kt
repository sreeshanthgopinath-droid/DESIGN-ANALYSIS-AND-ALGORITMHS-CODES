package com.phonecapture.tracker.core

import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.schedule.ScheduleCommand
import com.phonecapture.tracker.core.schedule.SchedulePlanner
import com.phonecapture.tracker.core.schedule.ScheduleStateMachine
import com.phonecapture.tracker.core.schedule.SingleFlight
import com.phonecapture.tracker.core.schedule.Transition
import java.time.Instant
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class SchedulerTest {
    private val schedule = CaptureSchedule()
    private val planner = SchedulePlanner(schedule)
    private val min = 60_000L
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun slotsAreAlignedToHalfHours() {
        val t = at("2026-11-11T10:47:13Z")
        assertEquals(at("2026-11-11T10:30:00Z"), schedule.slotStart(schedule.slotOf(t)))
        assertEquals(at("2026-11-11T11:00:00Z"), schedule.nextSlotStart(t))
        assertEquals(at("2026-11-11T11:00:00Z"), schedule.nextSlotStart(at("2026-11-11T10:30:00Z")))
    }

    @Test
    fun istHalfHourOffsetGivesLocalHalfHours() {
        // 10:47 IST = 05:17 UTC; next attempt 05:30 UTC = 11:00 IST.
        val next = schedule.nextSlotStart(at("2026-11-11T05:17:00Z"))
        assertEquals(at("2026-11-11T05:30:00Z"), next)
    }

    @Test
    fun firstTickRunsCurrentSlotWithoutMissed() {
        val now = at("2026-11-11T10:47:00Z")
        val plan = planner.planTick(now, lastHandledSlot = null)
        assertEquals(schedule.slotOf(now), plan.slotToRun)
        assertTrue(plan.missedSlots.isEmpty())
        assertEquals(at("2026-11-11T11:00:00Z"), plan.nextFireAtMillis)
    }

    @Test
    fun duplicateTickInSameSlotDoesNotStartSecondCycle() {
        val now = at("2026-11-11T10:47:00Z")
        val slot = schedule.slotOf(now)
        val plan = planner.planTick(now + 5 * min, lastHandledSlot = slot)
        assertNull(plan.slotToRun)
        assertTrue(plan.missedSlots.isEmpty())
    }

    @Test
    fun delayedTickRunsOnlyCurrentSlotAndRecordsMissedOnes() {
        val last = schedule.slotOf(at("2026-11-11T10:00:00Z"))
        // Phone unavailable until 12:10 – 10:30, 11:00 and 11:30 were missed; 12:00 runs once.
        val plan = planner.planTick(at("2026-11-11T12:10:00Z"), last)
        assertEquals(schedule.slotOf(at("2026-11-11T12:00:00Z")), plan.slotToRun)
        assertEquals(
            listOf("10:30", "11:00", "11:30").map { schedule.slotOf(at("2026-11-11T$it:00Z")) },
            plan.missedSlots,
        )
        assertEquals(0, plan.missedNotRecorded)
    }

    @Test
    fun longOutageIsCappedAndCounted() {
        val capped = SchedulePlanner(schedule, maxMissedRecorded = 4)
        val last = schedule.slotOf(at("2026-11-11T00:00:00Z"))
        val plan = capped.planTick(at("2026-11-12T00:05:00Z"), last)
        assertEquals(4, plan.missedSlots.size)
        assertEquals(47 - 4, plan.missedNotRecorded.toInt()) // 47 slots between, 4 recorded
        assertEquals(plan.slotToRun!! - 1, plan.missedSlots.last())
    }

    @Test
    fun clockMovedBackwardsIsFlaggedAndNeverInventsMissedAttempts() {
        val last = schedule.slotOf(at("2026-11-11T12:00:00Z"))
        val plan = planner.planTick(at("2026-11-11T09:10:00Z"), last)
        assertTrue(plan.clockMovedBackwards)
        assertTrue(plan.missedSlots.isEmpty())
        assertEquals(schedule.slotOf(at("2026-11-11T09:00:00Z")), plan.slotToRun)
    }

    @Test
    fun startBaselinePreventsPausedTimeCountingAsMissed() {
        val resumeAt = at("2026-11-11T15:12:00Z")
        val baseline = planner.baselineForStart(resumeAt)
        val plan = planner.planTick(resumeAt, baseline)
        assertEquals(schedule.slotOf(resumeAt), plan.slotToRun)
        assertTrue(plan.missedSlots.isEmpty())
    }

    @Test
    fun simulatedDayWithJitterRunsEachSlotExactlyOnce() {
        val random = Random(42)
        val start = at("2026-11-11T00:00:00Z")
        val end = start + 24 * 60 * min
        var now = start
        var last: Long? = null
        val ran = mutableListOf<Long>()
        val missed = mutableListOf<Long>()
        while (now < end) {
            val plan = planner.planTick(now, last)
            plan.slotToRun?.let { ran += it; last = it }
            missed += plan.missedSlots
            // Timer and alarm both fire, with random delays; sometimes the phone sleeps for 70 minutes.
            now = when (random.nextInt(10)) {
                0 -> plan.nextFireAtMillis + 70 * min
                1 -> now + 1_000 // duplicate tick from the second trigger
                else -> plan.nextFireAtMillis + random.nextInt(120_000)
            }
        }
        assertEquals(ran.size, ran.toSet().size, "a slot ran twice")
        assertTrue(ran.intersect(missed.toSet()).isEmpty(), "a slot was both run and missed")
        val all = (ran + missed).sorted()
        assertEquals((all.first()..all.last()).toList(), all, "every slot is either run or recorded as missed")
    }

    @Test
    fun stateMachineAllowsOnlySensibleTransitions() {
        assertIs<Transition.Allowed>(ScheduleStateMachine.apply(ScheduleState.STOPPED, ScheduleCommand.START))
        assertIs<Transition.Rejected>(ScheduleStateMachine.apply(ScheduleState.STOPPED, ScheduleCommand.PAUSE))
        assertIs<Transition.Rejected>(ScheduleStateMachine.apply(ScheduleState.STOPPED, ScheduleCommand.RESUME))
        assertIs<Transition.Rejected>(ScheduleStateMachine.apply(ScheduleState.PAUSED, ScheduleCommand.START))
        val resumed = ScheduleStateMachine.apply(ScheduleState.PAUSED, ScheduleCommand.RESUME)
        assertEquals(ScheduleState.RUNNING, (resumed as Transition.Allowed).to)
        for (state in ScheduleState.entries) {
            val stop = ScheduleStateMachine.apply(state, ScheduleCommand.STOP)
            assertEquals(ScheduleState.STOPPED, (stop as Transition.Allowed).to)
        }
    }

    @Test
    fun singleFlightRejectsOverlappingCycles() = runTest {
        val flight = SingleFlight()
        val gate = CompletableDeferred<Unit>()
        val first = async { flight.runExclusive { gate.await(); "first" } }
        testScheduler.runCurrent()
        assertTrue(flight.isRunning)
        assertNull(flight.runExclusive { "second" })
        gate.complete(Unit)
        assertEquals("first", first.await())
        assertFalse(flight.isRunning)
        assertEquals("third", flight.runExclusive { "third" })
    }

    @Test
    fun singleFlightReleasesAfterException() = runTest {
        val flight = SingleFlight()
        runCatching { flight.runExclusive { error("boom") } }
        assertEquals(1, flight.runExclusive { 1 })
    }
}
