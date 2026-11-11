package com.phonecapture.tracker

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesToOnboardingOrDashboard() {
        compose.waitForIdle()
        val onboarding = compose.onAllNodesWithText("Step 1 of 3").fetchSemanticsNodes().isNotEmpty()
        val dashboard = compose.onAllNodesWithText("My day").fetchSemanticsNodes().isNotEmpty()
        assertTrue("Expected the consent screen or the dashboard", onboarding || dashboard)
    }
}
