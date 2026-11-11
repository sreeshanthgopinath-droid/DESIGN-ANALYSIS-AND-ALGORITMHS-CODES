package com.phonecapture.tracker.core.schedule

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Divides time into fixed slots (default 30 minutes) aligned to the Unix epoch, so slots start at
 * :00 and :30 UTC (and therefore at :00 / :30 local time in whole- and half-hour time zones such as
 * IST). Each slot gets at most one capture cycle; the slot index is the de-duplication key.
 */
class CaptureSchedule(val interval: Duration = 30.minutes) {

    init {
        require(interval.inWholeMilliseconds >= 60_000) { "Interval must be at least one minute" }
    }

    val intervalMillis: Long = interval.inWholeMilliseconds

    fun slotOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, intervalMillis)

    fun slotStart(slot: Long): Long = slot * intervalMillis

    fun nextSlotStart(nowMillis: Long): Long = slotStart(slotOf(nowMillis) + 1)
}

/**
 * Decision for one scheduler tick.
 *
 * @property slotToRun the slot to capture now, or null when the current slot was already handled.
 * @property missedSlots slots that passed without a cycle; they are recorded as missed attempts,
 *   never captured late ("no catch-up photographs").
 * @property missedNotRecorded number of older missed slots beyond the recording cap.
 * @property clockMovedBackwards the wall clock is earlier than the last handled slot.
 * @property nextFireAtMillis when the next tick should happen (start of the following slot).
 */
data class TickPlan(
    val slotToRun: Long?,
    val missedSlots: List<Long>,
    val missedNotRecorded: Long,
    val clockMovedBackwards: Boolean,
    val nextFireAtMillis: Long,
)

class SchedulePlanner(
    private val schedule: CaptureSchedule,
    /** Upper bound on individually recorded missed slots after a long outage (96 = 2 days). */
    private val maxMissedRecorded: Int = 96,
) {
    /**
     * @param lastHandledSlot the last slot that was run or recorded as missed while the schedule
     *   was RUNNING; null on the very first tick after Start/Resume.
     */
    fun planTick(nowMillis: Long, lastHandledSlot: Long?): TickPlan {
        val current = schedule.slotOf(nowMillis)
        val next = schedule.slotStart(current + 1)

        if (lastHandledSlot == null) {
            return TickPlan(current, emptyList(), 0, clockMovedBackwards = false, nextFireAtMillis = next)
        }
        if (current == lastHandledSlot) {
            // Duplicate or early tick inside a slot that already ran: never start a second cycle.
            return TickPlan(null, emptyList(), 0, clockMovedBackwards = false, nextFireAtMillis = next)
        }
        if (current < lastHandledSlot) {
            // Wall clock was moved back. Run the current slot only if storage has no record of it
            // (the caller checks); never fabricate missed attempts for a negative gap.
            return TickPlan(current, emptyList(), 0, clockMovedBackwards = true, nextFireAtMillis = next)
        }

        val firstMissed = lastHandledSlot + 1
        val missedCount = current - firstMissed // slots strictly between last handled and current
        val recordedCount = minOf(missedCount, maxMissedRecorded.toLong())
        val missed = if (recordedCount <= 0) {
            emptyList()
        } else {
            ((current - recordedCount) until current).toList()
        }
        return TickPlan(
            slotToRun = current,
            missedSlots = missed,
            missedNotRecorded = missedCount - recordedCount,
            clockMovedBackwards = false,
            nextFireAtMillis = next,
        )
    }

    /**
     * Value to store as "last handled slot" when the user starts or resumes the schedule, so the
     * time spent stopped or paused is not reported as missed attempts.
     */
    fun baselineForStart(nowMillis: Long): Long = schedule.slotOf(nowMillis) - 1
}
