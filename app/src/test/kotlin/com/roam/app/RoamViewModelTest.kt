package com.roam.app

import androidx.lifecycle.SavedStateHandle
import com.roam.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoamViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: TestStore
    private lateinit var service: CommerceService
    private lateinit var vm: RoamViewModel
    private lateinit var handle: SavedStateHandle
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        store = TestStore()
        service = CommerceService(store, Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC))
        handle = SavedStateHandle()
        vm = RoamViewModel(service, handle)
        dispatcher.scheduler.runCurrent()
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun checkout() { vm.accept(Intent.OpenStay("kyoto")); vm.accept(Intent.Checkout); dispatcher.scheduler.runCurrent() }
    @Test fun `rapid confirm taps dispatch one reservation`() = runTest(dispatcher) {
        checkout()
        repeat(12) { vm.accept(Intent.Reserve) }
        advanceUntilIdle()
        assertEquals(1, store.state.value.bookings.size)
        assertNotNull(vm.state.value.screen.receipt)
        assertFalse(vm.state.value.screen.busy)
    }
    @Test fun `decline never mutates wallet or bookings`() = runTest(dispatcher) {
        checkout(); vm.accept(Intent.Mode(DemoMode.Decline)); vm.accept(Intent.Reserve)
        advanceUntilIdle()
        assertEquals(8500, store.state.value.account.balance.minor)
        assertTrue(store.state.value.bookings.isEmpty())
        assertTrue(vm.state.value.screen.error!!.contains("declined"))
        assertFalse(vm.state.value.screen.busy)
    }
    @Test fun `lost response retries same key and displays original price`() = runTest(dispatcher) {
        checkout()
        val originalQuote = vm.quote()
        val key = vm.state.value.screen.requestKey
        vm.accept(Intent.Mode(DemoMode.LostResponse)); vm.accept(Intent.Reserve)
        advanceUntilIdle()
        assertNull(vm.state.value.screen.receipt)
        assertEquals(1, store.state.value.bookings.size)
        assertEquals(originalQuote, vm.quote())
        vm.accept(Intent.Reserve)
        advanceUntilIdle()
        assertEquals(1, store.state.value.bookings.size)
        assertEquals(key, vm.state.value.screen.receipt)
        assertEquals(0, store.state.value.account.balance.minor)
    }
    @Test fun `saved state restores exact checkout request`() = runTest(dispatcher) {
        checkout(); vm.accept(Intent.Nights(5)); vm.accept(Intent.Guests(3)); vm.accept(Intent.Credit(false))
        advanceUntilIdle()
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = RoamViewModel(service, restoredHandle)
        advanceUntilIdle()
        assertEquals(vm.state.value.screen.requestKey, restored.state.value.screen.requestKey)
        assertEquals(5, restored.state.value.screen.nights)
        assertEquals(3, restored.state.value.screen.guests)
        assertFalse(restored.state.value.screen.useCredit)
        assertTrue(restored.state.value.screen.checkout)
        assertEquals(vm.quote(), restored.quote())
    }
    @Test fun `edits rotate key but in-flight controls do not change request`() = runTest(dispatcher) {
        checkout()
        val oldKey = vm.state.value.screen.requestKey
        vm.accept(Intent.Nights(4)); advanceUntilIdle()
        assertNotEquals(oldKey, vm.state.value.screen.requestKey)
        vm.accept(Intent.Reserve); vm.accept(Intent.Guests(4)); vm.accept(Intent.Back)
        advanceUntilIdle()
        assertEquals(2, store.state.value.bookings.single().request.guests)
        assertEquals(4, store.state.value.bookings.single().quote.nights)
    }
    @Test fun `validation keeps editor open with actionable error`() = runTest(dispatcher) {
        vm.accept(Intent.EditProfile(true)); vm.accept(Intent.SaveProfile(" ", "Kyoto", "Hello"))
        advanceUntilIdle()
        assertTrue(vm.state.value.screen.profileEditor)
        assertNotNull(vm.state.value.screen.error)
        assertEquals("Alex Morgan", store.state.value.account.profile.name)
    }
    @Test fun `discovery filters combine query category and favorites`() = runTest(dispatcher) {
        vm.accept(Intent.Query("  japan "))
        advanceUntilIdle()
        assertEquals(listOf("kyoto"), vm.state.value.stays.map { it.id })
        vm.accept(Intent.Category("Nature")); advanceUntilIdle()
        assertTrue(vm.state.value.stays.isEmpty())
        vm.accept(Intent.Category("All stays")); vm.accept(Intent.SaveStay("kyoto")); advanceUntilIdle()
        vm.accept(Intent.SavedOnly); advanceUntilIdle()
        assertEquals(1, vm.state.value.stays.size)
    }
}

private class TestStore : AccountStore {
    val state = MutableStateFlow(AccountSnapshot())
    private val mutex = Mutex()
    override fun observe(): Flow<AccountSnapshot> = state
    override suspend fun <T> transaction(block: suspend AccountTransaction.() -> T): T = mutex.withLock {
        var next = state.value
        val transaction = object : AccountTransaction {
            override suspend fun account() = next.account
            override suspend fun save(account: Account) { next = next.copy(account = account) }
            override suspend fun booking(key: String) = next.bookings.find { it.request.key == key }
            override suspend fun insert(booking: Booking) { next = next.copy(bookings = next.bookings + booking) }
            override suspend fun update(booking: Booking) { next = next.copy(bookings = next.bookings.map { if (it.id == booking.id) booking else it }) }
            override suspend fun append(entry: LedgerEntry) { next = next.copy(ledger = next.ledger + entry) }
        }
        val result = transaction.block()
        state.value = next
        result
    }
}
