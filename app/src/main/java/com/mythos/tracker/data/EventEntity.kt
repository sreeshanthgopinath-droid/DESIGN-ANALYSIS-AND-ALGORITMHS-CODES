package com.mythos.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured event waiting to be synced to the Mythos cloud server.
 *
 * kind:       "app_session" | "location" | "photo" | "video" | "call" | "sms_bank" |
 *             "notification" | "now_playing" | "diary" | "essential_note" | "shared_note"
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
