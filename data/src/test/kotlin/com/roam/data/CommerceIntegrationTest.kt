package com.roam.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.roam.core.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommerceIntegrationTest {
    private lateinit var db: RoamDatabase
    private lateinit var store: RoomAccountStore
    private lateinit var service: CommerceService
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    private val request =
        BookingRequest(
            "stable-key",
            "kyoto",
            LocalDate.of(2026, 10, 9),
            LocalDate.of(2026, 10, 12),
            2,
            true,
        )

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    RoamDatabase::class.java,
                )
                .build()
        store = RoomAccountStore(db)
        service = CommerceService(store, clock)
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun `parallel retries debit exactly once`() = runTest {
        val results = coroutineScope {
            List(24) { async(Dispatchers.IO) { service.reserve(request) } }.awaitAll()
        }
        assertEquals(1, results.map { it.id }.distinct().size)
        val snapshot = service.snapshots.first()
        assertEquals(0, snapshot.account.balance.minor)
        assertEquals(1, snapshot.bookings.size)
        assertEquals(1, snapshot.ledger.size)
    }

    @Test
    fun `reused key with changed payload fails without writes`() = runTest {
        service.reserve(request)
        try {
            service.reserve(request.copy(guests = 3))
            fail("Expected conflict")
        } catch (_: CommerceException) {}
        assertEquals(1, service.snapshots.first().bookings.size)
    }

    @Test
    fun `concurrent different bookings cannot overdraw`() = runTest {
        coroutineScope {
            List(12) { i ->
                    async(Dispatchers.IO) { service.reserve(request.copy(key = "key-$i")) }
                }
                .awaitAll()
        }
        val snapshot = service.snapshots.first()
        assertEquals(12, snapshot.bookings.size)
        assertEquals(8500, snapshot.bookings.sumOf { it.quote.credit.minor })
        assertEquals(0, snapshot.account.balance.minor)
    }

    @Test
    fun `cancellation returns credit once and preserves receipt`() = runTest {
        val original = service.reserve(request)
        coroutineScope {
            List(12) { async(Dispatchers.IO) { service.cancel(request.key) } }.awaitAll()
        }
        val snapshot = service.snapshots.first()
        assertEquals(8500, snapshot.account.balance.minor)
        assertTrue(snapshot.bookings.single().cancelled)
        assertEquals(original.quote, snapshot.bookings.single().quote)
        assertEquals(2, snapshot.ledger.size)
        assertTrue(service.reserve(request).cancelled)
    }

    @Test
    fun `reservation snapshots round trip independently of active inventory`() = runTest {
        val original = service.reserve(request)
        assertEquals(BookedStay.from(Catalog.find(request.stayId)), original.stay)
        val archived =
            original.copy(
                request = original.request.copy(stayId = "retired-listing"),
                stay =
                    BookedStay("An original name", "An original location", "Original country", ""),
            )
        store.transaction { update(archived) }
        val restored = service.snapshots.first().bookings.single()
        assertEquals(archived, restored)
        assertEquals(archived, service.reserve(archived.request))
        val cancelled = service.cancel(archived.request.key)
        assertEquals(archived.stay, cancelled.stay)
        assertEquals(archived.quote, cancelled.quote)
        assertEquals(8500, service.snapshots.first().account.balance.minor)
        assertEquals(
            "Credit returned · Original country",
            service.snapshots.first().ledger.single { it.amount.minor > 0 }.title,
        )
    }

    @Test
    fun `refund append failure rolls back cancellation and balance`() = runTest {
        val ids = ArrayDeque(listOf("booking", "debit", "debit"))
        val failing = CommerceService(store, clock) { ids.removeFirst() }
        val original = failing.reserve(request)
        val before = failing.snapshots.first()
        try {
            failing.cancel(request.key)
            fail("Expected ledger constraint failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {}
        assertEquals(before, failing.snapshots.first())
        assertEquals(original, failing.reserve(request))
    }

    @Test
    fun `cancelled coroutine rolls back writes rather than committing a partial reservation`() =
        runTest {
            val before = service.snapshots.first()
            val booking =
                Booking(
                    "interrupted-booking",
                    request,
                    BookingPolicy.quote(request, before.account.balance, service.today()),
                    0,
                )
            val writesFinished = CompletableDeferred<Unit>()
            val transaction =
                launch(Dispatchers.IO) {
                    store.transaction {
                        save(account().copy(balance = Money(0)))
                        insert(booking)
                        writesFinished.complete(Unit)
                        awaitCancellation()
                    }
                }
            writesFinished.await()
            transaction.cancelAndJoin()
            assertEquals(before, service.snapshots.first())
        }

    @Test
    fun `invalid persisted allocation cannot change the wallet during cancellation`() = runTest {
        service.reserve(request)
        db.openHelper.writableDatabase.execSQL("UPDATE bookings SET credit = -1")
        try {
            service.cancel(request.key)
            fail("Expected invalid historical allocation")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, db.dao().account().balance)
        assertFalse(db.dao().booking(request.key)!!.cancelled)
        assertEquals(1, db.dao().ledger().size)
    }

    @Test
    fun `local commerce refuses to manage a live reservation`() = runTest {
        val demo = service.reserve(request)
        store.transaction { update(demo.copy(simulated = false)) }
        val before = service.snapshots.first()
        assertFalse(before.bookings.single().simulated)
        try {
            service.cancel(request.key)
            fail("Expected a connected account requirement")
        } catch (_: CommerceException) {}
        try {
            service.reserve(request)
            fail("Expected a connected account requirement")
        } catch (_: CommerceException) {}
        assertEquals(before, service.snapshots.first())
    }

    @Test
    fun `pending and support flags survive storage and cannot be resolved by demo commerce`() =
        runTest {
            val original = service.reserve(request)
            val pendingStates =
                listOf(
                    original.copy(cancellationPending = true),
                    original.copy(requiresSupport = true),
                    original.copy(paymentPending = true),
                    original.copy(paymentFailed = true),
                )
            for (pending in pendingStates) {
                store.transaction { update(pending) }
                val before = service.snapshots.first()
                assertEquals(pending, before.bookings.single())
                try {
                    service.cancel(request.key)
                    fail("Expected a connected account requirement")
                } catch (_: CommerceException) {}
                assertEquals(before, service.snapshots.first())
            }
        }

    @Test
    fun `gateway quote uses the latest wallet without mutating it`() = runTest {
        val gateway: CommerceGateway = service
        assertTrue(gateway.isDemo)
        assertEquals(Catalog.stays, gateway.catalog.first())
        assertEquals(8500, gateway.quote(request).credit.minor)
        service.redeem("welcome")
        val before = service.snapshots.first()
        assertEquals(11000, gateway.quote(request).credit.minor)
        gateway.refresh()
        assertEquals(before, service.snapshots.first())
    }

    @Test
    fun `cancel at check-in is rejected`() = runTest {
        service.reserve(
            request.copy(checkIn = service.today(), checkOut = service.today().plusDays(1))
        )
        try {
            service.cancel(request.key)
            fail("Expected rejection")
        } catch (_: CommerceException) {}
        assertFalse(service.snapshots.first().bookings.single().cancelled)
    }

    @Test
    fun `failed transaction rolls back balance and ledger together`() = runTest {
        service.snapshots.first()
        try {
            store.transaction {
                save(account().copy(balance = Money(0)))
                append(LedgerEntry("same", "First", Money(1), 0))
                append(LedgerEntry("same", "Duplicate", Money(1), 0))
            }
            fail("Expected constraint failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {}
        val snapshot = service.snapshots.first()
        assertEquals(8500, snapshot.account.balance.minor)
        assertTrue(snapshot.ledger.isEmpty())
    }

    @Test
    fun `benefit can be redeemed only once under contention`() = runTest {
        coroutineScope {
            List(20) { async(Dispatchers.IO) { service.redeem("welcome") } }.awaitAll()
        }
        val snapshot = service.snapshots.first()
        assertEquals(11000, snapshot.account.balance.minor)
        assertEquals(setOf("welcome"), snapshot.account.redeemed)
        assertEquals(1, snapshot.ledger.size)
    }

    @Test
    fun `profile privacy and favorites persist`() = runTest {
        service.editProfile("Alex Chen", "Kyoto", "Wander slowly")
        service.setPrivacy(false, true)
        service.toggleSaved("kyoto")
        val state = CommerceService(RoomAccountStore(db), clock).snapshots.first()
        assertEquals("Alex Chen", state.account.profile.name)
        assertFalse(state.account.profile.shareHometown)
        assertTrue(state.account.profile.shareActivity)
        assertEquals(setOf("kyoto"), state.account.saved)
        service.toggleSaved("kyoto")
        assertTrue(service.snapshots.first().account.saved.isEmpty())
    }

    @Test
    fun `invalid reservation changes nothing`() = runTest {
        try {
            service.reserve(request.copy(guests = 0))
            fail("Expected validation failure")
        } catch (_: CommerceException) {}
        val snapshot = service.snapshots.first()
        assertEquals(8500, snapshot.account.balance.minor)
        assertTrue(snapshot.bookings.isEmpty())
    }

    @Test
    fun `disk reopen recovers committed booking and retry identity`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recovery-${System.nanoTime()}.db"
        val firstDb = Room.databaseBuilder(context, RoamDatabase::class.java, name).build()
        val original = CommerceService(RoomAccountStore(firstDb), clock).reserve(request)
        firstDb.close()
        val reopened = Room.databaseBuilder(context, RoamDatabase::class.java, name).build()
        try {
            val restored = CommerceService(RoomAccountStore(reopened), clock)
            assertEquals(original, restored.reserve(request))
            assertEquals(0, restored.snapshots.first().account.balance.minor)
            assertEquals(1, restored.snapshots.first().ledger.size)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
