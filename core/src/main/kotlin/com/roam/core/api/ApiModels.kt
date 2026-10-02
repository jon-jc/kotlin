package com.roam.core.api

import kotlinx.serialization.Serializable

/** All money is exact USD minor units encoded as decimal strings. Times are UTC ISO-8601. */
@Serializable data class ApiError(val code: String, val message: String, val requestId: String)

@Serializable
data class ApiStay(
    val id: String,
    val name: String,
    val location: String,
    val country: String,
    val category: String,
    val nightlyMinor: String,
    val maxGuests: Int,
    val image: String,
    val description: String,
    val amenities: List<String>,
    val timeZone: String,
    val demo: Boolean,
)

@Serializable data class ApiCatalog(val stays: List<ApiStay>)

@Serializable
data class ApiBookedStay(
    val id: String,
    val name: String,
    val location: String,
    val country: String,
    val image: String,
    val timeZone: String,
)

@Serializable
data class ApiProfile(
    val name: String = "Traveler",
    val hometown: String = "",
    val bio: String = "",
    val shareHometown: Boolean = false,
    val shareActivity: Boolean = false,
    val revision: Long = 0,
)

@Serializable
data class ApiLedgerEntry(
    val id: String,
    val title: String,
    val amountMinor: String,
    val createdAt: String,
    val reservationId: String? = null,
)

@Serializable
data class ApiAccount(
    val id: String,
    val profile: ApiProfile,
    val balanceMinor: String,
    val savedStayIds: List<String>,
    val reservations: List<ApiReservation>,
    val ledger: List<ApiLedgerEntry>,
)

@Serializable
data class ApiQuoteRequest(
    val stayId: String,
    val checkIn: String,
    val checkOut: String,
    val guests: Int,
    val useCredit: Boolean,
)

@Serializable
data class ApiQuote(
    val id: String,
    val request: ApiQuoteRequest,
    val nights: Long,
    val subtotalMinor: String,
    val serviceFeeMinor: String,
    val totalMinor: String,
    val creditMinor: String,
    val dueMinor: String,
    val currency: String = "USD",
    val expiresAt: String,
    val demo: Boolean,
    val stay: ApiBookedStay,
)

@Serializable data class ApiReserveRequest(val quoteId: String)

/** State is pending_payment, confirmed, cancel_pending, cancelled, or payment_failed. */
@Serializable
data class ApiReservation(
    val id: String,
    val requestKey: String,
    val quote: ApiQuote,
    val state: String,
    val createdAt: String,
    val paymentDeadline: String,
    val creditReturnedMinor: String = "0",
    val testMode: Boolean,
    val stay: ApiBookedStay = quote.stay,
    val requiresSupport: Boolean = false,
)

@Serializable
data class ApiReservationResult(
    val reservation: ApiReservation,
    val paymentClientSecret: String? = null,
)

@Serializable data class ApiOk(val ok: Boolean = true)
