package com.mythos.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mythos.tracker.day.DayInsights
import com.mythos.tracker.day.DayRepo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SUGGESTIONS = listOf(
    "When did I sleep?",
    "When did I wake up?",
    "How much did I spend?",
    "What's my most used app?",
    "Where was I at 3pm?",
    "What did I watch?",
    "What did I search?",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var day by remember { mutableLongStateOf(DayRepo.startOfDay(System.currentTimeMillis())) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var highlights by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(day) {
        highlights = runCatching { DayInsights.highlights(context, day) }.getOrDefault(emptyList())
        answer = ""
    }

    fun ask(text: String) {
        question = text
        thinking = true
        answer = ""
        scope.launch {
            answer = runCatching { DayInsights.answer(context, day, text) }
                .getOrElse { "Couldn't work that out: ${it.message}" }
            thinking = false
        }
    }

    val isToday = day == DayRepo.startOfDay(System.currentTimeMillis())

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row2(
            left = { TextButton(onClick = { day = DayRepo.addDays(day, -1) }) { Text("‹ Prev") } },
            middle = {
                Text(
                    if (isToday) "Today" else SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(day)),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            right = { TextButton(enabled = !isToday, onClick = { day = DayRepo.addDays(day, 1) }) { Text("Next ›") } },
        )

        if (highlights.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                highlights.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
            }
        }

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Ask about this day") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = question.isNotBlank() && !thinking, onClick = { ask(question) }) {
            Text(if (thinking) "Thinking…" else "Ask")
        }

        if (answer.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(answer, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }

        HorizontalDivider()
        Text("Try asking", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SUGGESTIONS.forEach { s -> AssistChip(onClick = { ask(s) }, label = { Text(s) }) }
        }
        Text(
            "All answers are worked out on your phone from your own data. Nothing is sent anywhere. " +
                "Sleep and wake are estimated from when the phone sits untouched at night, so they get more accurate the longer you use the app.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Row2(left: @Composable () -> Unit, middle: @Composable () -> Unit, right: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) { left(); middle(); right() }
}
