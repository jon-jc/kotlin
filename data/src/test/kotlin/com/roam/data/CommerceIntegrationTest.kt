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
    fun `failed transaction rolls back debit and booking together`() = runTest {
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
