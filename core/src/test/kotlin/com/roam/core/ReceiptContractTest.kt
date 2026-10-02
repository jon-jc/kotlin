package com.roam.core

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReceiptContractTest {
    private val request =
        BookingRequest(
            "private-request-key",
            "kyoto",
            LocalDate.of(2026, 10, 9),
            LocalDate.of(2026, 10, 12),
            2,
            true,
        )
    private val booking =
        Booking(
            "demo-confirmation",
            request,
            BookingPolicy.quote(request, Money(8500), LocalDate.of(2026, 10, 2)),
            0,
        )

    @Test
    fun `receipt matches shared golden fixture`() {
        val fixture = javaClass.getResource("/receipt-v1.json")!!.readText()
        assertEquals(
            Json.parseToJsonElement(fixture),
            Json.parseToJsonElement(ReceiptCodec.encode(booking)),
        )
        assertEquals(ReceiptV1.from(booking), ReceiptCodec.decode(fixture))
    }

    @Test
    fun `export excludes private request keys and account fields`() {
        val encoded = ReceiptCodec.encode(booking)
        listOf("private-request-key", "hometown", "bio", "profile", "balance").forEach {
            assertFalse(encoded.contains(it))
        }
        assertTrue(encoded.contains("\"simulated\": true"))
    }

    @Test
    fun `live bookings cannot be mislabeled as simulated receipts`() {
        val live = booking.copy(simulated = false)
        assertThrows(IllegalArgumentException::class.java) { ReceiptV1.from(live) }
        assertThrows(IllegalArgumentException::class.java) { ReceiptCodec.encode(live) }
    }

    @Test
    fun `unresolved and support reservations cannot be exported as confirmed demo receipts`() {
        listOf(
                booking.copy(cancellationPending = true),
                booking.copy(requiresSupport = true),
                booking.copy(paymentPending = true),
                booking.copy(paymentFailed = true),
            )
            .forEach { unresolved ->
                assertThrows(IllegalArgumentException::class.java) {
                    ReceiptCodec.encode(unresolved)
                }
            }
    }

    @Test
    fun `cancelled receipt preserves original allocation and reports refund`() {
        val receipt = ReceiptCodec.decode(ReceiptCodec.encode(booking.copy(cancelled = true)))
        assertEquals(ReceiptStatus.Cancelled, receipt.status)
        assertEquals(Money(8500), receipt.creditReturned.money())
        assertEquals(Money(45932), receipt.simulatedCardAmount.money())
    }

    @Test
    fun `additive fields are forward compatible`() {
        val encoded = ReceiptCodec.encode(booking).replaceFirst("{", "{\"futureField\":true,")
        assertEquals(ReceiptV1.from(booking), ReceiptCodec.decode(encoded))
    }

    @Test
    fun `unknown versions and statuses fail closed`() {
        val valid = ReceiptCodec.encode(booking)
        listOf(
                valid.replace("\"version\": 1", "\"version\": 2"),
                valid.replace("confirmed", "charged"),
                valid.replace("\"simulated\": true", "\"simulated\": false"),
                valid.replace("\"simulated\": true,", ""),
            )
            .forEach { bad ->
                assertThrows(IllegalArgumentException::class.java) { ReceiptCodec.decode(bad) }
            }
    }

    @Test
    fun `invalid amounts and inconsistent totals fail closed`() {
        listOf("-1", "1.5", "01", "NaN", "9223372036854775808").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { MoneyV1(bad).money() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptCodec.decode(ReceiptCodec.encode(booking).replace("54432", "54433"))
        }
        assertThrows(IllegalArgumentException::class.java) { MoneyV1("1", "EUR").money() }
    }

    @Test
    fun `decimal strings preserve values above javascript safe integer`() {
        assertEquals(9007199254740993L, MoneyV1("9007199254740993").money().minor)
    }
}
