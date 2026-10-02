package com.roam.network

import com.roam.core.*
import com.roam.core.api.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

enum class PaymentOutcome {
    Completed,
    Canceled,
    Failed,
}

/** Implemented by the Android Stripe SDK boundary. Client completion never confirms a booking. */
fun interface PaymentCoordinator {
    suspend fun present(clientSecret: String): PaymentOutcome
}

/** One instance per authenticated login. No real-account data is written into the demo database. */
class RemoteCommerceGateway(
    baseUrl: String,
    private val auth: SupabaseAuth,
    private val sessionId: String,
    private val payments: PaymentCoordinator,
    private val http: JsonHttp = JsonHttp(),
    private val clock: Clock = Clock.systemDefaultZone(),
    allowLocalHttp: Boolean = false,
    private val pollingDelayMillis: Long = 1000,
    private val pollingAttempts: Int = 20,
) : CommerceGateway {
    init {
        require(pollingAttempts in 0..60 && pollingDelayMillis in 0..5000)
    }

    private val base = JsonHttp.baseUrl(baseUrl, allowLocalHttp)
    private val refreshMutex = Mutex()
    private val quoteMutex = Mutex()
    private val accountData = MutableStateFlow<AccountSnapshot?>(null)
    private val catalogData = MutableStateFlow<List<Stay>?>(null)
    private val acceptedQuotes = mutableMapOf<String, Pair<BookingRequest, ApiQuote>>()
    private var profileRevision = 0L

    override val isDemo = false
    override val snapshots: Flow<AccountSnapshot> = flow {
        initialize()
        emitAll(accountData.filterNotNull().onEach { requireSession() })
    }
    override val catalog: Flow<List<Stay>> = flow {
        initialize()
        emitAll(catalogData.filterNotNull().onEach { requireSession() })
    }

    override fun today(): LocalDate = LocalDate.now(clock)

    private suspend fun initialize() =
        refreshMutex.withLock {
            requireSession()
            if (accountData.value == null || catalogData.value == null) refreshLocked()
        }

    override suspend fun refresh() = refreshMutex.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        val catalog = http.json.decodeFromString<ApiCatalog>(request("v1/catalog"))
        val account = http.json.decodeFromString<ApiAccount>(request("v1/account"))
        requireSession()
        require(account.id == auth.sessions.value?.userId) {
            "Account response does not match the signed-in account"
        }
        val snapshot = account.toSnapshot()
        val stays = catalog.stays.map(ApiStay::toStay)
        profileRevision = account.profile.revision
        if (accountData.value?.account?.balance != snapshot.account.balance) {
            quoteMutex.withLock { acceptedQuotes.clear() }
        }
        catalogData.value = stays
        accountData.value = snapshot
    }

    override suspend fun quote(request: BookingRequest): Quote =
        quoteMutex.withLock {
            requireSession()
            val existing = lookup(request.key)
            if (existing != null) {
                requireRequestMatches(request, existing.reservation.quote.request)
                acceptedQuotes[request.key] = request to existing.reservation.quote
                return@withLock existing.reservation.quote.toQuote()
            }
            val cached = acceptedQuotes[request.key]
            if (
                cached != null &&
                    cached.first == request &&
                    Instant.parse(cached.second.expiresAt).isAfter(clock.instant().plusSeconds(5))
            ) {
                return@withLock cached.second.toQuote()
            }
            val quote =
                http.json.decodeFromString<ApiQuote>(
                    request("v1/quotes", "POST", http.json.encodeToString(request.toApi()))
                )
            requireRequestMatches(request, quote.request)
            val result = quote.toQuote()
            requireSession()
            acceptedQuotes[request.key] = request to quote
            // Drafts are short-lived; cap memory even if a user adjusts dates repeatedly.
            while (acceptedQuotes.size > 16) acceptedQuotes.remove(acceptedQuotes.keys.first())
            result
        }

    override suspend fun reserve(request: BookingRequest): Booking {
        requireSession()
        var result = lookup(request.key)
        if (result == null) {
            val accepted = quoteMutex.withLock { acceptedQuotes[request.key] }
            if (accepted == null || accepted.first != request)
                throw CommerceException("Review a current price before paying.")
            // Never silently replace an expired or changed accepted price during confirmation.
            if (!Instant.parse(accepted.second.expiresAt).isAfter(clock.instant()))
                throw CommerceException(
                    "This price expired. Return to the stay and review a new price."
                )
            result =
                try {
                    http.json.decodeFromString(
                        request(
                            "v1/reservations",
                            "POST",
                            http.json.encodeToString(ApiReserveRequest(accepted.second.id)),
                            mapOf("Idempotency-Key" to request.key),
                        )
                    )
                } catch (failure: RemoteFailure) {
                    if (failure.code in setOf("QUOTE_STALE", "QUOTE_EXPIRED"))
                        quoteMutex.withLock { acceptedQuotes.remove(request.key) }
                    if (failure.status >= 500) refreshQuietly()
                    throw failure
                } catch (failure: java.io.IOException) {
                    refreshQuietly()
                    throw failure
                }
            require(requireNotNull(result).reservation.quote == accepted.second) {
                "Reservation price does not match the accepted quote"
            }
        }
        val resolved = requireNotNull(result)
        require(resolved.reservation.requestKey == request.key) {
            "Reservation does not match the request key"
        }
        requireRequestMatches(request, resolved.reservation.quote.request)
        var reservation = resolved.reservation
        // Validate recovered quotes as rigorously as newly accepted ones, before opening Stripe.
        reservation.toBooking()
        if (reservation.requiresSupport) {
            refreshQuietly()
            throw CommerceException(
                "This reservation needs review. Refresh Trips for its support reference."
            )
        }
        val clientSecret = resolved.paymentClientSecret
        if (reservation.state == "pending_payment" && clientSecret != null) {
            refreshQuietly()
            when (payments.present(clientSecret)) {
                PaymentOutcome.Canceled -> {
                    refreshQuietly()
                    throw CommerceException(
                        "The payment screen closed. Check this reservation in Trips before retrying."
                    )
                }
                PaymentOutcome.Failed -> {
                    refreshQuietly()
                    throw CommerceException(
                        "The payment screen couldn't finish. Check its status in Trips before paying again."
                    )
                }
                PaymentOutcome.Completed -> Unit
            }
        }
        repeat(pollingAttempts + 1) { attempt ->
            if (reservation.requiresSupport) {
                refresh()
                throw CommerceException(
                    "This reservation needs review. Refresh Trips for its support reference."
                )
            }
            when (reservation.state) {
                "confirmed",
                "cancelled" -> {
                    refresh()
                    return reservation.toBooking()
                }
                "payment_failed" -> {
                    refreshQuietly()
                    throw CommerceException(
                        "This payment did not complete. Return to the stay to review a new reservation."
                    )
                }
                "cancel_pending" -> {
                    refreshQuietly()
                    throw CommerceException(
                        "Cancellation is being processed. Refresh your trips to check its status."
                    )
                }
                "pending_payment" -> Unit
                else ->
                    throw CommerceException(
                        "This reservation status isn't supported. Please contact support."
                    )
            }
            if (attempt < pollingAttempts) {
                delay(pollingDelayMillis)
                reservation =
                    http.json
                        .decodeFromString<ApiReservationResult>(
                            request("v1/reservations/${safeSegment(reservation.id)}")
                        )
                        .reservation
                require(
                    reservation.requestKey == request.key &&
                        reservation.id == resolved.reservation.id &&
                        reservation.quote == resolved.reservation.quote
                ) {
                    "Reservation response changed unexpectedly"
                }
            }
        }
        refresh()
        throw CommerceException(
            "Your payment is still being checked. Retry safely to recover this reservation; you won't be charged twice."
        )
    }

    override suspend fun cancel(key: String): Booking {
        val previous =
            lookup(key)?.reservation
                ?: throw CommerceException("This reservation couldn't be found.")
        var reservation =
            http.json
                .decodeFromString<ApiReservationResult>(
                    request(
                        "v1/reservations/${safeSegment(previous.id)}/cancel",
                        "POST",
                        "{}",
                        mapOf("Idempotency-Key" to "cancel-${previous.id}"),
                    )
                )
                .reservation
        repeat(pollingAttempts + 1) { attempt ->
            require(
                reservation.id == previous.id &&
                    reservation.requestKey == key &&
                    reservation.quote == previous.quote
            ) {
                "Cancellation response changed unexpectedly"
            }
            if (reservation.requiresSupport) {
                refresh()
                throw CommerceException(
                    "This refund needs review. Your reservation reference remains available in Trips."
                )
            }
            if (reservation.state == "cancelled") {
                refresh()
                return reservation.toBooking()
            }
            if (reservation.state != "cancel_pending")
                throw CommerceException(
                    "The reservation could not be cancelled. Refresh and try again."
                )
            if (attempt < pollingAttempts) {
                delay(pollingDelayMillis)
                reservation =
                    http.json
                        .decodeFromString<ApiReservationResult>(
                            request("v1/reservations/${safeSegment(previous.id)}")
                        )
                        .reservation
            }
        }
        refresh()
        throw CommerceException(
            "Your refund is being processed. Refresh your trips to check its status."
        )
    }

    override suspend fun redeem(benefit: String): Unit =
        throw CommerceException("No funded benefits are available for this account.")

    private suspend fun refreshQuietly() {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            /* Keep the original recoverable operation error. */
        }
    }

    override suspend fun toggleSaved(id: String) {
        val saved =
            accountData.value?.account?.saved
                ?: throw CommerceException("Refresh your account before saving a stay.")
        request(
            "v1/saved/${safeSegment(id)}",
            if (id in saved) "DELETE" else "PUT",
            if (id in saved) null else "{}",
        )
        refresh()
    }

    override suspend fun editProfile(name: String, hometown: String, bio: String) {
        val old =
            accountData.value?.account?.profile
                ?: throw CommerceException("Refresh your profile and try again.")
        val valid = BookingPolicy.profile(name, hometown, bio, old)
        saveProfile(valid)
    }

    override suspend fun setPrivacy(hometown: Boolean, activity: Boolean) {
        val old =
            accountData.value?.account?.profile
                ?: throw CommerceException("Refresh your profile and try again.")
        saveProfile(old.copy(shareHometown = hometown, shareActivity = activity))
    }

    private suspend fun saveProfile(profile: Profile) {
        request(
            "v1/profile",
            "PUT",
            http.json.encodeToString(
                ApiProfile(
                    profile.name,
                    profile.hometown,
                    profile.bio,
                    profile.shareHometown,
                    profile.shareActivity,
                    profileRevision,
                )
            ),
        )
        refresh()
    }

    private suspend fun lookup(key: String): ApiReservationResult? =
        try {
            http.json
                .decodeFromString<ApiReservationResult>(
                    request("v1/reservations/by-request/${safeSegment(key)}")
                )
                .also {
                    require(it.reservation.requestKey == key) {
                        "Reservation does not match the request key"
                    }
                }
        } catch (failure: RemoteFailure) {
            if (failure.status == 404) null else throw failure
        }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
    ): String {
        requireSession()
        var token = auth.accessToken()
        requireSession()
        val url = base.newBuilder().addPathSegments(path).build()
        try {
            val response =
                try {
                    http.request(url, method, body, extra + ("Authorization" to "Bearer $token"))
                } catch (failure: HttpFailure) {
                    if (failure.status != 401) throw failure
                    token = auth.accessToken(token)
                    requireSession()
                    http.request(url, method, body, extra + ("Authorization" to "Bearer $token"))
                }
            requireSession()
            return response
        } catch (failure: HttpFailure) {
            throw RemoteFailure(failure.status, failure.code)
        }
    }

    private fun requireSession() {
        if (auth.sessions.value?.sessionId != sessionId)
            throw AuthException("Please sign in again.")
    }

    private fun safeSegment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid resource identifier" }
        return value
    }

    private fun requireRequestMatches(request: BookingRequest, actual: ApiQuoteRequest) {
        require(request.toApi() == actual) { "Reservation does not match the accepted request" }
    }
}

class RemoteFailure(val status: Int, val code: String?) :
    CommerceException(
        when (code) {
            "QUOTE_EXPIRED",
            "QUOTE_STALE" ->
                "This price changed or expired. Return to the stay and review a new price."
            "UNAVAILABLE" -> "These dates are no longer available. Choose different dates."
            "PROFILE_CHANGED" -> "Your profile changed elsewhere. Refresh before editing again."
            "TOO_MANY_PENDING" ->
                "Complete or cancel an existing pending reservation in Trips before booking again."
            "CANCELLATION_CLOSED" ->
                "The cancellation window has closed. Contact support with your reservation reference."
            else ->
                when (status) {
                    429 -> "Please wait before trying again."
                    401,
                    403 -> "Please sign in again."
                    else -> "The service couldn't complete this request. Please try again."
                }
        }
    )

private fun BookingRequest.toApi() =
    ApiQuoteRequest(stayId, checkIn.toString(), checkOut.toString(), guests, useCredit)

private fun minor(value: String, signed: Boolean = false): Money {
    require(value.matches(Regex(if (signed) "-?(0|[1-9][0-9]*)" else "0|[1-9][0-9]*")))
    return Money(value.toLong())
}

internal fun ApiQuote.toQuote(): Quote {
    require(currency == "USD")
    require(stay.id == request.stayId)
    require(
        java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.parse(request.checkIn),
            LocalDate.parse(request.checkOut),
        ) == nights
    )
    require(request.useCredit || minor(creditMinor).minor == 0L)
    return Quote(
        nights,
        minor(subtotalMinor),
        minor(serviceFeeMinor),
        minor(totalMinor),
        minor(creditMinor),
        minor(dueMinor),
    )
}

internal fun ApiReservation.toBooking(): Booking {
    require(
        state in
            setOf(
                "confirmed",
                "cancel_pending",
                "cancelled",
                "pending_payment",
                "payment_failed",
            ) || requiresSupport
    )
    val request = quote.request
    return Booking(
        id,
        BookingRequest(
            requestKey,
            request.stayId,
            LocalDate.parse(request.checkIn),
            LocalDate.parse(request.checkOut),
            request.guests,
            request.useCredit,
        ),
        quote.toQuote(),
        Instant.parse(createdAt).toEpochMilli(),
        state == "cancelled",
        BookedStay(stay.name, stay.location, stay.country, stay.image),
        testMode,
        cancellationPending = state == "cancel_pending",
        requiresSupport = requiresSupport,
        paymentPending = state == "pending_payment",
        paymentFailed = state == "payment_failed",
    )
}

private fun ApiAccount.toSnapshot(): AccountSnapshot =
    AccountSnapshot(
        Account(
            Profile(
                profile.name,
                profile.hometown,
                profile.bio,
                profile.shareHometown,
                profile.shareActivity,
            ),
            minor(balanceMinor),
            savedStayIds.toSet(),
        ),
        reservations.map(ApiReservation::toBooking),
        ledger.map {
            LedgerEntry(
                it.id,
                it.title,
                minor(it.amountMinor, true),
                Instant.parse(it.createdAt).toEpochMilli(),
                it.reservationId,
            )
        },
    )

private fun ApiStay.toStay() =
    Stay(
        id,
        name,
        location,
        country,
        category,
        minor(nightlyMinor),
        maxGuests,
        "",
        0,
        image,
        "",
        description,
        amenities,
        0.0,
        0.0,
    )
