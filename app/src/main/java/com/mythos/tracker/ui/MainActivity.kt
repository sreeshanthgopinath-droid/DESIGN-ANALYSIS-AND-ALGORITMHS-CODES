package com.mythos.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mythos.tracker.LocationService
import com.mythos.tracker.work.Scheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Scheduler.start(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocationService.start(this)
    }
}

@Composable
private fun Root() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Day", "Map", "Ask", "Add", "Settings")
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> DayScreen()
            1 -> MapScreen()
            2 -> AskScreen()
            3 -> AddScreen()
            else -> SettingsScreen()
        }
    }
}
