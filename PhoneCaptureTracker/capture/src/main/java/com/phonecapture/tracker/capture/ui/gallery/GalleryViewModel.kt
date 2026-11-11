package com.phonecapture.tracker.capture.ui.gallery

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.cloud.DeleteResult
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.time.DayWindow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DateFilter(val label: String) {
    ALL("All dates"),
    TODAY("Today"),
    LAST_7_DAYS("7 days"),
    LAST_30_DAYS("30 days"),
    DAY("Chosen day"),
}

data class GalleryQuery(val category: CaptureCategory, val filter: DateFilter, val day: LocalDate)

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(private val graph: CaptureGraph) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val started = SharingStarted.WhileSubscribed(5_000)

    private val _query = MutableStateFlow(GalleryQuery(CaptureCategory.FRONT, DateFilter.ALL, LocalDate.now(zone)))
    val query: StateFlow<GalleryQuery> = _query.asStateFlow()

    private val _month = MutableStateFlow(YearMonth.now(zone))
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    val items: StateFlow<List<CaptureRecordEntity>> = _query
        .flatMapLatest { q -> graph.repository.observeGallery(q.category, rangeFor(q)) }
        .stateIn(viewModelScope, started, emptyList())

    /** Days of the visible month that have at least one successful capture of the chosen category. */
    val markedDays: StateFlow<Set<LocalDate>> = combine(_query, _month) { q, m -> q.category to m }
        .flatMapLatest { (category, m) ->
            val start = m.atDay(1)
            val end = m.atEndOfMonth()
            graph.repository.dao.observeSuccessTimes(
                category,
                DayWindow.of(start, zone).startUtcMillis,
                DayWindow.of(end, zone).endUtcMillisExclusive,
            ).map { times -> times.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSet() }
        }
        .stateIn(viewModelScope, started, emptySet())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setCategory(category: CaptureCategory) {
        _query.value = _query.value.copy(category = category)
    }

    fun setFilter(filter: DateFilter) {
        _query.value = _query.value.copy(filter = filter)
    }

    fun pickDay(day: LocalDate) {
        _query.value = _query.value.copy(filter = DateFilter.DAY, day = day)
    }

    fun previousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        if (_month.value.isBefore(YearMonth.now(zone))) _month.value = _month.value.plusMonths(1)
    }

    suspend fun loadFullImage(record: CaptureRecordEntity): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching { graph.dataManager.loadFullImage(record) }
    }

    fun delete(record: CaptureRecordEntity, alsoFromCloud: Boolean) {
        viewModelScope.launch {
            graph.thumbnails.evict(record.captureId)
            _message.value = when (val result = graph.dataManager.deleteCapture(record, alsoFromCloud)) {
                DeleteResult.Deleted -> if (alsoFromCloud) "Deleted from the phone and the cloud." else "Deleted from the phone."
                DeleteResult.CloudDeletionPending -> "Deleted from the phone. The cloud copy will be deleted when online."
                is DeleteResult.Failed -> result.message
            }
        }
    }

    /** Writes a decrypted copy to a location the user picked. The exported file is NOT encrypted. */
    fun export(record: CaptureRecordEntity, target: Uri, resolver: ContentResolver) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = graph.dataManager.loadFullImage(record)
                    val stream = resolver.openOutputStream(target) ?: error("Could not open the chosen file")
                    stream.use { it.write(bytes) }
                }
            }
            _message.value = result.fold(
                onSuccess = {
                    graph.diagnostics.log(DiagnosticType.DATA_EXPORTED, "A capture was exported as an unencrypted copy", captureId = record.captureId)
                    "Exported. The exported copy is not encrypted."
                },
                onFailure = { "Export failed: ${it.message}" },
            )
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun rangeFor(q: GalleryQuery): LongRange {
        val today = LocalDate.now(zone)
        return when (q.filter) {
            DateFilter.ALL -> 0L until Long.MAX_VALUE
            DateFilter.TODAY -> DayWindow.range(today, today, zone)
            DateFilter.LAST_7_DAYS -> DayWindow.range(today.minusDays(6), today, zone)
            DateFilter.LAST_30_DAYS -> DayWindow.range(today.minusDays(29), today, zone)
            DateFilter.DAY -> DayWindow.range(q.day, q.day, zone)
        }
    }
}
