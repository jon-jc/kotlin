package com.roam.core

import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** The store must serialize transactions and roll back every write if the block fails. */
interface AccountStore {
    fun observe(): Flow<AccountSnapshot>
    suspend fun <T> transaction(block: suspend AccountTransaction.() -> T): T
}
interface AccountTransaction {
    suspend fun account(): Account
    suspend fun save(account: Account)
    suspend fun booking(key: String): Booking?
    suspend fun insert(booking: Booking)
    suspend fun update(booking: Booking)
    suspend fun append(entry: LedgerEntry)
}

class CommerceService(private val store: AccountStore, private val clock: Clock = Clock.systemUTC(), private val newId: () -> String = { UUID.randomUUID().toString() }) {
    val snapshots = store.observe()
    fun today(): LocalDate = LocalDate.now(clock)

    suspend fun reserve(request: BookingRequest): Booking = store.transaction {
        booking(request.key)?.let {
            if (it.request != request) throw CommerceException("This reservation key was already used for a different request.")
            return@transaction it
        }
        val account = account()
        val quote = BookingPolicy.quote(request, account.balance, today())
        val booking = Booking(newId(), request, quote, clock.millis())
        save(account.copy(balance = account.balance - quote.credit))
        insert(booking)
        if (quote.credit.minor > 0) append(LedgerEntry(newId(), "Stay · ${Catalog.find(request.stayId).country}", Money(-quote.credit.minor), clock.millis(), booking.id))
        booking
    }

    suspend fun cancel(key: String): Booking = store.transaction {
        val existing = booking(key) ?: throw CommerceException("We couldn't find that reservation.")
        if (existing.cancelled) return@transaction existing
        if (!today().isBefore(existing.request.checkIn)) throw CommerceException("Cancellation is available before check-in.")
        val account = account()
        val cancelled = existing.copy(cancelled = true)
        save(account.copy(balance = account.balance + existing.quote.credit))
        update(cancelled)
        if (existing.quote.credit.minor > 0) append(LedgerEntry(newId(), "Credit returned · ${Catalog.find(existing.request.stayId).country}", existing.quote.credit, clock.millis(), existing.id))
        cancelled
    }

    suspend fun redeem(benefit: String) = store.transaction {
        val value = when (benefit) { "welcome" -> 2500L; else -> throw CommerceException("This benefit isn't available.") }
        val current = account()
        if (benefit in current.redeemed) return@transaction
        save(current.copy(balance = current.balance + Money(value), redeemed = current.redeemed + benefit))
        append(LedgerEntry("benefit-$benefit", "Welcome to the community", Money(value), clock.millis()))
    }
    suspend fun toggleSaved(id: String) = store.transaction {
        Catalog.find(id)
        val current = account()
        save(current.copy(saved = if (id in current.saved) current.saved - id else current.saved + id))
    }
    suspend fun editProfile(name: String, hometown: String, bio: String) = store.transaction {
        val current = account()
        save(current.copy(profile = BookingPolicy.profile(name, hometown, bio, current.profile)))
    }
    suspend fun setPrivacy(hometown: Boolean, activity: Boolean) = store.transaction {
        val current = account()
        save(current.copy(profile = current.profile.copy(shareHometown = hometown, shareActivity = activity)))
    }
}
