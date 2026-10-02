package com.roam.server

import com.roam.core.BookingPolicy
import com.roam.core.Profile
import com.roam.core.api.*
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun uuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (_: Exception) {
        invalid("A valid identifier is required.")
    }

private fun nowValue(instant: Instant) = java.sql.Timestamp.from(instant)

internal data class ReservationRow(
    val accountId: String,
    val value: ApiReservation,
    val intentId: String?,
    val refundId: String?,
    val refundStartedAt: Instant?,
    val cancelRequested: Boolean,
    val reconcileAttempts: Int,
)

class Commerce(
    private val db: Database,
    private val payments: Payments,
    private val live: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun catalog(): ApiCatalog =
        db.transaction { c ->
            ApiCatalog(
                c.rows("SELECT payload::text FROM stays WHERE active ORDER BY id") {
                    wireJson.decodeFromString<ApiStay>(it.getString(1))
                }
            )
        }

    fun account(accountId: String): ApiAccount =
        db.transaction { c ->
            lockAccount(c, accountId)
            val values =
                c.one("SELECT profile::text,balance FROM accounts WHERE id=?", uuid(accountId)) {
                    wireJson.decodeFromString<ApiProfile>(it.getString(1)) to it.getLong(2)
                }!!
            ApiAccount(
                accountId,
                values.first,
                values.second.toString(),
                c.rows(
                    "SELECT stay_id FROM saved_stays WHERE account_id=? ORDER BY stay_id",
                    uuid(accountId),
                ) {
                    it.getString(1)
                },
                c.rows(
                    "SELECT * FROM reservations WHERE account_id=? ORDER BY created_at DESC,id LIMIT 200",
                    uuid(accountId),
                ) {
                    readReservation(it).value
                },
                c.rows(
                    "SELECT * FROM ledger WHERE account_id=? ORDER BY created_at DESC,id LIMIT 200",
                    uuid(accountId),
                ) {
                    ApiLedgerEntry(
                        it.getString("id"),
                        it.getString("title"),
                        it.getLong("amount").toString(),
                        it.getTimestamp("created_at").toInstant().toString(),
                        it.getString("reservation_id"),
                    )
                },
            )
        }

    fun profile(accountId: String, input: ApiProfile): ApiProfile =
        db.transaction { c ->
            lockAccount(c, accountId)
            val current =
                c.one("SELECT profile::text FROM accounts WHERE id=?", uuid(accountId)) {
                    wireJson.decodeFromString<ApiProfile>(it.getString(1))
                }!!
            if (current.revision != input.revision)
                throw ApiFailure(
                    412,
                    "PROFILE_CHANGED",
                    "Your profile changed on another device. Refresh and try again.",
                )
            val clean =
                try {
                    BookingPolicy.profile(input.name, input.hometown, input.bio, Profile())
                } catch (_: IllegalArgumentException) {
                    invalid(
                        "Check your name, hometown, and biography lengths and remove control characters."
                    )
                }
            val updated =
                input.copy(
                    name = clean.name,
                    hometown = clean.hometown,
                    bio = clean.bio,
                    revision = Math.addExact(current.revision, 1),
                )
            c.exec(
                "UPDATE accounts SET profile=?::jsonb WHERE id=?",
                wireJson.encodeToString(updated),
                uuid(accountId),
            )
            updated
        }

    fun saved(accountId: String, stayId: String, value: Boolean) =
        db.transaction { c ->
            lockAccount(c, accountId)
            if (value) {
                stay(c, stayId)
                c.exec(
                    "INSERT INTO saved_stays(account_id,stay_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                    uuid(accountId),
                    stayId,
                )
            } else
                c.exec(
                    "DELETE FROM saved_stays WHERE account_id=? AND stay_id=?",
                    uuid(accountId),
                    stayId,
                )
        }

    fun quote(accountId: String, request: ApiQuoteRequest): ApiQuote =
        db.transaction { c ->
            val balance = lockAccount(c, accountId)
            val stay = stay(c, request.stayId)
            val checkIn =
                try {
                    LocalDate.parse(request.checkIn)
                } catch (_: Exception) {
                    invalid("Choose a valid check-in date.")
                }
            val checkOut =
                try {
                    LocalDate.parse(request.checkOut)
                } catch (_: Exception) {
                    invalid("Choose a valid check-out date.")
                }
            val today = LocalDate.now(clock.withZone(ZoneId.of(stay.timeZone)))
            val nights = ChronoUnit.DAYS.between(checkIn, checkOut)
            if (
                checkIn.isBefore(today) ||
                    checkIn.isAfter(today.plusYears(1)) ||
                    nights !in 1..28 ||
                    request.guests !in 1..stay.maxGuests
            )
                invalid(
                    "Choose available dates, 1–28 nights, and a guest count this stay supports."
                )
            if (live && stay.demo)
                throw ApiFailure(
                    503,
                    "DEMO_INVENTORY",
                    "This sample stay cannot accept real payments.",
                )
            if (
                c.one(
                    "SELECT count(*) FROM reserved_nights WHERE stay_id=? AND night>=? AND night<?",
                    stay.id,
                    java.sql.Date.valueOf(checkIn),
                    java.sql.Date.valueOf(checkOut),
                ) {
                    it.getInt(1)
                }!! > 0
            )
                conflict("UNAVAILABLE", "These dates are no longer available.")
            val nightly = stay.nightlyMinor.toLongOrNull() ?: error("Invalid configured price")
            require(nightly in 50..3_000_000) { "Invalid configured price" }
            val subtotal = Math.multiplyExact(nightly, nights)
            val fee = Math.addExact(Math.multiplyExact(subtotal, 8), 50) / 100
            val total = Math.addExact(subtotal, fee)
            var credit = if (request.useCredit) minOf(balance, total) else 0
            // Stripe USD card payments require at least 50 cents when money remains due.
            if (total - credit in 1..49) credit = total - 50
            val quote =
                ApiQuote(
                    UUID.randomUUID().toString(),
                    request,
                    nights,
                    subtotal.toString(),
                    fee.toString(),
                    total.toString(),
                    credit.toString(),
                    (total - credit).toString(),
                    expiresAt = clock.instant().plusSeconds(300).toString(),
                    demo = stay.demo,
                    stay =
                        ApiBookedStay(
                            stay.id,
                            stay.name,
                            stay.location,
                            stay.country,
                            stay.image,
                            stay.timeZone,
                        ),
                )
            c.exec(
                "INSERT INTO quotes(id,account_id,payload,expires_at) VALUES (?,?,?::jsonb,?)",
                uuid(quote.id),
                uuid(accountId),
                wireJson.encodeToString(quote),
                nowValue(Instant.parse(quote.expiresAt)),
            )
            quote
        }

    fun reserve(accountId: String, key: String, request: ApiReserveRequest): ApiReservationResult {
        if (!key.matches(Regex("[A-Za-z0-9_-]{16,100}")))
            invalid("A valid idempotency key is required.")
        val id =
            db.transaction { c ->
                val balance = lockAccount(c, accountId)
                val existing =
                    c.one(
                        "SELECT * FROM reservations WHERE account_id=? AND request_key=?",
                        uuid(accountId),
                        key,
                        read = ::readReservation,
                    )
                if (existing != null) {
                    if (existing.value.quote.id != request.quoteId)
                        conflict(
                            "IDEMPOTENCY_CONFLICT",
                            "This request key was used for another quote.",
                        )
                    return@transaction existing.value.id
                }
                val quote =
                    c.one(
                        "SELECT payload::text FROM quotes WHERE id=? AND account_id=?",
                        uuid(request.quoteId),
                        uuid(accountId),
                    ) {
                        wireJson.decodeFromString<ApiQuote>(it.getString(1))
                    } ?: missing()
                if (!clock.instant().isBefore(Instant.parse(quote.expiresAt)))
                    conflict("QUOTE_EXPIRED", "This offer expired. Review a new quote.")
                if (
                    c.one("SELECT id FROM reservations WHERE quote_id=?", uuid(quote.id)) {
                        it.getString(1)
                    } != null
                )
                    conflict(
                        "QUOTE_CONSUMED",
                        "This quote already has a reservation. Open your trips to continue.",
                    )
                if (
                    c.one(
                        "SELECT count(*) FROM reservations WHERE account_id=? AND state IN ('pending_payment','cancel_pending')",
                        uuid(accountId),
                    ) {
                        it.getInt(1)
                    }!! >= 3
                )
                    conflict(
                        "TOO_MANY_PENDING",
                        "Finish or cancel a pending reservation before starting another.",
                    )
                val active = stay(c, quote.request.stayId)
                if (
                    LocalDate.parse(quote.request.checkIn)
                        .isBefore(LocalDate.now(clock.withZone(ZoneId.of(quote.stay.timeZone))))
                )
                    conflict(
                        "QUOTE_EXPIRED",
                        "The property's check-in date has passed. Review new dates.",
                    )
                if (live && (active.demo || quote.demo))
                    throw ApiFailure(
                        503,
                        "DEMO_INVENTORY",
                        "This sample stay cannot accept real payments.",
                    )
                val credit = quote.creditMinor.toLong()
                if (balance < credit)
                    conflict("QUOTE_STALE", "Your available credit changed. Review a new quote.")
                val reservationId = UUID.randomUUID().toString()
                val now = clock.instant()
                c.exec(
                    "INSERT INTO reservations(id,account_id,request_key,quote_id,quote,state,created_at,payment_deadline,test_mode) VALUES (?,?,?,?,?::jsonb,?,?,?,?)",
                    uuid(reservationId),
                    uuid(accountId),
                    key,
                    uuid(quote.id),
                    wireJson.encodeToString(quote),
                    if (quote.dueMinor == "0") "confirmed" else "pending_payment",
                    nowValue(now),
                    nowValue(now.plusSeconds(1800)),
                    !live,
                )
                c.exec(
                    "UPDATE reservations SET next_reconcile_at=? WHERE id=?",
                    nowValue(now),
                    uuid(reservationId),
                )
                var date = LocalDate.parse(quote.request.checkIn)
                val end = LocalDate.parse(quote.request.checkOut)
                while (date.isBefore(end)) {
                    if (
                        c.exec(
                            "INSERT INTO reserved_nights(stay_id,night,reservation_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                            quote.request.stayId,
                            java.sql.Date.valueOf(date),
                            uuid(reservationId),
                        ) != 1
                    )
                        conflict(
                            "UNAVAILABLE",
                            "These dates were just reserved. Choose another stay or dates.",
                        )
                    date = date.plusDays(1)
                }
                if (credit > 0) {
                    c.exec(
                        "UPDATE accounts SET balance=balance-? WHERE id=?",
                        credit,
                        uuid(accountId),
                    )
                    ledger(c, accountId, reservationId, "debit", "Travel credit applied", -credit)
                }
                reservationId
            }
        return reconcileOwned(accountId, id)
    }

    fun byRequest(accountId: String, key: String): ApiReservationResult {
        if (key.length > 100) missing()
        val id =
            db.transaction { c ->
                c.one(
                    "SELECT id FROM reservations WHERE account_id=? AND request_key=?",
                    uuid(accountId),
                    key,
                ) {
                    it.getString(1)
                }
            } ?: missing()
        return reconcileOwned(accountId, id)
    }

    fun reservation(accountId: String, id: String): ApiReservationResult =
        reconcileOwned(accountId, id)

    fun cancel(accountId: String, id: String): ApiReservationResult {
        db.transaction { c ->
            lockAccount(c, accountId)
            val row = owned(c, accountId, id)
            if (row.value.state in setOf("cancelled", "payment_failed")) return@transaction
            val today = LocalDate.now(clock.withZone(ZoneId.of(row.value.stay.timeZone)))
            if (
                row.value.state == "confirmed" &&
                    !today.isBefore(LocalDate.parse(row.value.quote.request.checkIn))
            )
                conflict(
                    "CANCELLATION_CLOSED",
                    "Cancellation is available before the property's check-in date.",
                )
            c.exec(
                "UPDATE reservations SET cancel_requested=true,state='cancel_pending' WHERE id=?",
                uuid(id),
            )
        }
        return reconcileOwned(accountId, id)
    }

    private fun reconcileOwned(accountId: String, id: String): ApiReservationResult {
        val row = db.transaction { c -> owned(c, accountId, id) }
        if (row.value.requiresSupport) return ApiReservationResult(row.value)
        return reconcile(row)
    }

    /**
     * Durable reservation rows are the retry queue. External calls are always outside DB
     * transactions.
     */
    private fun reconcile(initial: ReservationRow): ApiReservationResult {
        var row = initial
        if (row.value.state in setOf("cancelled", "payment_failed"))
            return ApiReservationResult(row.value)
        if (row.value.state == "confirmed" && !row.cancelRequested)
            return ApiReservationResult(row.value)
        if (
            row.value.state == "pending_payment" &&
                !clock.instant().isBefore(Instant.parse(row.value.paymentDeadline))
        ) {
            db.transaction { c ->
                lockAccount(c, row.accountId)
                c.exec(
                    "UPDATE reservations SET cancel_requested=true,state='cancel_pending' WHERE id=? AND state='pending_payment'",
                    uuid(row.value.id),
                )
            }
            row = load(row.value.id)
        }
        if (row.value.quote.dueMinor == "0") {
            if (row.cancelRequested) finishCancellation(row.value.id)
            return ApiReservationResult(load(row.value.id).value)
        }
        var intent =
            if (row.intentId == null) {
                // Stripe may forget idempotency keys after 24h. Never create a second intent after
                // that window.
                if (
                    clock
                        .instant()
                        .isAfter(Instant.parse(row.value.createdAt).plusSeconds(23 * 3600))
                )
                    throw ApiFailure(
                        503,
                        "PAYMENT_REVIEW_REQUIRED",
                        "Your payment request needs reconciliation. Contact support with your reservation number.",
                    )
                payments.create(row.value.id, row.value.quote.dueMinor.toLong())
            } else payments.retrieve(row.intentId)
        verifyIntent(row, intent)
        attachIntent(row.value.id, intent)
        row = load(row.value.id)
        if (row.cancelRequested) {
            if (intent.status !in setOf("succeeded", "canceled")) {
                intent = payments.cancel(intent.id, row.value.id)
                verifyIntent(row, intent)
            }
            when (intent.status) {
                "canceled" -> finishCancellation(row.value.id)
                "succeeded" -> refund(row, intent)
            }
        } else {
            when (intent.status) {
                "succeeded" ->
                    db.transaction { c ->
                        lockAccount(c, row.accountId)
                        c.exec(
                            "UPDATE reservations SET state='confirmed' WHERE id=? AND state='pending_payment' AND NOT cancel_requested",
                            uuid(row.value.id),
                        )
                    }
                "canceled" -> finishCancellation(row.value.id)
            }
        }
        val latest = load(row.value.id)
        return ApiReservationResult(
            latest.value,
            if (
                latest.value.state == "pending_payment" &&
                    intent.status in
                        setOf("requires_payment_method", "requires_confirmation", "requires_action")
            )
                intent.clientSecret
            else null,
        )
    }

    private fun refund(row: ReservationRow, intent: ProviderIntent) {
        val prepared =
            db.transaction { c ->
                lockAccount(c, row.accountId)
                val latest = owned(c, row.accountId, row.value.id)
                if (latest.value.state == "cancelled") return@transaction latest
                if (latest.refundStartedAt == null)
                    c.exec(
                        "UPDATE reservations SET refund_started_at=? WHERE id=?",
                        nowValue(clock.instant()),
                        uuid(row.value.id),
                    )
                owned(c, row.accountId, row.value.id)
            }
        if (prepared.value.state == "cancelled") return
        if (
            prepared.refundId == null &&
                clock.instant().isAfter(prepared.refundStartedAt!!.plusSeconds(23 * 3600))
        )
            throw ApiFailure(
                503,
                "REFUND_REVIEW_REQUIRED",
                "Your refund is being reconciled. Contact support with your reservation number.",
            )
        val refund =
            if (prepared.refundId == null) payments.refund(intent.id, row.value.id, intent.amount)
            else payments.retrieveRefund(prepared.refundId)
        require(refund.paymentIntentId == intent.id && refund.amount == intent.amount) {
            "Provider refund mismatch"
        }
        db.transaction { c ->
            lockAccount(c, row.accountId)
            val latest = owned(c, row.accountId, row.value.id)
            require(latest.refundId == null || latest.refundId == refund.id)
            c.exec("UPDATE reservations SET refund_id=? WHERE id=?", refund.id, uuid(row.value.id))
        }
        if (refund.status == "succeeded") finishCancellation(row.value.id)
        if (refund.status in setOf("failed", "canceled"))
            throw ApiFailure(
                503,
                "REFUND_REVIEW_REQUIRED",
                "Your refund requires support. Your reservation remains held until it is resolved.",
            )
    }

    private fun verifyIntent(row: ReservationRow, intent: ProviderIntent) {
        require(
            intent.reservationId == row.value.id &&
                intent.amount == row.value.quote.dueMinor.toLong() &&
                intent.currency == "usd" &&
                intent.live == !row.value.testMode &&
                (row.intentId == null || row.intentId == intent.id)
        ) {
            "Provider payment did not match the stored reservation"
        }
        if (intent.status == "succeeded")
            require(intent.receivedAmount == intent.amount) { "Provider captured amount mismatch" }
    }

    private fun attachIntent(id: String, intent: ProviderIntent) {
        val before = load(id)
        db.transaction { c ->
            lockAccount(c, before.accountId)
            val row = owned(c, before.accountId, id)
            verifyIntent(row, intent)
            c.exec(
                "UPDATE reservations SET payment_intent_id=?,review_required=CASE WHEN review_reason='PAYMENT_REVIEW_REQUIRED' THEN false ELSE review_required END,review_reason=CASE WHEN review_reason='PAYMENT_REVIEW_REQUIRED' THEN NULL ELSE review_reason END WHERE id=?",
                intent.id,
                uuid(id),
            )
        }
    }

    private fun finishCancellation(id: String) =
        db.transaction { c ->
            val accountId =
                c.one("SELECT account_id FROM reservations WHERE id=?", uuid(id)) {
                    it.getString(1)
                } ?: missing()
            lockAccount(c, accountId)
            val row = owned(c, accountId, id)
            if (row.value.state in setOf("cancelled", "payment_failed")) return@transaction
            val credit = row.value.quote.creditMinor.toLong()
            if (credit > 0) {
                c.exec("UPDATE accounts SET balance=balance+? WHERE id=?", credit, uuid(accountId))
                ledger(c, accountId, id, "return", "Travel credit returned", credit)
            }
            c.exec(
                "UPDATE reservations SET state='cancelled',credit_returned=?,review_required=false,review_reason=NULL WHERE id=?",
                credit,
                uuid(id),
            )
            c.exec("DELETE FROM reserved_nights WHERE reservation_id=?", uuid(id))
        }

    fun reconcilePending(continueProcessing: () -> Boolean = { true }): Int {
        if (!continueProcessing()) return 0
        // Persisted leases and ordered due times prevent old failures starving new work across
        // replicas.
        val rows =
            db.transaction { c ->
                c.rows(
                        "SELECT * FROM reservations WHERE state IN ('pending_payment','cancel_pending') AND NOT review_required AND next_reconcile_at<=? ORDER BY next_reconcile_at,id LIMIT 100 FOR UPDATE SKIP LOCKED",
                        nowValue(clock.instant()),
                        read = ::readReservation,
                    )
                    .also { rows ->
                        rows.forEach {
                            c.exec(
                                "UPDATE reservations SET next_reconcile_at=?,reconcile_attempts=reconcile_attempts+1 WHERE id=?",
                                nowValue(clock.instant().plusSeconds(120)),
                                uuid(it.value.id),
                            )
                        }
                    }
            }
        var failures = 0
        rows.forEach { row ->
            // Stop between reservations during shutdown; each provider call has its own deadline.
            // Unprocessed claims remain durable and become eligible after their lease expires.
            if (!continueProcessing()) return failures
            try {
                reconcile(row)
                db.transaction { c ->
                    c.exec(
                        "UPDATE reservations SET next_reconcile_at=?,reconcile_attempts=0 WHERE id=?",
                        nowValue(clock.instant().plusSeconds(30)),
                        uuid(row.value.id),
                    )
                }
            } catch (failure: Exception) {
                failures++
                if (
                    failure is ApiFailure &&
                        failure.code in setOf("PAYMENT_REVIEW_REQUIRED", "REFUND_REVIEW_REQUIRED")
                )
                    markReview(row.value.id, failure.code, row.refundId)
                else
                    db.transaction { c ->
                        c.exec(
                            "UPDATE reservations SET next_reconcile_at=? WHERE id=?",
                            nowValue(
                                clock.instant().plusSeconds(30L shl minOf(row.reconcileAttempts, 5))
                            ),
                            uuid(row.value.id),
                        )
                    }
            }
        }
        return failures
    }

    internal fun markReview(id: String, reason: String, expectedRefundId: String? = null) =
        db.transaction { c ->
            // A stale failed worker must not overwrite a concurrently recovered webhook outcome.
            when (reason) {
                "PAYMENT_REVIEW_REQUIRED" ->
                    c.exec(
                        "UPDATE reservations SET review_required=true,review_reason=? WHERE id=? AND state IN ('pending_payment','cancel_pending') AND payment_intent_id IS NULL",
                        reason,
                        uuid(id),
                    )
                "REFUND_REVIEW_REQUIRED" ->
                    c.exec(
                        "UPDATE reservations SET review_required=true,review_reason=? WHERE id=? AND state='cancel_pending' AND refund_id IS NOT DISTINCT FROM ?",
                        reason,
                        uuid(id),
                        expectedRefundId,
                    )
                else ->
                    c.exec(
                        "UPDATE reservations SET review_required=true,review_reason=? WHERE id=? AND state NOT IN ('cancelled','payment_failed')",
                        reason,
                        uuid(id),
                    )
            }
        }

    fun webhook(event: kotlinx.serialization.json.JsonObject) {
        val eventId = event.text("id")
        if (
            db.transaction { c ->
                c.one("SELECT id FROM webhook_events WHERE id=?", eventId) { it.getString(1) }
            } != null
        )
            return
        val type = event.text("type")
        if (
            type.startsWith("payment_intent.") ||
                type.startsWith("refund.") ||
                type == "charge.refunded"
        ) {
            val data = event["data"]!!.jsonObject["object"]!!.jsonObject
            val intentId =
                if (type.startsWith("payment_intent.")) data.text("id")
                else data["payment_intent"]?.jsonPrimitive?.content
            val reservationId =
                data["metadata"]?.jsonObject?.get("reservation_id")?.jsonPrimitive?.content
            val row =
                db.transaction { c ->
                    if (intentId == null) null
                    else
                        c.one(
                            "SELECT * FROM reservations WHERE payment_intent_id=?",
                            intentId,
                            read = ::readReservation,
                        )
                } ?: reservationId?.let { id -> runCatching { load(id) }.getOrNull() }
            if (row != null) {
                // Always retrieve canonical provider state: duplicated or out-of-order events
                // cannot rewind it.
                if (row.intentId == null && intentId != null) {
                    val intent = payments.retrieve(intentId)
                    verifyIntent(row, intent)
                    attachIntent(row.value.id, intent)
                }
                if (type.startsWith("refund.")) {
                    val refund = payments.retrieveRefund(data.text("id"))
                    require(refund.paymentIntentId == (row.intentId ?: intentId)) {
                        "Provider refund ownership mismatch"
                    }
                    if (row.cancelRequested && refund.amount == row.value.quote.dueMinor.toLong()) {
                        // A signed refund event recovers a lost creation response even after
                        // provider dedupe expiry.
                        db.transaction { c ->
                            lockAccount(c, row.accountId)
                            val current = owned(c, row.accountId, row.value.id)
                            require(current.refundId == null || current.refundId == refund.id)
                            c.exec(
                                "UPDATE reservations SET refund_id=?,review_required=false,review_reason=NULL WHERE id=?",
                                refund.id,
                                uuid(row.value.id),
                            )
                        }
                    } else {
                        markReview(row.value.id, "EXTERNAL_REFUND")
                        db.transaction { c ->
                            c.exec(
                                "INSERT INTO webhook_events(id) VALUES (?) ON CONFLICT DO NOTHING",
                                eventId,
                            )
                        }
                        return
                    }
                } else if (type == "charge.refunded") {
                    // Dashboard refunds can be partial; never infer a booking cancellation from
                    // their amount.
                    if (!row.cancelRequested) markReview(row.value.id, "EXTERNAL_REFUND")
                }
                reconcile(load(row.value.id))
            }
        }
        db.transaction { c ->
            c.exec("INSERT INTO webhook_events(id) VALUES (?) ON CONFLICT DO NOTHING", eventId)
        }
    }

    private fun load(id: String): ReservationRow =
        db.transaction { c ->
            c.one("SELECT * FROM reservations WHERE id=?", uuid(id), read = ::readReservation)
                ?: missing()
        }

    private fun owned(c: Connection, accountId: String, id: String): ReservationRow =
        c.one(
            "SELECT * FROM reservations WHERE id=? AND account_id=?",
            uuid(id),
            uuid(accountId),
            read = ::readReservation,
        ) ?: missing()

    private fun lockAccount(c: Connection, accountId: String): Long {
        c.exec(
            "INSERT INTO accounts(id,profile) VALUES (?,?::jsonb) ON CONFLICT DO NOTHING",
            uuid(accountId),
            wireJson.encodeToString(ApiProfile()),
        )
        return c.one("SELECT balance FROM accounts WHERE id=? FOR UPDATE", uuid(accountId)) {
            it.getLong(1)
        }!!
    }

    private fun stay(c: Connection, id: String): ApiStay {
        if (!id.matches(Regex("[a-z0-9-]{1,80}"))) invalid("Choose a valid stay.")
        return c.one("SELECT payload::text FROM stays WHERE id=? AND active", id) {
            wireJson.decodeFromString<ApiStay>(it.getString(1))
        } ?: missing()
    }

    private fun ledger(
        c: Connection,
        accountId: String,
        reservationId: String,
        kind: String,
        title: String,
        amount: Long,
    ) {
        c.exec(
            "INSERT INTO ledger(id,account_id,reservation_id,kind,title,amount,created_at) VALUES (?,?,?,?,?,?,?)",
            UUID.randomUUID(),
            uuid(accountId),
            uuid(reservationId),
            kind,
            title,
            amount,
            nowValue(clock.instant()),
        )
    }
}

private fun readReservation(row: ResultSet): ReservationRow {
    val value =
        ApiReservation(
            row.getString("id"),
            row.getString("request_key"),
            wireJson.decodeFromString<ApiQuote>(row.getString("quote")),
            row.getString("state"),
            row.getTimestamp("created_at").toInstant().toString(),
            row.getTimestamp("payment_deadline").toInstant().toString(),
            row.getLong("credit_returned").toString(),
            row.getBoolean("test_mode"),
            requiresSupport = row.getBoolean("review_required"),
        )
    return ReservationRow(
        row.getString("account_id"),
        value,
        row.getString("payment_intent_id"),
        row.getString("refund_id"),
        row.getTimestamp("refund_started_at")?.toInstant(),
        row.getBoolean("cancel_requested"),
        row.getInt("reconcile_attempts"),
    )
}
