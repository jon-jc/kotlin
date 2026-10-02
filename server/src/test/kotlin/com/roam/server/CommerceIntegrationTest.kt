package com.roam.server

import com.roam.core.api.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CommerceIntegrationTest {
    private lateinit var database: Database
    private lateinit var payments: TestPayments
    private lateinit var clock: TestClock
    private lateinit var commerce: Commerce
    private val alice = UUID.randomUUID().toString()
    private val bob = UUID.randomUUID().toString()

    @Before
    fun setup() {
        database = testDatabase()
        database.transaction { it.exec("TRUNCATE accounts,stays,webhook_events CASCADE") }
        database.seedDemo()
        payments = TestPayments()
        clock = TestClock(Instant.parse("2026-10-02T12:00:00Z"))
        commerce = Commerce(database, payments, false, clock)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `real account has no fictional funds and profile updates use revisions`() {
        val account = commerce.account(alice)
        assertEquals("0", account.balanceMinor)
        assertFalse(account.profile.shareHometown)
        assertTrue(account.ledger.isEmpty())
        val updated =
            commerce.profile(
                alice,
                account.profile.copy(name = "Alice Example", hometown = "Kyoto"),
            )
        assertEquals(1L, updated.revision)
        assertFailure(412) { commerce.profile(alice, account.profile.copy(name = "Another Name")) }
        assertEquals("Traveler", commerce.account(bob).profile.name)
    }

    @Test
    fun `quote expiry and ownership are enforced before a reservation exists`() {
        val quote = quote()
        assertFailure(404) { commerce.reserve(bob, key(), ApiReserveRequest(quote.id)) }
        clock.advance(301)
        assertFailure(409, "QUOTE_EXPIRED") {
            commerce.reserve(alice, key(), ApiReserveRequest(quote.id))
        }
        assertTrue(commerce.account(alice).reservations.isEmpty())
    }

    @Test
    fun `retry survives expiry changed key payload fails and one quote cannot book twice`() {
        val quote = quote()
        val key = key()
        val result = commerce.reserve(alice, key, ApiReserveRequest(quote.id))
        assertEquals("pending_payment", result.reservation.state)
        assertNotNull(result.paymentClientSecret)
        clock.advance(301)
        val retry = commerce.reserve(alice, key, ApiReserveRequest(quote.id))
        assertEquals(result.reservation.id, retry.reservation.id)
        val otherQuote = quote("coast")
        assertFailure(409, "IDEMPOTENCY_CONFLICT") {
            commerce.reserve(alice, key, ApiReserveRequest(otherQuote.id))
        }
        assertEquals(1, payments.created.get())
        assertFailure(404) { commerce.reservation(bob, result.reservation.id) }
        assertFailure(404) { commerce.byRequest(bob, key) }
    }

    @Test
    fun `same quote cannot be reused with another request key`() {
        val quote = quote()
        commerce.reserve(alice, key(), ApiReserveRequest(quote.id))
        assertFailure(409, "QUOTE_CONSUMED") {
            commerce.reserve(alice, key(), ApiReserveRequest(quote.id))
        }
    }

    @Test
    fun `concurrent same request is durable exactly once`() {
        val quote = quote()
        val key = key()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results =
                executor
                    .invokeAll(
                        (1..4).map {
                            Callable {
                                commerce
                                    .reserve(alice, key, ApiReserveRequest(quote.id))
                                    .reservation
                                    .id
                            }
                        }
                    )
                    .map { it.get() }
            assertEquals(1, results.toSet().size)
            assertEquals(1, commerce.account(alice).reservations.size)
            assertEquals(1, payments.created.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `competing accounts cannot reserve overlapping nights while adjacent nights remain free`() {
        val first = quote()
        val second = commerce.quote(bob, request())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                executor
                    .invokeAll(
                        listOf(
                            Callable {
                                runCatching {
                                    commerce.reserve(alice, key(), ApiReserveRequest(first.id))
                                }
                            },
                            Callable {
                                runCatching {
                                    commerce.reserve(bob, key(), ApiReserveRequest(second.id))
                                }
                            },
                        )
                    )
                    .map { it.get() }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(
                "UNAVAILABLE",
                (results.single { it.isFailure }.exceptionOrNull() as ApiFailure).code,
            )
            assertNotNull(
                commerce.quote(
                    alice,
                    request().copy(checkIn = "2026-10-13", checkOut = "2026-10-15"),
                )
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `accepted credit is immutable and stale balance never increases charge`() {
        fund(alice, 10000)
        val quote = quote()
        assertEquals("10000", quote.creditMinor)
        database.transaction {
            it.exec("UPDATE accounts SET balance=0 WHERE id=?", UUID.fromString(alice))
        }
        assertFailure(409, "QUOTE_STALE") {
            commerce.reserve(alice, key(), ApiReserveRequest(quote.id))
        }
        assertEquals(0, payments.created.get())
        assertTrue(commerce.account(alice).reservations.isEmpty())
    }

    @Test
    fun `Stripe success confirms only canonical matching payment and refund returns original credit once`() {
        fund(alice, 8500)
        val result = commerce.reserve(alice, key(), ApiReserveRequest(quote().id))
        val id = result.reservation.id
        payments.succeed(id)
        commerce.webhook(event(id, "evt_paid"))
        commerce.webhook(event(id, "evt_paid"))
        assertEquals("confirmed", commerce.reservation(alice, id).reservation.state)
        val cancelled = commerce.cancel(alice, id).reservation
        assertEquals("cancelled", cancelled.state)
        assertEquals("8500", cancelled.creditReturnedMinor)
        commerce.cancel(alice, id)
        assertEquals("8500", commerce.account(alice).balanceMinor)
        assertEquals(2, commerce.account(alice).ledger.size)
        assertEquals(1, payments.refunded.get())
        assertNotNull(quote())
    }

    @Test
    fun `abandoned checkout cancels provider before inventory or credit are released`() {
        fund(alice, 5000)
        val result = commerce.reserve(alice, key(), ApiReserveRequest(quote().id))
        clock.advance(1801)
        payments.failCancel = true
        assertEquals(1, commerce.reconcilePending())
        assertEquals("0", commerce.account(alice).balanceMinor)
        assertFailure(409, "UNAVAILABLE") { quote() }
        payments.failCancel = false
        clock.advance(31)
        assertEquals(0, commerce.reconcilePending())
        assertEquals(
            "cancelled",
            commerce.reservation(alice, result.reservation.id).reservation.state,
        )
        assertEquals("5000", commerce.account(alice).balanceMinor)
        assertNotNull(quote())
    }

    @Test
    fun `lost provider response recovers one payment after application restart`() {
        val quote = quote()
        val key = key()
        payments.loseCreateResponse = true
        assertFailure(503) { commerce.reserve(alice, key, ApiReserveRequest(quote.id)) }
        val newService = Commerce(database, payments, false, clock)
        val result = newService.byRequest(alice, key)
        assertNotNull(result.paymentClientSecret)
        assertEquals(1, payments.created.get())
        assertEquals(1, newService.account(alice).reservations.size)
    }

    @Test
    fun `unknown payment creation older than provider dedupe window fails closed`() {
        val quote = quote()
        val key = key()
        payments.loseCreateResponse = true
        assertFailure(503) { commerce.reserve(alice, key, ApiReserveRequest(quote.id)) }
        clock.advance(24 * 3600)
        assertFailure(503, "PAYMENT_REVIEW_REQUIRED") { commerce.byRequest(alice, key) }
        assertEquals(1, payments.created.get())
    }

    @Test
    fun `receipt display survives catalog removal and persistent database reopen`() {
        val result = commerce.reserve(alice, key(), ApiReserveRequest(quote().id))
        database.transaction { it.exec("UPDATE stays SET active=false WHERE id='kyoto'") }
        database.close()
        database = testDatabase()
        val snapshot = Commerce(database, payments, false, clock).account(alice)
        assertEquals("The quiet side of Kyoto", snapshot.reservations.single().stay.name)
        assertEquals(result.reservation.id, snapshot.reservations.single().id)
    }

    @Test
    fun `live processor refuses fictional inventory`() {
        val live = Commerce(database, payments, true, clock)
        assertFailure(503, "DEMO_INVENTORY") { live.quote(alice, request()) }
        assertEquals(0, payments.created.get())
    }

    @Test
    fun `more than one hundred indeterminate payments cannot starve newer work`() {
        val valid = commerce.reserve(alice, key(), ApiReserveRequest(quote().id)).reservation
        payments.succeed(valid.id)
        database.transaction { c ->
            repeat(101) {
                val quoteId = UUID.randomUUID()
                val id = UUID.randomUUID()
                c.exec(
                    "INSERT INTO quotes(id,account_id,payload,expires_at) SELECT ?,account_id,payload,expires_at FROM quotes WHERE id=?",
                    quoteId,
                    UUID.fromString(valid.quote.id),
                )
                c.exec(
                    "INSERT INTO reservations(id,account_id,request_key,quote_id,quote,state,created_at,payment_deadline,test_mode,next_reconcile_at) SELECT ?,account_id,?,?,quote,'pending_payment',?,?,true,? FROM reservations WHERE id=?",
                    id,
                    key(),
                    quoteId,
                    java.sql.Timestamp.from(clock.instant().minusSeconds(86400)),
                    java.sql.Timestamp.from(clock.instant().minusSeconds(80000)),
                    java.sql.Timestamp.from(clock.instant().minusSeconds(3600)),
                    UUID.fromString(valid.id),
                )
            }
        }
        assertEquals(100, commerce.reconcilePending())
        assertEquals(1, commerce.reconcilePending())
        assertEquals("confirmed", commerce.reservation(alice, valid.id).reservation.state)
        assertEquals(
            101,
            database.transaction { c ->
                c.one("SELECT count(*) FROM reservations WHERE review_required") { it.getInt(1) }
            },
        )
    }

    @Test
    fun `known signed refund recovers a lost response after provider idempotency expires`() {
        fund(alice, 8500)
        val reservation = commerce.reserve(alice, key(), ApiReserveRequest(quote().id)).reservation
        payments.succeed(reservation.id)
        commerce.reservation(alice, reservation.id)
        payments.loseRefundResponse = true
        assertFailure(503) { commerce.cancel(alice, reservation.id) }
        clock.advance(86400)
        commerce.webhook(refundEvent(reservation.id, "evt_refundlate"))
        assertEquals("cancelled", commerce.reservation(alice, reservation.id).reservation.state)
        assertEquals("8500", commerce.account(alice).balanceMinor)
        assertEquals(1, payments.refunded.get())
    }

    @Test
    fun `external partial refund requires support without pretending the stay was cancelled`() {
        val reservation = commerce.reserve(alice, key(), ApiReserveRequest(quote().id)).reservation
        payments.succeed(reservation.id)
        commerce.reservation(alice, reservation.id)
        payments.refund("pi_${reservation.id}", reservation.id, 100)
        commerce.webhook(refundEvent(reservation.id, "evt_externalrefund"))
        val updated = commerce.reservation(alice, reservation.id).reservation
        assertTrue(updated.requiresSupport)
        assertEquals("confirmed", updated.state)
        assertFailure(409, "UNAVAILABLE") { quote() }
    }

    @Test
    fun `stale worker review cannot replace a webhook recovered refund or confirmed payment`() {
        val reservation = commerce.reserve(alice, key(), ApiReserveRequest(quote().id)).reservation
        payments.succeed(reservation.id)
        commerce.reservation(alice, reservation.id)
        commerce.markReview(reservation.id, "PAYMENT_REVIEW_REQUIRED")
        assertFalse(commerce.reservation(alice, reservation.id).reservation.requiresSupport)
        commerce.cancel(alice, reservation.id)
        // This represents the late failure callback from a worker holding the pre-webhook row.
        commerce.markReview(reservation.id, "REFUND_REVIEW_REQUIRED", null)
        commerce.markReview(reservation.id, "REFUND_REVIEW_REQUIRED", "re_${reservation.id}")
        val recovered = commerce.reservation(alice, reservation.id).reservation
        assertEquals("cancelled", recovered.state)
        assertFalse(recovered.requiresSupport)
    }

    @Test
    fun `known payment webhook clears only its obsolete unknown creation review`() {
        val quote = quote()
        val key = key()
        payments.loseCreateResponse = true
        assertFailure(503) { commerce.reserve(alice, key, ApiReserveRequest(quote.id)) }
        val reservation = commerce.account(alice).reservations.single()
        clock.advance(86400)
        commerce.reconcilePending()
        assertTrue(commerce.account(alice).reservations.single().requiresSupport)
        // Even after the dedupe window, signed provider metadata identifies the original intent.
        commerce.webhook(event(reservation.id, "evt_knownlate"))
        assertFalse(commerce.reservation(alice, reservation.id).reservation.requiresSupport)
        assertEquals("cancelled", commerce.reservation(alice, reservation.id).reservation.state)
        assertEquals(1, payments.created.get())
    }

    @Test
    fun `shutdown stops between records and unprocessed claims resume after their lease`() {
        val first = commerce.reserve(alice, key(), ApiReserveRequest(quote().id)).reservation
        val second =
            commerce.reserve(alice, key(), ApiReserveRequest(quote("coast").id)).reservation
        payments.succeed(first.id)
        payments.succeed(second.id)
        val checks = AtomicInteger()
        // One check before claiming, one before the first record, then shutdown is requested.
        assertEquals(0, commerce.reconcilePending { checks.incrementAndGet() <= 2 })
        val intermediate = commerce.account(alice).reservations
        assertEquals(1, intermediate.count { it.state == "confirmed" })
        assertEquals(1, intermediate.count { it.state == "pending_payment" })
        assertEquals(0, commerce.reconcilePending { false })
        clock.advance(121)
        val restarted = Commerce(database, payments, false, clock)
        assertEquals(0, restarted.reconcilePending())
        assertTrue(restarted.account(alice).reservations.all { it.state == "confirmed" })
        assertEquals(2, payments.created.get())
    }

    private fun quote(stay: String = "kyoto") = commerce.quote(alice, request(stay))

    private fun request(stay: String = "kyoto") =
        ApiQuoteRequest(stay, "2026-10-10", "2026-10-13", 2, true)

    private fun key() = UUID.randomUUID().toString()

    private fun fund(account: String, amount: Long) {
        commerce.account(account)
        database.transaction {
            it.exec("UPDATE accounts SET balance=? WHERE id=?", amount, UUID.fromString(account))
        }
    }

    private fun event(id: String, eventId: String) = buildJsonObject {
        put("id", eventId)
        put("type", "payment_intent.succeeded")
        putJsonObject("data") {
            putJsonObject("object") {
                put("id", "pi_$id")
                putJsonObject("metadata") { put("reservation_id", id) }
            }
        }
    }

    private fun refundEvent(id: String, eventId: String) = buildJsonObject {
        put("id", eventId)
        put("type", "refund.updated")
        putJsonObject("data") {
            putJsonObject("object") {
                put("id", "re_$id")
                put("payment_intent", "pi_$id")
                putJsonObject("metadata") { put("reservation_id", id) }
            }
        }
    }
}

internal fun testDatabase() =
    Database(
        System.getenv("ROAM_TEST_DATABASE_URL"),
        System.getenv("ROAM_TEST_DATABASE_USER"),
        System.getenv("ROAM_TEST_DATABASE_PASSWORD"),
    )

internal fun assertFailure(status: Int, code: String? = null, action: () -> Any?) {
    try {
        action()
        fail("Expected HTTP $status")
    } catch (failure: ApiFailure) {
        assertEquals(status, failure.status)
        if (code != null) assertEquals(code, failure.code)
    }
}

internal class TestClock(private var value: Instant) : Clock() {
    fun advance(seconds: Long) {
        value = value.plusSeconds(seconds)
    }

    override fun instant() = value

    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
}

internal class TestPayments : Payments {
    private val intents = ConcurrentHashMap<String, ProviderIntent>()
    private val refunds = ConcurrentHashMap<String, ProviderRefund>()
    val created = AtomicInteger()
    val refunded = AtomicInteger()
    var failCancel = false
    var loseCreateResponse = false
    var loseRefundResponse = false

    override fun create(reservationId: String, amount: Long): ProviderIntent {
        val result =
            intents.computeIfAbsent("pi_$reservationId") {
                created.incrementAndGet()
                ProviderIntent(
                    it,
                    "${it}_secret_test",
                    "requires_payment_method",
                    amount,
                    "usd",
                    reservationId,
                    false,
                )
            }
        if (loseCreateResponse) {
            loseCreateResponse = false
            throw ApiFailure(503, "PAYMENT_UNAVAILABLE", "Lost response")
        }
        return result
    }

    override fun retrieve(id: String) = intents[id] ?: error("Missing test payment")

    override fun cancel(id: String, reservationId: String): ProviderIntent {
        if (failCancel) throw ApiFailure(503, "PAYMENT_UNAVAILABLE", "Offline")
        return intents.compute(id) { _, value ->
            requireNotNull(value)
            if (value.status == "succeeded") value
            else value.copy(status = "canceled", clientSecret = null)
        }!!
    }

    override fun refund(id: String, reservationId: String, amount: Long): ProviderRefund {
        val refund =
            refunds.computeIfAbsent("re_$reservationId") {
                refunded.incrementAndGet()
                ProviderRefund(it, "succeeded", amount, id)
            }
        if (loseRefundResponse) {
            loseRefundResponse = false
            throw ApiFailure(503, "PAYMENT_UNAVAILABLE", "Lost refund response")
        }
        return refund
    }

    override fun retrieveRefund(id: String) = refunds[id]!!

    fun succeed(reservationId: String) {
        intents.compute("pi_$reservationId") { _, value ->
            value!!.copy(status = "succeeded", clientSecret = null, receivedAmount = value.amount)
        }
    }
}
