package com.phonecapture.tracker.capture.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.dashboard.DashboardScreen
import com.phonecapture.tracker.capture.ui.diagnostics.DiagnosticsScreen
import com.phonecapture.tracker.capture.ui.gallery.GalleryScreen
import com.phonecapture.tracker.capture.ui.history.HistoryScreen
import com.phonecapture.tracker.capture.ui.onboarding.OnboardingScreen
import com.phonecapture.tracker.capture.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private val TABS = listOf("My day", "Gallery", "History", "Settings")

/**
 * The complete capture UI. PhoneCaptureTracker's MainActivity shows it full-screen; Mythos Tracker
 * can show it as a destination behind its "Phone captures" card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureApp(modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    if (!settings.onboardingCompleted) {
        OnboardingScreen(modifier)
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    BackHandler(enabled = showDiagnostics || tab != 0) {
        if (showDiagnostics) showDiagnostics = false else tab = 0
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                if (showDiagnostics) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showDiagnostics = false }) { Text("‹ Back") }
                        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        TABS.forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (showDiagnostics) {
                DiagnosticsScreen(onShowMessage = showMessage)
            } else {
                when (tab) {
                    0 -> DashboardScreen(
                        onOpenGallery = { tab = 1 },
                        onOpenHistory = { tab = 2 },
                        onOpenSettings = { tab = 3 },
                        onOpenDiagnostics = { showDiagnostics = true },
                        onShowMessage = showMessage,
                    )
                    1 -> GalleryScreen(onShowMessage = showMessage)
                    2 -> HistoryScreen()
                    else -> SettingsScreen(onOpenDiagnostics = { showDiagnostics = true }, onShowMessage = showMessage)
                }
            }
        }
    }
}
