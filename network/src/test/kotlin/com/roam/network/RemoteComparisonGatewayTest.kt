package com.roam.network

import com.roam.core.*
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemoteComparisonGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var gateway: RemoteComparisonGateway
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val query =
        ComparisonQuery("Kyoto", "2026-10-09", "2026-10-12", childAges = listOf(3, 14))
    private val wire = Json { encodeDefaults = true }

    @Before
    fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        gateway =
            RemoteComparisonGateway(
                server.loopbackUrl("/").toString(),
                allowLocalHttp = true,
                clock = clock,
            )
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun `public search sends exact occupancy currency and scope without credentials`() =
        runBlocking {
            val expected = result()
            enqueue(wire.encodeToString(expected))
            assertEquals(expected, gateway.search(query))
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/v1/comparison/search", request.path)
            assertEquals(query, wire.decodeFromString<ComparisonQuery>(request.body.readUtf8()))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("apikey"))
            assertNull(request.getHeader("Idempotency-Key"))
        }

    @Test
    fun `partial provider failure retains working properties with explicit coverage`() =
        runBlocking {
            val expected =
                result()
                    .copy(
                        sourceStatuses =
                            listOf(
                                ComparisonSourceStatus(
                                    ComparisonKind.HOTEL,
                                    ComparisonSourceState.LIVE,
                                ),
                                ComparisonSourceStatus(
                                    ComparisonKind.RENTAL,
                                    ComparisonSourceState.TIMEOUT,
                                    "Vacation rentals timed out. Try again.",
                                ),
                            )
                    )
            enqueue(wire.encodeToString(expected))
            val actual = gateway.search(query)
            assertEquals(1, actual.properties.size)
            assertEquals(ComparisonSourceState.TIMEOUT, actual.sourceStatuses.last().status)
            assertNull(actual.properties.single().summaryPrice?.comparableTotal("USD"))
        }

    @Test
    fun `search response must echo all query fields`() = runBlocking {
        listOf(
                query.copy(destination = "Tokyo"),
                query.copy(checkOut = "2026-10-13"),
                query.copy(adults = 3),
                query.copy(childAges = listOf(3, 15)),
                query.copy(market = "uk"),
                query.copy(scope = ComparisonScope.HOTELS),
            )
            .forEach { changed ->
                enqueue(wire.encodeToString(result(changed)))
                assertTrue(
                    runCatching { gateway.search(query) }.exceptionOrNull() is ComparisonException
                )
            }
    }

    @Test
    fun `expired and future dated provider responses are rejected`() = runBlocking {
        val expired =
            result()
                .copy(
                    checkedAt = now.minusSeconds(301).toString(),
                    expiresAt = now.minusSeconds(1).toString(),
                )
        val future =
            result()
                .copy(
                    checkedAt = now.plusSeconds(61).toString(),
                    expiresAt = now.plusSeconds(361).toString(),
                )
        listOf(expired, future).forEach { response ->
            enqueue(wire.encodeToString(response))
            assertTrue(
                runCatching { gateway.search(query) }.exceptionOrNull() is ComparisonException
            )
        }
    }

    @Test
    fun `malformed prices wrong currencies and overflow never reach displayed results`() =
        runBlocking {
            val valid = wire.encodeToString(result())
            listOf(
                    valid.replace("\"amount\":\"320.00\"", "\"amount\":\"-1\""),
                    valid.replace("\"amount\":\"320.00\"", "\"amount\":\"1.001\""),
                    valid.replace("\"amount\":\"320.00\"", "\"amount\":\"92233720368547758.08\""),
                    valid.replace(
                        "\"amount\":\"320.00\",\"currency\":\"USD\"",
                        "\"amount\":\"320.00\",\"currency\":\"EUR\"",
                    ),
                    valid.replace("\"status\":\"LIVE\"", "\"status\":\"NOT_CONFIGURED\""),
                )
                .forEach { malformed ->
                    assertNotEquals(valid, malformed)
                    enqueue(malformed)
                    assertTrue(
                        runCatching { gateway.search(query) }.exceptionOrNull()
                            is ComparisonException
                    )
                }
        }

    @Test
    fun `details match the selected token kind and full search context`() = runBlocking {
        val expected = details()
        enqueue(wire.encodeToString(expected))
        assertEquals(expected, gateway.details(query, "token_1", ComparisonKind.HOTEL))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/comparison/property", request.path)
        assertEquals(
            ComparisonPropertyRequest(query, "token_1", ComparisonKind.HOTEL),
            wire.decodeFromString<ComparisonPropertyRequest>(request.body.readUtf8()),
        )
        assertNull(request.getHeader("Authorization"))
        listOf(
                expected.copy(propertyToken = "other_token"),
                expected.copy(query = query.copy(checkOut = "2026-10-13")),
                expected.copy(
                    kind = ComparisonKind.RENTAL,
                    sourceStatuses =
                        listOf(
                            ComparisonSourceStatus(
                                ComparisonKind.RENTAL,
                                ComparisonSourceState.LIVE,
                            )
                        ),
                ),
            )
            .forEach { changed ->
                enqueue(wire.encodeToString(changed))
                assertTrue(
                    runCatching { gateway.details(query, "token_1", ComparisonKind.HOTEL) }
                        .exceptionOrNull() is ComparisonException
                )
            }
    }

    @Test
    fun `unsafe image and booking links are removed without inventing replacement destinations`() =
        runBlocking {
            enqueue(
                wire.encodeToString(
                    result()
                        .copy(
                            properties =
                                listOf(
                                    property()
                                        .copy(
                                            imageUrl =
                                                "https://serpapi.com/image?api_key=server-secret"
                                        )
                                )
                        )
                )
            )
            assertNull(gateway.search(query).properties.single().imageUrl)
            enqueue(
                wire.encodeToString(
                    details()
                        .copy(
                            rates =
                                listOf(
                                    rate().copy(bookingUrl = "https://user:secret@www.booking.com/")
                                )
                        )
                )
            )
            val response = gateway.details(query, "token_1", ComparisonKind.HOTEL)
            assertNull(response.rates.single().bookingUrl)
            assertEquals(ComparisonMoney("320.00", "USD"), response.rates.single().price?.total)
        }

    @Test
    fun `invalid past search and mismatched detail scope never call upstream`() = runBlocking {
        val past = query.copy(checkIn = "2026-10-01", checkOut = "2026-10-02")
        assertTrue(runCatching { gateway.search(past) }.exceptionOrNull() is ComparisonException)
        assertTrue(
            runCatching {
                    gateway.details(
                        query.copy(scope = ComparisonScope.HOTELS),
                        "token_1",
                        ComparisonKind.RENTAL,
                    )
                }
                .exceptionOrNull() is ComparisonException
        )
        assertTrue(
            runCatching {
                    gateway.details(query, "../secret?api_key=private", ComparisonKind.HOTEL)
                }
                .exceptionOrNull() is ComparisonException
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `provider errors disclose no response diagnostics or credentials`() = runBlocking {
        enqueue(
            """{"code":"QUOTA","message":"api_key=secret-user-query","details":"private"}""",
            429,
        )
        val failure = runCatching { gateway.search(query) }.exceptionOrNull()
        assertTrue(failure is ComparisonException)
        assertEquals("Comparison is busy. Please try again shortly.", failure?.message)
        assertNull(failure?.cause)
        assertFalse(failure.toString().contains("secret-user-query"))
    }

    @Test
    fun `cancellation is preserved while reading a slow comparison body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(wire.encodeToString(result()))
                .setBodyDelay(1, TimeUnit.SECONDS)
        )
        val failure = runCatching { withTimeout(100) { gateway.search(query) } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
    }

    @Test
    fun `comparison endpoint requires HTTPS outside explicit test loopback`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteComparisonGateway("http://www.example.com", allowLocalHttp = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteComparisonGateway("https://user:secret@www.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteComparisonGateway("https://www.example.com?api_key=secret")
        }
    }

    private fun enqueue(body: String, status: Int = 200) {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun property() =
        ComparisonProperty(
            "hotel-1",
            "token_1",
            ComparisonKind.HOTEL,
            "Kyoto stay",
            imageUrl = "https://lh3.googleusercontent.com/photo",
            summaryPrice = ComparisonPrice(total = ComparisonMoney("320.00", "USD")),
        )

    private fun result(query: ComparisonQuery = this.query) =
        ComparisonResult(
            query,
            now.toString(),
            now.plusSeconds(300).toString(),
            listOf(property()),
            query.kinds().map { ComparisonSourceStatus(it, ComparisonSourceState.LIVE) },
        )

    private fun rate() =
        ComparisonRate(
            "rate-1",
            "Booking.com",
            "Double room",
            ComparisonPrice(total = ComparisonMoney("320.00", "USD")),
            "https://www.booking.com/hotel/jp/place.html",
            "Check cancellation terms with the provider.",
        )

    private fun details() =
        ComparisonDetails(
            query,
            "token_1",
            ComparisonKind.HOTEL,
            now.toString(),
            now.plusSeconds(300).toString(),
            listOf(rate()),
            listOf(ComparisonSourceStatus(ComparisonKind.HOTEL, ComparisonSourceState.LIVE)),
        )
}
