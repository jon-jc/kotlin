package com.roam.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.roam.core.*
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PresentationSafetyTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 2)

    @Test
    fun missingReceiptHasAVisibleExit() {
        var accepted: Intent? = null
        compose.setContent {
            RoamTheme { ReceiptScreen(null, today, false, null, false) { accepted = it } }
        }
        compose.onNodeWithText("Receipt unavailable").assertIsDisplayed()
        compose.onNodeWithText("Go back").performClick()
        assertEquals(Intent.Back, accepted)
    }

    @Test
    fun historicalLiveReceiptUsesStoredStayAndOffersServerValidatedCancellation() {
        val request =
            BookingRequest(
                "historical",
                "removed-from-catalog",
                today.minusDays(1),
                today.plusDays(2),
                2,
                false,
            )
        val booking =
            Booking(
                "confirmed",
                request,
                Quote(3, Money(30000), Money(1200), Money(31200), Money(0), Money(31200)),
                0,
                stay = BookedStay("Original Harbor House", "Old Town", "Portugal", "coast"),
                simulated = false,
            )
        compose.setContent { RoamTheme { ReceiptScreen(booking, today, false, null, false) {} } }
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Original Harbor House"))
        compose.onNodeWithText("Original Harbor House").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Cancel reservation"))
        compose.onNodeWithText("Cancel reservation").assertIsEnabled()
        compose.onNodeWithText("Export receipt").assertDoesNotExist()
        compose.onNodeWithText("Sample card · No money was charged").assertDoesNotExist()
    }

    @Test
    fun liveCheckoutRequiresAuthoritativePriceAndHasNoDemoControls() {
        val stay =
            Catalog.stays.first().copy(id = "server-stay", host = "", rating = "", reviews = 0)
        val state =
            RoamState(
                screen =
                    ScreenState(
                        selectedStay = stay.id,
                        checkout = true,
                        checkIn = today.plusDays(14),
                        requestKey = "live-request",
                    ),
                snapshot = AccountSnapshot(account = Account(balance = Money(0))),
                loaded = true,
                isDemo = false,
                catalog = listOf(stay),
                quoteLoading = true,
            )
        compose.setContent { RoamTheme { CheckoutScreen(state, null) {} } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Continue to payment"))
        compose.onNodeWithText("Continue to payment").assertIsNotEnabled()
        compose.onNodeWithText("Demo controls · Normal").assertDoesNotExist()
        compose.onNodeWithText("Confirm demo reservation").assertDoesNotExist()
    }

    @Test
    fun pendingPaymentOffersRecoveryWithoutClaimingConfirmation() {
        var accepted: Intent? = null
        val booking = incompleteBooking().copy(paymentPending = true)
        compose.setContent {
            RoamTheme { ReceiptScreen(booking, today, false, null, false) { accepted = it } }
        }
        compose.onNodeWithText("PAYMENT PENDING").assertExists()
        compose.onNodeWithText("Your reservation is confirmed.").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Continue payment"))
        compose.onNodeWithText("Continue payment").performClick()
        assertEquals(Intent.ResumePayment(booking.request.key), accepted)
        compose.onNodeWithText("Export receipt").assertDoesNotExist()
    }

    @Test
    fun cancellationPendingNeverClaimsReturnedCreditOrOffersDuplicateCancellation() {
        val booking = incompleteBooking().copy(cancellationPending = true)
        compose.setContent { RoamTheme { ReceiptScreen(booking, today, false, null, false) {} } }
        compose.onNodeWithText("CANCELLATION IN PROGRESS").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Refresh reservation"))
        compose.onNodeWithText("Cancel reservation").assertDoesNotExist()
        compose.onNodeWithText("Credits returned").assertDoesNotExist()
    }

    @Test
    fun supportReviewDisplaysFullReferenceAndBlocksPaymentRetry() {
        val booking = incompleteBooking().copy(paymentPending = true, requiresSupport = true)
        compose.setContent { RoamTheme { ReceiptScreen(booking, today, false, null, false) {} } }
        compose.onNodeWithText("A LITTLE HELP IS NEEDED").assertExists()
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Support reference: ${booking.id}"))
        compose.onNodeWithText("Support reference: ${booking.id}").assertIsDisplayed()
        compose.onNodeWithText("Continue payment").assertDoesNotExist()
    }

    private fun incompleteBooking() =
        Booking(
            "full-support-reference-1234",
            BookingRequest("pending-key", "removed", today.plusDays(2), today.plusDays(5), 2, true),
            Quote(3, Money(30000), Money(1200), Money(31200), Money(1000), Money(30200)),
            0,
            stay = BookedStay("Original stay", "Old Town", "Portugal", "coast"),
            simulated = false,
        )
}
