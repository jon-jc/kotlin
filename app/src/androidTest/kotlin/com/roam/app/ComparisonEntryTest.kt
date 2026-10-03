package com.roam.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class ComparisonEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun publicComparisonOpensFromExploreAndReturnsWithoutSignIn() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Compare stays").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("discovery_feed").performScrollToNode(hasText("Compare stays"))
        compose.onNodeWithText("Compare stays").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        compose.onNodeWithText("Back to Explore").assertIsDisplayed()
        compose.onNodeWithText("Back to Explore").performSemanticsAction(SemanticsActions.OnClick) {
            it()
        }
        compose.onNodeWithTag("discovery_feed").assertIsDisplayed()
    }
}
