package com.roam.network

import com.roam.core.*
import com.roam.core.api.*
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemoteCommerceGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: SupabaseAuth
    private lateinit var sessions: GatewaySessions
    private lateinit var gateway: RemoteCommerceGateway
    private val clock = GatewayClock(Instant.parse("2026-10-02T12:00:00Z"))
    private val user = "65c82baa-3ad6-40ca-bb4f-1e6b83f126b6"
    private val reservationId = "68c09523-cc55-429d-8458-73d16a328e16"
    private val request =
        BookingRequest(
            "stable-reservation-key",
            "kyoto",
            LocalDate.of(2026, 10, 9),
            LocalDate.of(2026, 10, 12),
            2,
            true,
        )
    private val paymentCalls = AtomicInteger()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private var routes: (RecordedRequest) -> MockResponse = { json("{}", 404) }
    private val wire = Json { encodeDefaults = true }
    private val stay =
        ApiStay(
            "kyoto",
            "Original Kyoto stay",
            "Kyoto",
            "Japan",
            "City",
            "16800",
            4,
            "kyoto",
            "A stay",
            listOf("Kitchen"),
            "Asia/Tokyo",
            false,
        )

    @Before
    fun setup() = runBlocking {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded += request
                    return routes(request)
                }
            }
        sessions =
            GatewaySessions(
                AuthSession(user, "access", "refresh", clock.instant().epochSecond + 3600)
            )
        auth =
            SupabaseAuth(
                server.loopbackUrl("/").toString(),
                "sb_publishable_test",
                sessions,
                clock = clock,
                allowLocalHttp = true,
            )
        auth.restore()
        gateway = newGateway()
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun `completed payment UI never confirms a still pending provider reservation`() = runBlocking {
        val quote = quote()
        val pending = reservation(quote, "pending_payment")
        var created = false
        routes = { call ->
            when {
                isLookup(call) -> if (created) result(pending, "pi_secret") else json("{}", 404)
                call.path == "/v1/quotes" -> json(quote)
                call.path == "/v1/reservations" -> {
                    created = true
                    result(pending, "pi_secret")
                }
                call.path == "/v1/reservations/$reservationId" -> result(pending)
                else -> accountRoute(call, listOf(pending))
            }
        }
        gateway.quote(request)
        val outcome = runCatching { gateway.reserve(request) }
        assertTrue(outcome.exceptionOrNull() is CommerceException)
        assertEquals(1, paymentCalls.get())
        assertEquals(1, posts("/v1/reservations"))
        val stored = gateway.snapshots.first().bookings.single()
        assertTrue(stored.paymentPending)
        assertFalse(stored.cancelled)
    }

    @Test
    fun `successful replay recovers receipt without another create or payment presentation`() =
        runBlocking {
            val confirmed = reservation(quote(), "confirmed")
            routes = { call ->
                if (isLookup(call)) result(confirmed) else accountRoute(call, listOf(confirmed))
            }
            val first = gateway.reserve(request)
            val retry = gateway.reserve(request)
            assertEquals(reservationId, first.id)
            assertEquals(first, retry)
            assertEquals(0, posts("/v1/reservations"))
            assertEquals(0, posts("/v1/quotes"))
            assertEquals(0, paymentCalls.get())
        }

    @Test
    fun `confirmation on the final allowed provider check completes the reservation`() =
        runBlocking {
            val pending = reservation(quote(), "pending_payment")
            val confirmed = pending.copy(state = "confirmed")
            val checks = AtomicInteger()
            routes = { call ->
                when {
                    isLookup(call) -> result(pending, "pi_secret")
                    call.path == "/v1/reservations/$reservationId" ->
                        result(if (checks.incrementAndGet() == 2) confirmed else pending)
                    else -> accountRoute(call, listOf(confirmed))
                }
            }
            assertEquals(reservationId, gateway.reserve(request).id)
            assertEquals(2, checks.get())
            assertEquals(1, paymentCalls.get())
            assertEquals(0, posts("/v1/reservations"))
        }

    @Test
    fun `expired local quote cannot silently start a reservation with a replacement price`() =
        runBlocking {
            routes = { call ->
                when {
                    isLookup(call) -> json("{}", 404)
                    call.path == "/v1/quotes" -> json(quote())
                    else -> json("{}", 404)
                }
            }
            gateway.quote(request)
            clock.advance(301)
            assertTrue(
                runCatching { gateway.reserve(request) }.exceptionOrNull() is CommerceException
            )
            assertEquals(0, posts("/v1/reservations"))
            assertEquals(1, posts("/v1/quotes"))
            gateway.quote(request)
            assertEquals(2, posts("/v1/quotes"))
        }

    @Test
    fun `stale quote rejection invalidates cache and requires another reviewed quote`() =
        runBlocking {
            rejectedQuoteNeedsReview("QUOTE_STALE")
        }

    @Test
    fun `server expired quote rejection invalidates cache despite a valid local expiry`() =
        runBlocking {
            rejectedQuoteNeedsReview("QUOTE_EXPIRED")
        }

    @Test
    fun `unbalanced money and changed requests are rejected before caching a quote`() =
        runBlocking {
            val valid = quote()
            val invalid =
                listOf(
                    valid.copy(totalMinor = "54433"),
                    valid.copy(dueMinor = "-1"),
                    valid.copy(currency = "EUR"),
                    valid.copy(request = valid.request.copy(guests = 3)),
                    valid.copy(totalMinor = "9223372036854775808"),
                    valid.copy(nights = 4),
                    valid.copy(stay = valid.stay.copy(id = "a-different-stay")),
                )
            for (response in invalid) {
                routes = { call -> if (isLookup(call)) json("{}", 404) else json(response) }
                val fresh = newGateway()
                assertTrue(runCatching { fresh.quote(request) }.isFailure)
                assertTrue(runCatching { fresh.reserve(request) }.isFailure)
            }
            assertEquals(0, posts("/v1/reservations"))
            assertEquals(0, paymentCalls.get())
        }

    @Test
    fun `changed reservation price is rejected before payment UI receives its secret`() =
        runBlocking {
            val accepted = quote()
            val changed =
                accepted.copy(
                    subtotalMinor = "60000",
                    serviceFeeMinor = "4800",
                    totalMinor = "64800",
                    dueMinor = "56300",
                )
            routes = { call ->
                when {
                    isLookup(call) -> json("{}", 404)
                    call.path == "/v1/quotes" -> json(accepted)
                    call.path == "/v1/reservations" ->
                        result(reservation(changed, "pending_payment"), "pi_secret")
                    else -> accountRoute(call)
                }
            }
            gateway.quote(request)
            assertTrue(runCatching { gateway.reserve(request) }.isFailure)
            assertEquals(0, paymentCalls.get())
        }

    @Test
    fun `reconciliation with another request key cannot present payment`() = runBlocking {
        val mismatched =
            reservation(quote(), "pending_payment").copy(requestKey = "some-other-request-key")
        routes = { call ->
            if (isLookup(call)) result(mismatched, "pi_secret") else accountRoute(call)
        }
        assertTrue(runCatching { gateway.reserve(request) }.isFailure)
        assertEquals(0, paymentCalls.get())
        assertEquals(0, posts("/v1/reservations"))
    }

    @Test
    fun `malformed monetary allocation on a replay is rejected before payment presentation`() =
        runBlocking {
            val malformed = reservation(quote().copy(totalMinor = "54433"), "pending_payment")
            routes = { call ->
                if (isLookup(call)) result(malformed, "pi_secret") else accountRoute(call)
            }
            assertTrue(runCatching { gateway.reserve(request) }.isFailure)
            assertEquals(0, paymentCalls.get())
        }

    @Test
    fun `a new login for the same user rejects late account data from the old session`() =
        runBlocking {
            val accountStarted = CountDownLatch(1)
            val finishAccount = CountDownLatch(1)
            routes = { call ->
                if (call.path == "/v1/account") {
                    accountStarted.countDown()
                    check(finishAccount.await(5, TimeUnit.SECONDS))
                    json(account())
                } else accountRoute(call)
            }
            val refresh = async(Dispatchers.Default) { runCatching { gateway.refresh() } }
            try {
                assertTrue(accountStarted.await(5, TimeUnit.SECONDS))
                sessions.value = sessions.value!!.copy(sessionId = UUID.randomUUID().toString())
                auth.restore()
            } finally {
                finishAccount.countDown()
            }
            assertTrue(refresh.await().exceptionOrNull() is AuthException)
            assertTrue(runCatching { gateway.snapshots.first() }.exceptionOrNull() is AuthException)
            assertEquals(2, recorded.size)
        }

    @Test
    fun `account data must belong to the authenticated user before publication`() = runBlocking {
        routes = { call ->
            if (call.path == "/v1/account") json(account().copy(id = UUID.randomUUID().toString()))
            else accountRoute(call)
        }
        assertTrue(runCatching { gateway.refresh() }.isFailure)
    }

    @Test
    fun `old gateway cannot expose cached account data after another login`() = runBlocking {
        routes = { call -> accountRoute(call) }
        gateway.refresh()
        sessions.value = sessions.value!!.copy(sessionId = UUID.randomUUID().toString())
        auth.restore()
        assertTrue(runCatching { gateway.snapshots.first() }.exceptionOrNull() is AuthException)
        assertTrue(runCatching { gateway.catalog.first() }.exceptionOrNull() is AuthException)
    }

    @Test
    fun `pending cancellation remains visible with original credit and no completed refund claim`() =
        runBlocking {
            val pending = reservation(quote(), "cancel_pending")
            routes = { call -> accountRoute(call, listOf(pending)) }
            gateway.refresh()
            val booking = gateway.snapshots.first().bookings.single()
            assertEquals(reservationId, booking.id)
            assertFalse(booking.cancelled)
            assertTrue(booking.cancellationPending)
            assertEquals(8500, booking.quote.credit.minor)
            assertEquals(0, gateway.snapshots.first().account.balance.minor)
        }

    @Test
    fun `support review prevents another payment presentation`() = runBlocking {
        val review = reservation(quote(), "pending_payment").copy(requiresSupport = true)
        routes = { call ->
            if (isLookup(call)) result(review, "pi_secret") else accountRoute(call, listOf(review))
        }
        assertTrue(runCatching { gateway.reserve(request) }.exceptionOrNull() is CommerceException)
        assertEquals(0, paymentCalls.get())
        assertEquals(0, posts("/v1/reservations"))
    }

    @Test
    fun `failed payments and support reviews remain visible in restored account history`() =
        runBlocking {
            val failed = reservation(quote(), "payment_failed").copy(requiresSupport = true)
            routes = { call -> accountRoute(call, listOf(failed)) }
            gateway.refresh()
            val booking = gateway.snapshots.first().bookings.single()
            assertTrue(booking.paymentFailed)
            assertTrue(booking.requiresSupport)
            assertFalse(booking.cancelled)
            assertFalse(booking.paymentPending)
        }

    private suspend fun rejectedQuoteNeedsReview(code: String) {
        var current = quote()
        var reject = true
        routes = { call ->
            when {
                isLookup(call) -> json("{}", 404)
                call.path == "/v1/quotes" -> {
                    current = quote()
                    json(current)
                }
                call.path == "/v1/reservations" ->
                    if (reject) json(ApiError(code, "Review the price", "request-id"), 409)
                    else result(reservation(current, "confirmed"))
                else ->
                    accountRoute(
                        call,
                        if (reject) emptyList() else listOf(reservation(current, "confirmed")),
                    )
            }
        }
        gateway.quote(request)
        assertTrue(runCatching { gateway.reserve(request) }.exceptionOrNull() is RemoteFailure)
        assertTrue(runCatching { gateway.reserve(request) }.exceptionOrNull() is CommerceException)
        assertEquals(1, posts("/v1/reservations"))
        assertEquals(1, posts("/v1/quotes"))
        gateway.quote(request)
        assertEquals(2, posts("/v1/quotes"))
        reject = false
        assertEquals(reservationId, gateway.reserve(request).id)
        val sent = recorded.last { it.method == "POST" && it.path == "/v1/reservations" }
        assertEquals(request.key, sent.getHeader("Idempotency-Key"))
        assertEquals(
            current.id,
            wire.decodeFromString<ApiReserveRequest>(sent.body.readUtf8()).quoteId,
        )
        assertEquals(0, paymentCalls.get())
    }

    private fun newGateway() =
        RemoteCommerceGateway(
            server.loopbackUrl("/").toString(),
            auth,
            auth.sessions.value!!.sessionId,
            PaymentCoordinator {
                paymentCalls.incrementAndGet()
                PaymentOutcome.Completed
            },
            clock = clock,
            allowLocalHttp = true,
            pollingDelayMillis = 0,
            pollingAttempts = 2,
        )

    private fun quote() =
        ApiQuote(
            UUID.randomUUID().toString(),
            ApiQuoteRequest("kyoto", "2026-10-09", "2026-10-12", 2, true),
            3,
            "50400",
            "4032",
            "54432",
            "8500",
            "45932",
            expiresAt = clock.instant().plusSeconds(300).toString(),
            demo = false,
            stay =
                ApiBookedStay(
                    "kyoto",
                    stay.name,
                    stay.location,
                    stay.country,
                    stay.image,
                    stay.timeZone,
                ),
        )

    private fun reservation(quote: ApiQuote, state: String) =
        ApiReservation(
            reservationId,
            request.key,
            quote,
            state,
            clock.instant().toString(),
            clock.instant().plusSeconds(1800).toString(),
            testMode = true,
        )

    private fun account(reservations: List<ApiReservation> = emptyList()) =
        ApiAccount(user, ApiProfile(), "0", emptyList(), reservations, emptyList())

    private fun accountRoute(
        call: RecordedRequest,
        reservations: List<ApiReservation> = emptyList(),
    ) =
        when (call.path) {
            "/v1/catalog" -> json(ApiCatalog(listOf(stay)))
            "/v1/account" -> json(account(reservations))
            else -> json("{}", 404)
        }

    private fun isLookup(call: RecordedRequest) =
        call.path == "/v1/reservations/by-request/${request.key}"

    private fun posts(path: String) = recorded.count { it.method == "POST" && it.path == path }

    private fun result(reservation: ApiReservation, secret: String? = null) =
        json(ApiReservationResult(reservation, secret))

    private inline fun <reified T> json(value: T, status: Int = 200) =
        json(wire.encodeToString(value), status)

    private fun json(text: String, status: Int = 200) =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(text)
}

private class GatewaySessions(var value: AuthSession?) : SessionStore {
    override suspend fun read() = value

    override suspend fun write(session: AuthSession?) {
        value = session
    }
}

private class GatewayClock(private var value: Instant) : Clock() {
    fun advance(seconds: Long) {
        value = value.plusSeconds(seconds)
    }

    override fun instant() = value

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
}
