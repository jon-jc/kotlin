package com.roam.app

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.roam.core.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComparisonScreenTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 2)
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val query = ComparisonQuery("Kyoto", "2026-10-16", "2026-10-19")

    @Test
    fun completedSearchRevealsCoverageAndEditSearchReturnsToTheForm() {
        var state by mutableStateOf(ComparisonUiState(ComparisonDraft.from(query), now = now))
        compose.setContent {
            RoamTheme {
                ComparisonContent(
                    state,
                    today,
                    { if (it == ComparisonIntent.Search) state = state.copy(loading = true) },
                    {},
                    onBack = {},
                    onAirbnbSearch = {},
                )
            }
        }
        scroll("Compare prices")
        click("Compare prices")
        compose.runOnIdle {
            state =
                state.copy(
                    loading = false,
                    result =
                        result(
                            emptyList(),
                            query.kinds().map {
                                ComparisonSourceStatus(it, ComparisonSourceState.NOT_CONFIGURED)
                            },
                        ),
                )
        }
        compose.onNodeWithText("Search coverage").assertIsDisplayed()
        compose.onNodeWithText("Hotels · Live search not connected").assertIsDisplayed()
        click("Edit search")
        compose.onNode(hasSetTextAction() and hasText("Destination")).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(now = now.plusSeconds(15)) }
        compose.onNode(hasSetTextAction() and hasText("Destination")).assertIsDisplayed()
    }

    @Test
    fun searchCriteriaExposeOccupancyCurrencyAndMarket() {
        var draft by mutableStateOf(ComparisonDraft.from(query).copy(childAges = listOf(9)))
        var submitted = false
        compose.setContent {
            RoamTheme {
                ComparisonContent(
                    ComparisonUiState(draft, now = now),
                    today,
                    { intent ->
                        when (intent) {
                            is ComparisonIntent.Destination ->
                                draft = draft.copy(destination = intent.value)
                            is ComparisonIntent.Adults -> draft = draft.copy(adults = intent.value)
                            is ComparisonIntent.Currency ->
                                draft = draft.copy(currency = intent.value)
                            is ComparisonIntent.Market -> draft = draft.copy(market = intent.value)
                            ComparisonIntent.Search -> submitted = true
                            else -> Unit
                        }
                    },
                    {},
                )
            }
        }
        compose
            .onNode(hasSetTextAction() and hasText("Destination"))
            .performTextReplacement("Lisbon")
        compose
            .onNodeWithContentDescription("Increase adults")
            .performSemanticsAction(SemanticsActions.OnClick)
        scroll("Currency")
        click("USD")
        click("EUR")
        click("United States")
        click("United Kingdom")
        scroll("Compare prices")
        click("Compare prices")
        compose.runOnIdle {
            assertEquals("Lisbon", draft.destination)
            assertEquals(3, draft.adults)
            assertEquals(listOf(9), draft.childAges)
            assertEquals("EUR", draft.currency)
            assertEquals("uk", draft.market)
            assertTrue(submitted)
        }
    }

    @Test
    fun nightlyOnlyPriceNeverBecomesAnInventedStayTotal() {
        val property = property(ComparisonPrice(nightly = ComparisonMoney("20", "USD")))
        val result = result(listOf(property))
        compose.setContent {
            RoamTheme {
                ComparisonContent(
                    ComparisonUiState(ComparisonDraft.from(query), result, now = now),
                    today,
                    {},
                    {},
                )
            }
        }
        scroll("Full-stay total unavailable")
        compose.onNodeWithText("Full-stay total unavailable").assertIsDisplayed()
        compose.onNodeWithText("Reported nightly rate $20.00 USD").assertExists()
        compose
            .onNodeWithText("Taxes and fees not confirmed. Extra charges may apply.")
            .assertExists()
        compose.onNodeWithText("$60.00 USD").assertDoesNotExist()
    }

    @Test
    fun oneFailedSourceDoesNotHideSuccessfulHotelResults() {
        val result =
            result(
                listOf(property()),
                listOf(
                    ComparisonSourceStatus(ComparisonKind.HOTEL, ComparisonSourceState.LIVE),
                    ComparisonSourceStatus(ComparisonKind.RENTAL, ComparisonSourceState.TIMEOUT),
                ),
            )
        compose.setContent {
            RoamTheme {
                ComparisonContent(
                    ComparisonUiState(ComparisonDraft.from(query), result, now = now),
                    today,
                    {},
                    {},
                )
            }
        }
        scroll("Search coverage")
        compose.onNodeWithText("Hotels · Results received").assertExists()
        compose.onNodeWithText("Vacation rentals · Source timed out").assertExists()
        scroll("Garden Hotel")
        compose.onNodeWithText("Garden Hotel").assertIsDisplayed()
    }

    @Test
    fun expiredBookingOptionCannotOpenAndOffersRefresh() {
        var opened: String? = null
        var accepted: ComparisonIntent? = null
        val state =
            ComparisonUiState(
                ComparisonDraft.from(query),
                selected = property(),
                details = details(),
                now = now.plusSeconds(300),
            )
        compose.setContent {
            RoamTheme { ComparisonContent(state, today, { accepted = it }, { opened = it }) }
        }
        scroll("View on Partner")
        compose.onNodeWithText("View on Partner").assertIsNotEnabled()
        compose
            .onNodeWithText("Cancellation terms not provided. Check with the booking site.")
            .assertExists()
        scroll("Refresh booking options")
        click("Refresh booking options")
        assertEquals(ComparisonIntent.RefreshDetails, accepted)
        assertNull(opened)
    }

    @Test
    fun freshDetailsShowWholeStayPriceAndOpenTheSelectedProvider() {
        var opened: String? = null
        val state =
            ComparisonUiState(
                ComparisonDraft.from(query),
                selected = property(),
                details = details(),
                now = now,
            )
        compose.setContent { RoamTheme { ComparisonContent(state, today, {}, { opened = it }) } }
        scroll("View on Partner")
        compose.onNodeWithText("$300.00 USD").assertExists()
        compose.onNodeWithText("Reported full-stay total · 3 nights").assertExists()
        compose.onNodeWithText("Taxes and fees reported included.").assertExists()
        compose.onNodeWithText("View on Partner").assertIsEnabled()
        click("View on Partner")
        assertEquals("partner-rate", opened)
    }

    @Test
    fun unconfiguredSourcesAndSeparateAirbnbActionMakeCoverageExplicit() {
        var airbnbOpened = false
        val result =
            result(
                emptyList(),
                query.kinds().map {
                    ComparisonSourceStatus(it, ComparisonSourceState.NOT_CONFIGURED)
                },
            )
        compose.setContent {
            RoamTheme {
                ComparisonContent(
                    ComparisonUiState(ComparisonDraft.from(query), result, now = now),
                    today,
                    {},
                    {},
                    onAirbnbSearch = { airbnbOpened = true },
                )
            }
        }
        scroll("Search Airbnb separately")
        compose
            .onNodeWithText(
                "Airbnb prices are not included. Enter or confirm your dates and guests on Airbnb."
            )
            .assertExists()
        click("Search Airbnb separately")
        assertTrue(airbnbOpened)
        scroll("Search coverage")
        compose.onNodeWithText("Hotels · Live search not connected").assertExists()
        compose.onNodeWithText("Vacation rentals · Live search not connected").assertExists()
        compose.onNodeWithText("Compare booking options").assertDoesNotExist()
    }

    private fun scroll(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    private fun click(text: String) {
        compose
            .onNodeWithText(text)
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun property(price: ComparisonPrice? = null) =
        ComparisonProperty(
            "hotel",
            "hotel-token",
            ComparisonKind.HOTEL,
            "Garden Hotel",
            summaryPrice = price,
        )

    private fun result(
        properties: List<ComparisonProperty>,
        statuses: List<ComparisonSourceStatus> =
            query.kinds().map { ComparisonSourceStatus(it, ComparisonSourceState.LIVE) },
    ) =
        ComparisonResult(
            query,
            now.toString(),
            now.plusSeconds(300).toString(),
            properties,
            statuses,
        )

    private fun details() =
        ComparisonDetails(
            query,
            "hotel-token",
            ComparisonKind.HOTEL,
            now.toString(),
            now.plusSeconds(300).toString(),
            listOf(
                ComparisonRate(
                    "partner-rate",
                    "Partner",
                    price =
                        ComparisonPrice(
                            total = ComparisonMoney("300", "USD"),
                            taxCoverage = TaxCoverage.REPORTED_INCLUDED,
                        ),
                    bookingUrl = "https://partner.example.com/book",
                )
            ),
            listOf(ComparisonSourceStatus(ComparisonKind.HOTEL, ComparisonSourceState.LIVE)),
        )
}
