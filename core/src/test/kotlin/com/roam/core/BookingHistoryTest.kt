package com.roam.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BookingHistoryTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val request =
        BookingRequest("history", "kyoto", today.plusDays(7), today.plusDays(10), 2, true)
    private val quote = BookingPolicy.quote(request, Money(8500), today)

    @Test
    fun `booked metadata does not change with the catalog source`() {
        val original = Catalog.find("kyoto")
        val snapshot = BookedStay.from(original)
        val renamed = original.copy(name = "Replacement name", country = "Replacement country")
        assertEquals("The quiet side of Kyoto", snapshot.name)
        assertEquals("Japan", snapshot.country)
        assertNotEquals(BookedStay.from(renamed), snapshot)
    }

    @Test
    fun `unknown historical stays do not need active inventory`() {
        val booking = Booking("confirmation", request.copy(stayId = "removed-stay"), quote, 0)
        assertEquals("Unavailable stay", booking.stay.name)
        assertEquals("", booking.stay.country)
        assertEquals("removed-stay", ReceiptCodec.decode(ReceiptCodec.encode(booking)).stayId)
        assertEquals(quote, booking.copy(cancelled = true).quote)
    }

    @Test
    fun `invalid historical amounts fail before a refund can use them`() {
        assertThrows(IllegalArgumentException::class.java) { quote.copy(credit = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { quote.copy(total = Money(1)) }
        assertThrows(IllegalArgumentException::class.java) { quote.copy(due = Money(0)) }
        assertThrows(IllegalArgumentException::class.java) { quote.copy(nights = 0) }
        assertThrows(ArithmeticException::class.java) {
            Quote(1, Money(Long.MAX_VALUE), Money(1), Money(0), Money(0), Money(0))
        }
        assertThrows(IllegalArgumentException::class.java) { Account(balance = Money(-1)) }
    }

    @Test
    fun `historical validation does not recompute accepted prices or apply todays dates`() {
        val oldRequest = request.copy(checkIn = today.minusDays(10), checkOut = today.minusDays(7))
        val accepted = Quote(3, Money(10000), Money(123), Money(10123), Money(500), Money(9623))
        val booking = Booking("old-confirmation", oldRequest, accepted, 0)
        assertEquals(accepted, booking.quote)
        assertEquals(Money(10123), ReceiptCodec.decode(ReceiptCodec.encode(booking)).total.money())
    }

    @Test
    fun `booking dates and credit preference must match the stored allocation`() {
        assertThrows(IllegalArgumentException::class.java) {
            Booking("confirmation", request.copy(checkOut = request.checkOut.plusDays(1)), quote, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Booking("confirmation", request.copy(useCredit = false), quote, 0)
        }
    }
}
