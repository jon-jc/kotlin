package com.roam.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roam.core.CommerceService
import com.roam.data.RoamDatabase
import com.roam.data.RoomAccountStore
import org.junit.*
import org.junit.Assert.*

class JourneyTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: RoamDatabase
    private lateinit var model: RoamViewModel
    private val models = ViewModelStore()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RoamDatabase::class.java).build()
        compose.runOnUiThread {
            model = ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RoamViewModel(CommerceService(RoomAccountStore(db)), SavedStateHandle()) as T
            })[RoamViewModel::class.java]
        }
        compose.setContent { RoamTheme { RoamApp(model) } }
        compose.waitUntil(10_000) { model.state.value.loaded }
    }
    @After fun tearDown() { compose.runOnUiThread { models.clear() }; db.close() }
    private fun scroll(text: String) { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)) }
    private fun click(text: String) { compose.onNodeWithText(text).performClick() }
    private fun openCheckout() {
        scroll("The quiet side of Kyoto"); click("The quiet side of Kyoto"); click("Make it your next")
        scroll("Confirm demo reservation")
    }

    @Test fun reserveRecoverAndCancelWithoutDoubleDebit() {
        openCheckout()
        click("Demo controls · Normal")
        click("Response interrupted")
        click("Confirm demo reservation")
        compose.waitUntil(10_000) { model.state.value.screen.error?.contains("response was interrupted") == true && model.state.value.snapshot.bookings.size == 1 }
        assertEquals(1, model.state.value.snapshot.bookings.size)
        scroll("Recover my reservation"); click("Recover my reservation")
        compose.waitUntil(10_000) { model.state.value.screen.receipt != null }
        compose.onNodeWithText("Your demo reservation is confirmed.").assertExists()
        assertEquals(1, model.state.value.snapshot.bookings.size)
        assertEquals(0, model.state.value.snapshot.account.balance.minor)
        scroll("Cancel reservation"); click("Cancel reservation"); click("Yes, cancel stay")
        compose.waitUntil(10_000) { model.state.value.snapshot.bookings.single().cancelled }
        assertEquals(8500, model.state.value.snapshot.account.balance.minor)
        assertEquals(2, model.state.value.snapshot.ledger.size)
    }

    @Test fun discoverySearchSaveAndEmptyState() {
        compose.onNodeWithText("Explore places").performTextInput("Kyoto")
        scroll("The quiet side of Kyoto")
        compose.onNodeWithContentDescription("Save The quiet side of Kyoto").performClick()
        compose.waitUntil(5_000) { "kyoto" in model.state.value.snapshot.account.saved }
        compose.onNodeWithContentDescription("Unsave The quiet side of Kyoto").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Explore places").performTextReplacement("nowhere matches this")
        scroll("Room for a new discovery")
        compose.onNodeWithText("Reset discovery").assertExists()
    }

    @Test fun benefitRedemptionAndPrivateProfile() {
        click("Wallet")
        scroll("Claim $25 welcome credit"); click("Claim $25 welcome credit")
        compose.waitUntil(5_000) { model.state.value.snapshot.account.balance.minor == 11000L }
        compose.onNodeWithText("Welcome credit claimed").assertIsNotEnabled()
        click("Passport")
        scroll("Make it yours"); click("Make it yours")
        compose.onNodeWithText("Name").performTextReplacement("Alex Chen")
        click("Save profile")
        compose.waitUntil(5_000) { model.state.value.snapshot.account.profile.name == "Alex Chen" }
        scroll("Your privacy, your choice"); click("Your privacy, your choice")
        compose.onNodeWithContentDescription("Show hometown").performClick()
        click("Save privacy")
        compose.waitUntil(5_000) { !model.state.value.snapshot.account.profile.shareHometown }
        scroll("What the community sees")
        compose.onNodeWithText("⌂  San Francisco, CA").assertDoesNotExist()
    }

    @Test fun declinedCardLeavesCheckoutRecoverable() {
        openCheckout()
        click("Demo controls · Normal"); click("Card declined"); click("Confirm demo reservation")
        compose.waitUntil(10_000) { model.state.value.screen.error?.contains("declined") == true }
        assertTrue(model.state.value.snapshot.bookings.isEmpty())
        assertEquals(8500, model.state.value.snapshot.account.balance.minor)
        assertFalse(model.state.value.screen.busy)
        scroll("Demo controls · Decline"); click("Demo controls · Decline"); click("Normal")
        click("Confirm demo reservation")
        compose.waitUntil(10_000) { model.state.value.screen.receipt != null }
        assertEquals(1, model.state.value.snapshot.bookings.size)
    }
}
