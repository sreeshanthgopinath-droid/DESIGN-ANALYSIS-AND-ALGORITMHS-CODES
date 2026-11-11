package com.phonecapture.tracker.capture.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.Severity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "diagnostic_logs", indices = [Index(value = ["timestamp_utc"])])
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "timestamp_utc") val timestampUtc: Long,
    @ColumnInfo(name = "type") val type: DiagnosticType,
    @ColumnInfo(name = "severity") val severity: Severity,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "detail") val detail: String? = null,
    @ColumnInfo(name = "capture_id") val captureId: String? = null,
)

@Dao
interface DiagnosticLogDao {
    @Insert
    suspend fun insert(entry: DiagnosticLogEntity): Long

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestamp_utc DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticLogEntity>>

    @Query("SELECT * FROM diagnostic_logs ORDER BY timestamp_utc DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticLogEntity>

    @Query("SELECT * FROM diagnostic_logs WHERE severity != 'INFO' ORDER BY timestamp_utc DESC, id DESC LIMIT 1")
    fun observeLatestProblem(): Flow<DiagnosticLogEntity?>

    @Query("DELETE FROM diagnostic_logs WHERE timestamp_utc < :cutoffUtc")
    suspend fun deleteOlderThan(cutoffUtc: Long): Int

    @Query("DELETE FROM diagnostic_logs")
    suspend fun deleteAll()
}
