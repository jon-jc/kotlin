package com.roam.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roam.core.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookingMigrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    private val request =
        BookingRequest(
            "legacy-key",
            "kyoto",
            LocalDate.of(2026, 10, 9),
            LocalDate.of(2026, 10, 12),
            2,
            true,
        )

    @Test
    fun `v1 disk migration preserves wallet history and retries even after inventory removal`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "migration-${System.nanoTime()}.db"
            val known = legacyBooking("known", request, 5000)
            val removed =
                legacyBooking(
                    "removed",
                    request.copy(key = "removed-key", stayId = "retired-listing"),
                    3500,
                )
            val alreadyCancelled =
                legacyBooking("cancelled", request.copy(key = "cancelled-key"), 1200)
                    .copy(cancelled = true)
            val entries =
                listOf(
                    LedgerEntry("benefit-welcome", "Welcome", Money(2500), 0),
                    LedgerEntry("debit-known", "Stay · Japan", Money(-5000), 1, known.id),
                    LedgerEntry("debit-removed", "Stay · Portugal", Money(-3500), 2, removed.id),
                    LedgerEntry(
                        "debit-cancelled",
                        "Stay · Japan",
                        Money(-1200),
                        3,
                        alreadyCancelled.id,
                    ),
                    LedgerEntry(
                        "refund-cancelled",
                        "Credit returned · Japan",
                        Money(1200),
                        4,
                        alreadyCancelled.id,
                    ),
                )
            try {
                createV1(context, name, listOf(known, removed, alreadyCancelled), entries)
                val migrated = open(context, name)
                val committed: AccountSnapshot
                try {
                    val service = CommerceService(RoomAccountStore(migrated), clock)
                    val initial = service.snapshots.first()
                    assertEquals(2500, initial.account.balance.minor)
                    assertEquals("Legacy Traveler", initial.account.profile.name)
                    assertFalse(initial.account.profile.shareHometown)
                    assertTrue(initial.account.profile.shareActivity)
                    assertEquals(setOf("kyoto"), initial.account.saved)
                    assertEquals(setOf("welcome"), initial.account.redeemed)
                    assertEquals(entries.toSet(), initial.ledger.toSet())
                    assertEquals(setOf(known, removed, alreadyCancelled), initial.bookings.toSet())
                    assertEquals("The quiet side of Kyoto", service.reserve(request).stay.name)
                    assertEquals(removed, service.reserve(removed.request))
                    assertEquals("Unavailable stay", service.reserve(removed.request).stay.name)

                    service.redeem("welcome")
                    service.cancel(alreadyCancelled.request.key)
                    assertEquals(initial, service.snapshots.first())
                    service.cancel(removed.request.key)
                    service.cancel(removed.request.key)
                    service.cancel(known.request.key)
                    committed = service.snapshots.first()
                    assertEquals(11000, committed.account.balance.minor)
                    assertTrue(committed.bookings.all { it.cancelled })
                    assertEquals(entries.size + 2, committed.ledger.size)
                    assertEquals(
                        "Credit returned",
                        committed.ledger
                            .single { it.bookingId == removed.id && it.amount.minor > 0 }
                            .title,
                    )
                    assertEquals(known.quote, committed.bookings.single { it.id == known.id }.quote)
                    assertEquals(
                        removed.quote,
                        committed.bookings.single { it.id == removed.id }.quote,
                    )
                } finally {
                    migrated.close()
                }
                val reopened = open(context, name)
                try {
                    val service = CommerceService(RoomAccountStore(reopened), clock)
                    assertEquals(committed, service.snapshots.first())
                    assertTrue(service.reserve(removed.request).cancelled)
                    service.cancel(removed.request.key)
                    assertEquals(committed, service.snapshots.first())
                } finally {
                    reopened.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private fun legacyBooking(id: String, bookingRequest: BookingRequest, credit: Long): Booking =
        Booking(
            id,
            bookingRequest,
            BookingPolicy.quote(
                bookingRequest.copy(stayId = "kyoto"),
                Money(credit),
                LocalDate.now(clock),
            ),
            0,
        )

    private fun open(context: Context, name: String) =
        Room.databaseBuilder(context, RoamDatabase::class.java, name)
            .addMigrations(RoamDatabase.MIGRATION_1_2)
            .build()

    /** Build the actual exported v1 schema, including its Room identity and unique indices. */
    private fun createV1(
        context: Context,
        name: String,
        bookings: List<Booking>,
        ledger: List<LedgerEntry>,
    ) {
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema =
            JSONObject(javaClass.getResource("/com.roam.data.RoamDatabase/1.json")!!.readText())
                .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) {
                    for (j in 0 until indices.length()) {
                        db.execSQL(
                            indices
                                .getJSONObject(j)
                                .getString("createSql")
                                .replace("\${TABLE_NAME}", table)
                        )
                    }
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO accounts (id, name, hometown, bio, shareHometown, shareActivity, balance, saved, redeemed) VALUES (1, 'Legacy Traveler', 'Kyoto', 'Existing profile', 0, 1, 2500, 'kyoto', 'welcome')"
            )
            for (booking in bookings) {
                db.execSQL(
                    "INSERT INTO bookings (id, requestKey, stayId, checkIn, checkOut, guests, useCredit, nights, subtotal, fee, total, credit, due, createdAt, cancelled) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        booking.id,
                        booking.request.key,
                        booking.request.stayId,
                        booking.request.checkIn.toString(),
                        booking.request.checkOut.toString(),
                        booking.request.guests,
                        if (booking.request.useCredit) 1 else 0,
                        booking.quote.nights,
                        booking.quote.subtotal.minor,
                        booking.quote.serviceFee.minor,
                        booking.quote.total.minor,
                        booking.quote.credit.minor,
                        booking.quote.due.minor,
                        booking.createdAt,
                        if (booking.cancelled) 1 else 0,
                    ),
                )
            }
            for (entry in ledger) {
                db.execSQL(
                    "INSERT INTO ledger (id, title, amount, createdAt, bookingId) VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        entry.id,
                        entry.title,
                        entry.amount.minor,
                        entry.createdAt,
                        entry.bookingId,
                    ),
                )
            }
            db.version = 1
        }
    }
}
