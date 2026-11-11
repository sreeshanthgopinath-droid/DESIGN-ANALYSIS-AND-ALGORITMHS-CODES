package com.phonecapture.tracker.core.schedule

import com.phonecapture.tracker.core.model.ScheduleState

enum class ScheduleCommand { START, PAUSE, RESUME, STOP }

sealed interface Transition {
    data class Allowed(val from: ScheduleState, val to: ScheduleState) : Transition
    data class Rejected(val from: ScheduleState, val command: ScheduleCommand, val reason: String) : Transition
}

/** Validates user commands so the UI and the notification actions cannot create odd states. */
object ScheduleStateMachine {
    fun apply(from: ScheduleState, command: ScheduleCommand): Transition = when (command) {
        ScheduleCommand.START -> when (from) {
            ScheduleState.STOPPED -> Transition.Allowed(from, ScheduleState.RUNNING)
            // Starting an already running schedule is how the app re-attaches after a process kill.
            ScheduleState.RUNNING -> Transition.Allowed(from, ScheduleState.RUNNING)
            ScheduleState.PAUSED -> Transition.Rejected(from, command, "Schedule is paused; use Resume.")
        }
        ScheduleCommand.PAUSE -> when (from) {
            ScheduleState.RUNNING -> Transition.Allowed(from, ScheduleState.PAUSED)
            ScheduleState.PAUSED -> Transition.Allowed(from, ScheduleState.PAUSED)
            ScheduleState.STOPPED -> Transition.Rejected(from, command, "Schedule is not running.")
        }
        ScheduleCommand.RESUME -> when (from) {
            ScheduleState.PAUSED -> Transition.Allowed(from, ScheduleState.RUNNING)
            ScheduleState.RUNNING -> Transition.Allowed(from, ScheduleState.RUNNING)
            ScheduleState.STOPPED -> Transition.Rejected(from, command, "Schedule is stopped; use Start.")
        }
        ScheduleCommand.STOP -> Transition.Allowed(from, ScheduleState.STOPPED)
    }
}
