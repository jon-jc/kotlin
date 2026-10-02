package com.roam.app

import androidx.lifecycle.SavedStateHandle
import com.roam.core.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-10-02T12:00:00Z"))
    private val today = LocalDate.of(2026, 10, 2)
    private lateinit var gateway: FakeGateway
    private lateinit var saved: SavedStateHandle
    private lateinit var model: ComparisonViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway(clock)
        saved = SavedStateHandle()
        model = ComparisonViewModel(gateway, saved, clock)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blank launch and partial destination edits are safe and never call provider`() =
        runTest(dispatcher) {
            assertEquals("", model.state.value.query.destination)
            assertNull(model.state.value.result)
            model.accept(ComparisonIntent.Search)
            assertNotNull(model.state.value.error)
            model.accept(ComparisonIntent.Destination("L"))
            model.accept(ComparisonIntent.Search)
            model.accept(ComparisonIntent.Destination(""))
            model.accept(ComparisonIntent.Search)
            advanceUntilIdle()
            assertEquals(0, gateway.searches.size)
            assertEquals("", model.state.value.query.destination)
            model.accept(ComparisonIntent.Destination("x".repeat(150)))
            assertEquals(120, model.state.value.query.destination.length)
        }

    @Test
    fun `search trims destination only when submitting a validated draft`() =
        runTest(dispatcher) {
            model.accept(ComparisonIntent.Destination("  Kyoto  "))
            assertEquals("  Kyoto  ", model.state.value.query.destination)
            model.accept(ComparisonIntent.Search)
            advanceUntilIdle()
            assertEquals("Kyoto", gateway.searches.single().destination)
            assertEquals("Kyoto", saved.get<String>("comparison.destination"))
            assertNotNull(model.state.value.result)
        }

    @Test
    fun `past and overlong date ranges do not issue charged requests`() =
        runTest(dispatcher) {
            model.accept(ComparisonIntent.Destination("Kyoto"))
            model.accept(ComparisonIntent.Dates(today.minusDays(1), today.plusDays(2)))
            model.accept(ComparisonIntent.Search)
            assertNotNull(model.state.value.error)
            model.accept(ComparisonIntent.Dates(today.plusDays(1), today.plusDays(30)))
            model.accept(ComparisonIntent.Search)
            advanceUntilIdle()
            assertTrue(gateway.searches.isEmpty())
            assertNotNull(model.state.value.error)
        }

    @Test
    fun `criteria restore but provider results links and detail selection do not`() =
        runTest(dispatcher) {
            model.accept(ComparisonIntent.Destination("Lisbon"))
            model.accept(ComparisonIntent.Adults(4))
            model.accept(ComparisonIntent.AddChild)
            model.accept(ComparisonIntent.ChildAge(0, 9))
            model.accept(ComparisonIntent.Currency("EUR"))
            model.accept(ComparisonIntent.Market("uk"))
            model.accept(ComparisonIntent.Scope(ComparisonScope.HOTELS))
            model.accept(ComparisonIntent.Search)
            advanceUntilIdle()
            model.accept(ComparisonIntent.OpenProperty("Lisbon-hotel"))
            advanceUntilIdle()
            val restored = ComparisonViewModel(gateway, saved, clock)
            assertEquals(model.state.value.query, restored.state.value.query)
            assertNull(restored.state.value.result)
            assertNull(restored.state.value.details)
            assertNull(restored.state.value.selected)
            assertEquals(8, saved.keys().size)
            assertTrue(saved.keys().all { it.startsWith("comparison.") })
        }

    @Test
    fun `late uncancellable search success cannot replace newer trip results`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<Unit>()
            gateway.searchBlock = { query ->
                if (query.destination == "Old city") withContext(NonCancellable) { old.await() }
                gateway.result(query)
            }
            search("Old city")
            runCurrent()
            search("New city")
            runCurrent()
            assertEquals("New city", model.state.value.result?.query?.destination)
            old.complete(Unit)
            advanceUntilIdle()
            assertEquals("New city", model.state.value.result?.query?.destination)
            assertNull(model.state.value.error)
        }

    @Test
    fun `late uncancellable search failure cannot surface over newer results`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<Unit>()
            gateway.searchBlock = { query ->
                if (query.destination == "Old city")
                    withContext(NonCancellable) {
                        old.await()
                        throw ComparisonException("Old provider failed")
                    }
                gateway.result(query)
            }
            search("Old city")
            runCurrent()
            search("New city")
            runCurrent()
            old.complete(Unit)
            advanceUntilIdle()
            assertEquals("New city", model.state.value.result?.query?.destination)
            assertNull(model.state.value.error)
            assertFalse(model.state.value.loading)
        }

    @Test
    fun `late details from previous search are discarded even when cancellation is ignored`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            gateway.detailsBlock = { query, token, kind ->
                withContext(NonCancellable) { gate.await() }
                gateway.detailsResult(query, token, kind)
            }
            search("Kyoto")
            runCurrent()
            model.accept(ComparisonIntent.OpenProperty("Kyoto-hotel"))
            runCurrent()
            search("Lisbon")
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("Lisbon", model.state.value.result?.query?.destination)
            assertNull(model.state.value.selected)
            assertNull(model.state.value.details)
            assertNull(model.state.value.detailsError)
        }

    @Test
    fun `closing details suppresses late provider errors`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            gateway.detailsBlock = { _, _, _ ->
                withContext(NonCancellable) {
                    gate.await()
                    throw ComparisonException("Late failure")
                }
            }
            search("Kyoto")
            runCurrent()
            model.accept(ComparisonIntent.OpenProperty("Kyoto-hotel"))
            runCurrent()
            model.accept(ComparisonIntent.CloseDetails)
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(model.state.value.selected)
            assertNull(model.state.value.detailsError)
        }

    @Test
    fun `booking handoff rechecks five minute expiry without relying on screen timer`() =
        runTest(dispatcher) {
            search("Kyoto")
            advanceUntilIdle()
            model.accept(ComparisonIntent.OpenProperty("Kyoto-hotel"))
            advanceUntilIdle()
            clock.value = clock.value.plusSeconds(299)
            assertEquals("https://booking.example.com/stay", model.bookingUrl("rate"))
            clock.value = clock.value.plusSeconds(1)
            assertNull(model.bookingUrl("rate"))
            assertFalse(model.state.value.detailsFresh)
            assertTrue(model.state.value.detailsError.orEmpty().contains("expired"))
        }

    @Test
    fun `total sort puts uncertain fees and nightly only offers after complete totals`() {
        val query =
            ComparisonQuery("Kyoto", "2026-10-16", "2026-10-19", scope = ComparisonScope.HOTELS)
        val properties =
            listOf(
                property("unknown", ComparisonPrice(total = ComparisonMoney("1", "USD"))),
                property("nightly", ComparisonPrice(nightly = ComparisonMoney("2", "USD"))),
                property(
                    "high",
                    ComparisonPrice(
                        total = ComparisonMoney("200", "USD"),
                        taxCoverage = TaxCoverage.REPORTED_INCLUDED,
                    ),
                ),
                property(
                    "low",
                    ComparisonPrice(
                        total = ComparisonMoney("100", "USD"),
                        taxCoverage = TaxCoverage.REPORTED_INCLUDED,
                    ),
                ),
            )
        val result =
            ComparisonResult(
                query,
                clock.instant().toString(),
                clock.instant().plusSeconds(300).toString(),
                properties,
                liveStatuses(query),
            )
        val state =
            ComparisonUiState(
                ComparisonDraft.from(query),
                result = result,
                sort = ComparisonSort.TOTAL,
                now = clock.instant(),
            )
        assertEquals(listOf("low", "high", "unknown", "nightly"), state.properties.map { it.id })
        assertNull(
            comparisonTotal(
                ComparisonPrice(
                    total = ComparisonMoney("10", "EUR"),
                    taxCoverage = TaxCoverage.REPORTED_INCLUDED,
                ),
                "USD",
            )
        )
    }

    @Test
    fun `wrong query response fails closed without displaying provider internals`() =
        runTest(dispatcher) {
            gateway.searchBlock = { query ->
                gateway.result(query.copy(destination = "Another trip"))
            }
            search("Kyoto")
            advanceUntilIdle()
            assertNull(model.state.value.result)
            assertEquals("We couldn't compare prices. Please try again.", model.state.value.error)
        }

    @Test
    fun `unconfigured comparison returns coverage without fabricated prices`() =
        runTest(dispatcher) {
            val unavailable =
                ComparisonViewModel(UnavailableComparisonGateway(clock), SavedStateHandle(), clock)
            unavailable.accept(ComparisonIntent.Destination("Kyoto"))
            unavailable.accept(ComparisonIntent.Search)
            advanceUntilIdle()
            val result = requireNotNull(unavailable.state.value.result)
            assertTrue(result.properties.isEmpty())
            assertTrue(
                result.sourceStatuses.all { it.status == ComparisonSourceState.NOT_CONFIGURED }
            )
        }

    @Test
    fun `guest edits enforce supported occupancy and preserve explicit child ages`() {
        model.accept(ComparisonIntent.Adults(100))
        repeat(6) { model.accept(ComparisonIntent.AddChild) }
        model.accept(ComparisonIntent.ChildAge(0, 100))
        assertEquals(6, model.state.value.query.adults)
        assertEquals(listOf(17, 1, 1, 1), model.state.value.query.childAges)
        model.accept(ComparisonIntent.RemoveChild(0))
        assertEquals(listOf(1, 1, 1), model.state.value.query.childAges)
    }

    private fun search(destination: String) {
        model.accept(ComparisonIntent.Destination(destination))
        model.accept(ComparisonIntent.Search)
    }

    private class MutableClock(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = value
    }

    private class FakeGateway(private val clock: Clock) : ComparisonGateway {
        val searches = mutableListOf<ComparisonQuery>()
        var searchBlock: suspend (ComparisonQuery) -> ComparisonResult = { result(it) }
        var detailsBlock: suspend (ComparisonQuery, String, ComparisonKind) -> ComparisonDetails =
            { query, token, kind ->
                detailsResult(query, token, kind)
            }

        override suspend fun search(query: ComparisonQuery): ComparisonResult {
            searches += query
            return searchBlock(query)
        }

        override suspend fun details(
            query: ComparisonQuery,
            propertyToken: String,
            kind: ComparisonKind,
        ) = detailsBlock(query, propertyToken, kind)

        fun result(query: ComparisonQuery) =
            ComparisonResult(
                query,
                clock.instant().toString(),
                clock.instant().plusSeconds(300).toString(),
                listOf(property("${query.destination}-hotel")),
                liveStatuses(query),
            )

        fun detailsResult(query: ComparisonQuery, token: String, kind: ComparisonKind) =
            ComparisonDetails(
                query,
                token,
                kind,
                clock.instant().toString(),
                clock.instant().plusSeconds(300).toString(),
                listOf(
                    ComparisonRate(
                        "rate",
                        "Booking site",
                        price = ComparisonPrice(total = ComparisonMoney("300", query.currency)),
                        bookingUrl = "https://booking.example.com/stay",
                    )
                ),
                listOf(ComparisonSourceStatus(kind, ComparisonSourceState.LIVE)),
            )
    }

    companion object {
        private fun property(id: String, price: ComparisonPrice? = null) =
            ComparisonProperty(
                id,
                id.replace(' ', '_'),
                ComparisonKind.HOTEL,
                "A real hotel",
                summaryPrice = price,
            )

        private fun liveStatuses(query: ComparisonQuery) =
            query.kinds().map { ComparisonSourceStatus(it, ComparisonSourceState.LIVE) }
    }
}
