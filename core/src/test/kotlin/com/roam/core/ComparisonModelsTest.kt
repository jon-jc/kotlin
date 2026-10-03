package com.roam.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ComparisonModelsTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val query = ComparisonQuery("Kyoto", "2026-10-09", "2026-10-12")

    @Test
    fun `query supports exact date and occupancy boundaries for one room`() {
        query
            .copy(
                checkIn = today.toString(),
                checkOut = today.plusDays(28).toString(),
                adults = 6,
                childAges = listOf(1, 17, 7, 7),
            )
            .validate(today)
        query
            .copy(
                checkIn = today.plusDays(365).toString(),
                checkOut = today.plusDays(366).toString(),
            )
            .validate(today)
        assertEquals(listOf(ComparisonKind.HOTEL, ComparisonKind.RENTAL), query.kinds())
        assertEquals(
            listOf(ComparisonKind.RENTAL),
            query.copy(scope = ComparisonScope.RENTALS).kinds(),
        )
    }

    @Test
    fun `invalid destinations dates occupancy currencies and market fail closed`() {
        val invalid =
            listOf<() -> ComparisonQuery>(
                { query.copy(destination = " ") },
                { query.copy(destination = " Kyoto") },
                { query.copy(destination = "Kyo\nto") },
                { query.copy(destination = "x".repeat(121)) },
                { query.copy(checkIn = "2026-10-32") },
                { query.copy(checkIn = "2026-1-09") },
                { query.copy(checkOut = query.checkIn) },
                { query.copy(checkOut = "2026-11-07") },
                { query.copy(adults = 0) },
                { query.copy(adults = 7) },
                { query.copy(childAges = listOf(0)) },
                { query.copy(childAges = listOf(18)) },
                { query.copy(childAges = List(5) { 8 }) },
                { query.copy(currency = "usd") },
                { query.copy(currency = "CHF") },
                { query.copy(market = "US") },
                { query.copy(market = "xx") },
                { query.copy(market = "../") },
                {
                    query.copy(checkIn = today.minusDays(1).toString(), checkOut = today.toString())
                },
                {
                    query.copy(
                        checkIn = today.plusDays(366).toString(),
                        checkOut = today.plusDays(367).toString(),
                    )
                },
            )
        invalid.forEach { create ->
            assertThrows(ComparisonException::class.java) { create().validate(today) }
        }
    }

    @Test
    fun `money keeps exact minor units and currency exponents`() {
        assertEquals(12345L, ComparisonMoney("123.45", "USD").minorUnits())
        assertEquals(12340L, ComparisonMoney("123.4", "EUR").minorUnits())
        assertEquals(123L, ComparisonMoney("123", "JPY").minorUnits())
        assertEquals(Long.MAX_VALUE, ComparisonMoney("92233720368547758.07", "USD").minorUnits())
        assertEquals("$123.45", ComparisonMoney("123.45", "USD").formatted(Locale.US))
        assertFalse(ComparisonMoney("123", "JPY").formatted(Locale.US).contains(".00"))
        listOf("-1", "NaN", "Infinity", "1e2", "+1", "01", "1.001", "92233720368547758.08")
            .forEach { value ->
                assertThrows(ComparisonException::class.java) { ComparisonMoney(value, "USD") }
            }
        assertThrows(ComparisonException::class.java) { ComparisonMoney("123.5", "JPY") }
    }

    @Test
    fun `nightly estimates and unknown taxes never become complete comparable totals`() {
        val nightly = ComparisonMoney("100.00", "USD")
        assertNull(
            ComparisonPrice(nightly = nightly, taxCoverage = TaxCoverage.REPORTED_INCLUDED)
                .comparableTotal("USD")
        )
        val total = ComparisonMoney("320.00", "USD")
        assertNull(ComparisonPrice(total = total).comparableTotal("USD"))
        assertNull(
            ComparisonPrice(total = total, taxCoverage = TaxCoverage.REPORTED_INCLUDED)
                .comparableTotal("EUR")
        )
        assertEquals(
            total,
            ComparisonPrice(total = total, taxCoverage = TaxCoverage.REPORTED_INCLUDED)
                .comparableTotal("USD"),
        )
        assertThrows(ComparisonException::class.java) { ComparisonPrice() }
        assertThrows(ComparisonException::class.java) {
            ComparisonPrice(total, ComparisonMoney("100.00", "EUR"))
        }
    }

    @Test
    fun `serialization preserves exact price and requested child ages`() {
        val result = result(query.copy(childAges = listOf(3, 14)), listOf(property()))
        val wire = Json { encodeDefaults = true }
        val serialized = wire.encodeToString(result)
        assertTrue(serialized.contains("\"amount\":\"320.00\""))
        assertEquals(result, wire.decodeFromString<ComparisonResult>(serialized))
    }

    @Test
    fun `response rejects mismatched currency duplicate properties missing statuses and excessive freshness`() {
        val property = property()
        assertThrows(ComparisonException::class.java) {
            result(properties = listOf(property, property))
        }
        assertThrows(ComparisonException::class.java) {
            result(
                properties =
                    listOf(
                        property.copy(
                            summaryPrice = ComparisonPrice(total = ComparisonMoney("100", "EUR"))
                        )
                    )
            )
        }
        assertThrows(ComparisonException::class.java) {
            result().copy(sourceStatuses = emptyList())
        }
        assertThrows(ComparisonException::class.java) {
            result(properties = listOf(property))
                .copy(
                    sourceStatuses =
                        query.kinds().map {
                            ComparisonSourceStatus(it, ComparisonSourceState.NOT_CONFIGURED)
                        }
                )
        }
        assertThrows(ComparisonException::class.java) { result().copy(expiresAt = now.toString()) }
        assertThrows(ComparisonException::class.java) {
            result().copy(expiresAt = now.plusSeconds(3601).toString())
        }
        assertThrows(ComparisonException::class.java) { result().copy(checkedAt = "not-a-time") }
        assertThrows(ComparisonException::class.java) {
            result(query.copy(scope = ComparisonScope.RENTALS), listOf(property))
        }
        assertThrows(ComparisonException::class.java) {
            property.copy(propertyToken = "../../search?api_key=secret")
        }
        assertThrows(ComparisonException::class.java) { property.copy(rating = Double.NaN) }
    }

    @Test
    fun `public provider links stay intact while unsafe destinations and credentials are rejected`() {
        listOf(
                "https://www.airbnb.com/",
                "https://www.booking.com/hotel/jp/place.html?checkin=2026-10-09&checkout=2026-10-12",
                "https://www.google.com/travel/hotels/entity/token",
            )
            .forEach { link -> assertEquals(link, ComparisonLinks.safeBookingUrl(link)) }
        listOf(
                "http://www.booking.com/",
                "javascript:alert(1)",
                "intent://booking/",
                "file:///tmp/token",
                "https://user:secret@www.booking.com/",
                "https://www.booking.com:8443/",
                "https://127.0.0.1/",
                "https://127.1/",
                "https://2130706433/",
                "https://[::1]/",
                "https://localhost/",
                "https://machine.local/",
                "https://machine.localdomain/",
                "https://localhost.localdomain/",
                "https://home.arpa/",
                "https://router.home.arpa/",
                "https://machine.internal/",
                "https://serpapi.com/search",
                "https://api.serpapi.com/search",
                "https://www.booking.com/?api_key=secret",
                "https://www.booking.com/?%61pi_key=secret",
                "https://www.booking.com/?api%255fkey=secret",
                "https://www.booking.com/?%25252561pi_key=secret",
                "https://www.booking.com/?url=https%3A%2F%2Fuser%3Asecret%40www.booking.com%2F",
                "https://www.booking.com/#access_token=secret",
                "https://www.booking.com\\@localhost/",
                " https://www.booking.com/",
                "https://www.booking.com/%0d%0a?api_key=secret",
                "https://www.booking.com/%0d%0a",
            )
            .forEach { link -> assertNull(link, ComparisonLinks.safeBookingUrl(link)) }
        assertNull(ComparisonLinks.safeImageUrl("https://serpapi.com/image?api_key=secret"))
        assertEquals(
            "https://lh3.googleusercontent.com/photo",
            ComparisonLinks.safeImageUrl("https://lh3.googleusercontent.com/photo"),
        )
    }

    @Test
    fun `unconfigured gateway reports missing coverage without example prices`(): Unit =
        runBlocking {
            val gateway = UnavailableComparisonGateway(Clock.fixed(now, ZoneOffset.UTC))
            val results = gateway.search(query)
            assertTrue(results.properties.isEmpty())
            assertEquals(
                setOf(ComparisonKind.HOTEL, ComparisonKind.RENTAL),
                results.sourceStatuses.map { it.kind }.toSet(),
            )
            assertTrue(
                results.sourceStatuses.all { it.status == ComparisonSourceState.NOT_CONFIGURED }
            )
            val detail = gateway.details(query, "valid_token", ComparisonKind.RENTAL)
            assertTrue(detail.rates.isEmpty())
            assertEquals(
                ComparisonSourceState.NOT_CONFIGURED,
                detail.sourceStatuses.single().status,
            )
            assertThrows(ComparisonException::class.java) {
                ComparisonPropertyRequest(
                    query.copy(scope = ComparisonScope.HOTELS),
                    "token",
                    ComparisonKind.RENTAL,
                )
            }
        }

    private fun property() =
        ComparisonProperty(
            "hotel-1",
            "token_1",
            ComparisonKind.HOTEL,
            "Kyoto stay",
            summaryPrice = ComparisonPrice(total = ComparisonMoney("320.00", "USD")),
        )

    private fun result(
        query: ComparisonQuery = this.query,
        properties: List<ComparisonProperty> = emptyList(),
    ) =
        ComparisonResult(
            query,
            now.toString(),
            now.plusSeconds(300).toString(),
            properties,
            query.kinds().map { ComparisonSourceStatus(it, ComparisonSourceState.LIVE) },
        )
}
