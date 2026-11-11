package com.phonecapture.tracker.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A local calendar day expressed as a UTC millisecond range. Data is stored in UTC; screens ask for
 * local days. Uses the zone rules, so 23- and 25-hour DST days are handled correctly.
 */
data class DayWindow(val date: LocalDate, val startUtcMillis: Long, val endUtcMillisExclusive: Long) {
    operator fun contains(utcMillis: Long): Boolean = utcMillis in startUtcMillis until endUtcMillisExclusive

    companion object {
        fun of(date: LocalDate, zone: ZoneId): DayWindow = DayWindow(
            date = date,
            startUtcMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
            endUtcMillisExclusive = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

        fun containing(utcMillis: Long, zone: ZoneId): DayWindow =
            of(Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate(), zone)

        fun range(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): LongRange {
            require(!toInclusive.isBefore(from)) { "Range end is before start" }
            return of(from, zone).startUtcMillis until of(toInclusive, zone).endUtcMillisExclusive
        }
    }
}
