package com.phonecapture.tracker.capture.data.settings

import android.content.Context
import androidx.core.content.edit
import com.phonecapture.tracker.core.model.ScheduleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScheduleSnapshot(
    val state: ScheduleState = ScheduleState.STOPPED,
    /** Last slot that was run or recorded as missed while RUNNING. */
    val lastHandledSlot: Long? = null,
    val nextAttemptUtc: Long? = null,
    val startedAtUtc: Long? = null,
    val lastCycleUtc: Long? = null,
)

/**
 * Persists the schedule synchronously (commit) so the state survives process death: after Android
 * kills the app, the next start knows the schedule was RUNNING and which slots were missed.
 */
class ScheduleStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val snapshot: StateFlow<ScheduleSnapshot> = state.asStateFlow()
    val current: ScheduleSnapshot get() = state.value

    @Synchronized
    fun update(transform: (ScheduleSnapshot) -> ScheduleSnapshot): ScheduleSnapshot {
        val next = transform(state.value)
        prefs.edit(commit = true) {
            putString(K_STATE, next.state.name)
            putLong(K_LAST_SLOT, next.lastHandledSlot ?: NONE)
            putLong(K_NEXT, next.nextAttemptUtc ?: NONE)
            putLong(K_STARTED, next.startedAtUtc ?: NONE)
            putLong(K_LAST_CYCLE, next.lastCycleUtc ?: NONE)
        }
        state.value = next
        return next
    }

    private fun read(): ScheduleSnapshot = ScheduleSnapshot(
        state = runCatching { ScheduleState.valueOf(prefs.getString(K_STATE, null) ?: "") }
            .getOrDefault(ScheduleState.STOPPED),
        lastHandledSlot = prefs.getLong(K_LAST_SLOT, NONE).orNull(),
        nextAttemptUtc = prefs.getLong(K_NEXT, NONE).orNull(),
        startedAtUtc = prefs.getLong(K_STARTED, NONE).orNull(),
        lastCycleUtc = prefs.getLong(K_LAST_CYCLE, NONE).orNull(),
    )

    private fun Long.orNull(): Long? = takeIf { it != NONE }

    private companion object {
        const val PREFS = "pct_schedule_state"
        const val NONE = Long.MIN_VALUE
        const val K_STATE = "state"
        const val K_LAST_SLOT = "last_handled_slot"
        const val K_NEXT = "next_attempt_utc"
        const val K_STARTED = "started_at_utc"
        const val K_LAST_CYCLE = "last_cycle_utc"
    }
}
