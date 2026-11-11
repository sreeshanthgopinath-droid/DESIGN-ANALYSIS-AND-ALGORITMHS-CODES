package com.phonecapture.tracker.core.schedule

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs at most one block at a time. A second caller arriving while a block runs is rejected
 * immediately (returns null) instead of queueing, which is what prevents overlapping capture
 * cycles when the alarm and the in-service timer fire close together.
 */
class SingleFlight {
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    suspend fun <T> runExclusive(block: suspend () -> T): T? {
        if (!running.compareAndSet(false, true)) return null
        try {
            return block()
        } finally {
            running.set(false)
        }
    }
}
