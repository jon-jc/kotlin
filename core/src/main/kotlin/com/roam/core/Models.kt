package com.roam.core

import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale

/** Amounts cross every boundary as integer minor units; arithmetic fails closed on overflow. */
data class Money(val minor: Long, val currency: String = "USD") {
    init {
        require(currency == "USD") { "Only USD is supported by this catalog" }
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency)
        return copy(minor = Math.addExact(minor, other.minor))
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency)
        return copy(minor = Math.subtractExact(minor, other.minor))
    }

    fun times(count: Long) = copy(minor = Math.multiplyExact(minor, count))

    fun formatted(locale: Locale = Locale.US): String =
        NumberFormat.getCurrencyInstance(locale)
            .apply { currency = Currency.getInstance(this@Money.currency) }
            .format(java.math.BigDecimal.valueOf(minor, 2))
}

data class Stay(
    val id: String,
    val name: String,
    val location: String,
    val country: String,
    val category: String,
    val nightly: Money,
    val maxGuests: Int,
    val rating: String,
    val reviews: Int,
    val image: String,
    val host: String,
    val description: String,
    val amenities: List<String>,
    val latitude: Double,
    val longitude: Double,
)

object Catalog {
    val stays =
        listOf(
            Stay(
                "kyoto",
                "The quiet side of Kyoto",
                "Higashiyama, Kyoto",
                "Japan",
                "City",
                Money(16800),
                4,
                "4.98",
                128,
                "kyoto",
                "Aiko",
                "A restored machiya tucked into a lantern-lit lane. Open the cedar doors to a private garden, slow mornings, and a city that rewards curiosity.",
                listOf("Private garden", "Fast Wi-Fi", "Kitchen", "Self check-in"),
                34.998,
                135.778,
            ),
            Stay(
                "alpine",
                "A little closer to the sky",
                "Dolomites, South Tyrol",
                "Italy",
                "Nature",
                Money(21400),
                2,
                "4.96",
                86,
                "alpine",
                "Luca",
                "Wake above the clouds in a timber cabin framed by the Dolomites. A place for long walks, wood-fired evenings, and absolutely no rush.",
                listOf("Mountain views", "Fireplace", "Breakfast", "Free parking"),
                46.498,
                11.354,
            ),
            Stay(
                "coast",
                "Where the coast slows down",
                "Ericeira, Lisbon",
                "Portugal",
                "Coast",
                Money(13200),
                3,
                "4.95",
                104,
                "coast",
                "Inês",
                "Salt air, linen sheets, and the Atlantic at your doorstep. A sunlit coastal hideaway minutes from the village's bakeries and surf breaks.",
                listOf("Ocean views", "Beach access", "Fast Wi-Fi", "Kitchen"),
                38.963,
                -9.417,
            ),
        )

    fun find(id: String) =
        stays.firstOrNull { it.id == id }
            ?: throw CommerceException("This stay is no longer available.")
}

data class BookingRequest(
    val key: String,
    val stayId: String,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val guests: Int,
    val useCredit: Boolean,
)

data class Quote(
    val nights: Long,
    val subtotal: Money,
    val serviceFee: Money,
    val total: Money,
    val credit: Money,
    val due: Money,
) {
    init {
        require(nights in 1..28) { "A quote must cover 1 to 28 nights" }
        require(listOf(subtotal, serviceFee, total, credit, due).all { it.minor >= 0 }) {
            "Quote amounts cannot be negative"
        }
        require(subtotal + serviceFee == total) { "Quote total does not balance" }
        require(credit + due == total) { "Quote allocation does not balance" }
    }
}

/** Display facts accepted with the booking, independent of later catalog changes. */
data class BookedStay(
    val name: String,
    val location: String,
    val country: String,
    val image: String,
) {
    init {
        require(name.isNotBlank()) { "A booked stay needs a display name" }
    }

    companion object {
        fun from(stay: Stay) = BookedStay(stay.name, stay.location, stay.country, stay.image)

        /** Frozen v1 fixture metadata. Do not resolve old bookings against the current catalog. */
        fun legacy(stayId: String): BookedStay =
            when (stayId) {
                "kyoto" ->
                    BookedStay("The quiet side of Kyoto", "Higashiyama, Kyoto", "Japan", "kyoto")
                "alpine" ->
                    BookedStay(
                        "A little closer to the sky",
                        "Dolomites, South Tyrol",
                        "Italy",
                        "alpine",
                    )
                "coast" ->
                    BookedStay(
                        "Where the coast slows down",
                        "Ericeira, Lisbon",
                        "Portugal",
                        "coast",
                    )
                else -> BookedStay("Unavailable stay", "Location unavailable", "", "")
            }
    }
}

data class Booking(
    val id: String,
    val request: BookingRequest,
    val quote: Quote,
    val createdAt: Long,
    val cancelled: Boolean = false,
    val stay: BookedStay = BookedStay.legacy(request.stayId),
    val simulated: Boolean = true,
    val cancellationPending: Boolean = false,
    val requiresSupport: Boolean = false,
    val paymentPending: Boolean = false,
    val paymentFailed: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 100) { "A valid confirmation is required" }
        require(request.key.isNotBlank() && request.key.length <= 100) {
            "A valid reservation key is required"
        }
        require(request.stayId.isNotBlank()) { "A stay identifier is required" }
        require(request.guests in 1..20) { "Invalid historical guest count" }
        require(ChronoUnit.DAYS.between(request.checkIn, request.checkOut) == quote.nights) {
            "Booking dates do not match its quote"
        }
        require(request.useCredit || quote.credit.minor == 0L) {
            "Credit was applied without being requested"
        }
    }
}

data class LedgerEntry(
    val id: String,
    val title: String,
    val amount: Money,
    val createdAt: Long,
    val bookingId: String? = null,
)

data class Profile(
    val name: String = "Alex Morgan",
    val hometown: String = "San Francisco, CA",
    val bio: String = "Collecting moments, not things.",
    val shareHometown: Boolean = true,
    val shareActivity: Boolean = false,
)

data class Account(
    val profile: Profile = Profile(),
    val balance: Money = Money(8500),
    val saved: Set<String> = emptySet(),
    val redeemed: Set<String> = emptySet(),
) {
    init {
        require(balance.minor >= 0) { "Wallet balance cannot be negative" }
    }
}

data class AccountSnapshot(
    val account: Account = Account(),
    val bookings: List<Booking> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
)

open class CommerceException(message: String) : IllegalArgumentException(message)

object BookingPolicy {
    fun quote(request: BookingRequest, balance: Money, today: LocalDate): Quote {
        val stay = Catalog.find(request.stayId)
        if (request.key.isBlank() || request.key.length > 100)
            throw CommerceException("A valid reservation key is required.")
        if (request.checkIn.isBefore(today))
            throw CommerceException("Choose a check-in date from today onward.")
        if (request.checkIn.isAfter(today.plusYears(1)))
            throw CommerceException("Choose a stay within the next year.")
        val nights = ChronoUnit.DAYS.between(request.checkIn, request.checkOut)
        if (nights !in 1..28) throw CommerceException("Choose a stay of 1 to 28 nights.")
        if (request.guests !in 1..stay.maxGuests)
            throw CommerceException("This stay welcomes 1 to ${stay.maxGuests} guests.")
        require(balance.minor >= 0) { "Wallet balance cannot be negative" }
        val subtotal = stay.nightly.times(nights)
        // Fixed 8% fee, rounded half up to the nearest cent. No device-locale dependency.
        val fee = Money(Math.addExact(Math.multiplyExact(subtotal.minor, 8), 50) / 100)
        val total = subtotal + fee
        val credit = Money(if (request.useCredit) minOf(balance.minor, total.minor) else 0)
        return Quote(nights, subtotal, fee, total, credit, total - credit)
    }

    fun profile(name: String, hometown: String, bio: String, current: Profile): Profile {
        val cleanName = name.trim()
        if (cleanName.length !in 2..50)
            throw CommerceException("Use a name between 2 and 50 characters.")
        if (hometown.trim().length > 80 || bio.trim().length > 160)
            throw CommerceException("Keep hometown under 81 and bio under 161 characters.")
        if (listOf(cleanName, hometown, bio).any { text -> text.any { it.isISOControl() } })
            throw CommerceException("Please remove control characters.")
        return current.copy(name = cleanName, hometown = hometown.trim(), bio = bio.trim())
    }
}
