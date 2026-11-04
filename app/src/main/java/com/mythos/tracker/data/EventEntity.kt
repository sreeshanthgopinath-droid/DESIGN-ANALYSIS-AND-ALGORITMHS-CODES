package com.mythos.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured event waiting to be synced to the laptop memory server.
 *
 * kind:       "app_session" | "location" | "photo" | "video"
 * sourceKey:  stable identifier of the event on the device; (kind, sourceKey) is unique,
 *             so re-reading the same device data never creates duplicates.
 * payloadJson: kind-specific JSON details.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["kind", "source_key"], unique = true)],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    val synced: Boolean = false,
)
