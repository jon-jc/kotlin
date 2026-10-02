package com.roam.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BookingPolicyTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val request = BookingRequest("one", "kyoto", today.plusDays(7), today.plusDays(10), 2, true)
    @Test fun `quote is exact and credit cannot exceed total`() {
        val quote = BookingPolicy.quote(request, Money(8500), today)
        assertEquals(50400, quote.subtotal.minor)
        assertEquals(4032, quote.serviceFee.minor)
        assertEquals(54432, quote.total.minor)
        assertEquals(45932, quote.due.minor)
        assertEquals(0, BookingPolicy.quote(request, Money(Long.MAX_VALUE), today).due.minor)
    }
    @Test fun `turning credit off preserves the balance`() {
        val quote = BookingPolicy.quote(request.copy(useCredit = false), Money(8500), today)
        assertEquals(0, quote.credit.minor)
        assertEquals(quote.total, quote.due)
    }
    @Test fun `reject invalid dates occupancy keys and inventory`() {
        listOf(
            request.copy(checkIn = today.minusDays(1)),
            request.copy(checkOut = request.checkIn),
            request.copy(checkOut = request.checkIn.minusDays(1)),
            request.copy(checkOut = request.checkIn.plusDays(29)),
            request.copy(checkIn = today.plusYears(1).plusDays(1), checkOut = today.plusYears(1).plusDays(2)),
            request.copy(guests = 0), request.copy(guests = 5), request.copy(key = ""),
            request.copy(key = "a".repeat(101)), request.copy(stayId = "missing"),
        ).forEach { bad -> assertThrows(CommerceException::class.java) { BookingPolicy.quote(bad, Money(0), today) } }
    }
    @Test fun `valid boundary dates include today leap day and 28 nights`() {
        val leap = LocalDate.of(2028, 2, 29)
        assertEquals(28, BookingPolicy.quote(request.copy(checkIn = leap, checkOut = leap.plusDays(28), guests = 4), Money(0), leap).nights)
        assertEquals(1, BookingPolicy.quote(request.copy(checkIn = today, checkOut = today.plusDays(1), guests = 1), Money(0), today).nights)
    }
    @Test fun `money rejects unsupported currency and overflow`() {
        assertThrows(IllegalArgumentException::class.java) { Money(100, "EUR") }
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) + Money(1) }
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE) - Money(1) }
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE).times(2) }
        assertThrows(IllegalArgumentException::class.java) { BookingPolicy.quote(request, Money(-1), today) }
        assertEquals("$1.05", Money(105).formatted())
    }
    @Test fun `profile validates boundaries unicode and control characters`() {
        assertEquals("佐藤 あき", BookingPolicy.profile(" 佐藤 あき ", "京都", "Hello", Profile()).name)
        listOf("", " ", "a", "a".repeat(51), "Alex\nMorgan").forEach { name ->
            assertThrows(CommerceException::class.java) { BookingPolicy.profile(name, "", "", Profile()) }
        }
        assertThrows(CommerceException::class.java) { BookingPolicy.profile("Alex", "x".repeat(81), "", Profile()) }
        assertThrows(CommerceException::class.java) { BookingPolicy.profile("Alex", "", "x".repeat(161), Profile()) }
        assertEquals(160, BookingPolicy.profile("Alex", "", "x".repeat(160), Profile()).bio.length)
    }
}
