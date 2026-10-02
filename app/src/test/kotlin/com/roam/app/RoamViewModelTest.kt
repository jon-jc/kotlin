package com.roam.app

import androidx.lifecycle.SavedStateHandle
import com.roam.core.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoamViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: TestStore
    private lateinit var service: CommerceService
    private lateinit var vm: RoamViewModel
    private lateinit var handle: SavedStateHandle

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        store = TestStore()
        service =
            CommerceService(
                store,
                Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC),
            )
        handle = SavedStateHandle()
        vm = RoamViewModel(service, handle)
        dispatcher.scheduler.runCurrent()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun checkout() {
        vm.accept(Intent.OpenStay("kyoto"))
        vm.accept(Intent.Checkout)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `rapid confirm taps dispatch one reservation`() =
        runTest(dispatcher) {
            checkout()
            repeat(12) { vm.accept(Intent.Reserve) }
            advanceUntilIdle()
            assertEquals(1, store.state.value.bookings.size)
            assertNotNull(vm.state.value.screen.receipt)
            assertFalse(vm.state.value.screen.busy)
        }

    @Test
    fun `decline never mutates wallet or bookings`() =
        runTest(dispatcher) {
            checkout()
            vm.accept(Intent.Mode(DemoMode.Decline))
            vm.accept(Intent.Reserve)
            advanceUntilIdle()
            assertEquals(8500, store.state.value.account.balance.minor)
            assertTrue(store.state.value.bookings.isEmpty())
            assertTrue(vm.state.value.screen.error!!.contains("declined"))
            assertFalse(vm.state.value.screen.busy)
        }

    @Test
    fun `lost response retries same key and displays original price`() =
        runTest(dispatcher) {
            checkout()
            val originalQuote = vm.quote()
            val key = vm.state.value.screen.requestKey
            vm.accept(Intent.Mode(DemoMode.LostResponse))
            vm.accept(Intent.Reserve)
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

    @Test
    fun `saved state restores exact checkout request`() =
        runTest(dispatcher) {
            checkout()
            vm.accept(Intent.Nights(5))
            vm.accept(Intent.Guests(3))
            vm.accept(Intent.Credit(false))
            advanceUntilIdle()
            val restoredHandle =
                SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
            val restored = RoamViewModel(service, restoredHandle)
            advanceUntilIdle()
            assertEquals(vm.state.value.screen.requestKey, restored.state.value.screen.requestKey)
            assertEquals(5, restored.state.value.screen.nights)
            assertEquals(3, restored.state.value.screen.guests)
            assertFalse(restored.state.value.screen.useCredit)
            assertTrue(restored.state.value.screen.checkout)
            assertEquals(vm.quote(), restored.quote())
        }

    @Test
    fun `edits rotate key but in-flight controls do not change request`() =
        runTest(dispatcher) {
            checkout()
            val oldKey = vm.state.value.screen.requestKey
            vm.accept(Intent.Nights(4))
            advanceUntilIdle()
            assertNotEquals(oldKey, vm.state.value.screen.requestKey)
            vm.accept(Intent.Reserve)
            vm.accept(Intent.Guests(4))
            vm.accept(Intent.Back)
            advanceUntilIdle()
            assertEquals(2, store.state.value.bookings.single().request.guests)
            assertEquals(4, store.state.value.bookings.single().quote.nights)
        }

    @Test
    fun `validation keeps editor open with actionable error`() =
        runTest(dispatcher) {
            vm.accept(Intent.EditProfile(true))
            vm.accept(Intent.SaveProfile(" ", "Kyoto", "Hello"))
            advanceUntilIdle()
            assertTrue(vm.state.value.screen.profileEditor)
            assertNotNull(vm.state.value.screen.error)
            assertEquals("Alex Morgan", store.state.value.account.profile.name)
        }

    @Test
    fun `discovery filters combine query category and favorites`() =
        runTest(dispatcher) {
            vm.accept(Intent.Query("  japan "))
            advanceUntilIdle()
            assertEquals(listOf("kyoto"), vm.state.value.stays.map { it.id })
            vm.accept(Intent.Category("Nature"))
            advanceUntilIdle()
            assertTrue(vm.state.value.stays.isEmpty())
            vm.accept(Intent.Category("All stays"))
            vm.accept(Intent.SaveStay("kyoto"))
            advanceUntilIdle()
            vm.accept(Intent.SavedOnly)
            advanceUntilIdle()
            assertEquals(1, vm.state.value.stays.size)
        }

    @Test
    fun `confirming unchanged dates after a lost response recovers one reservation`() =
        runTest(dispatcher) {
            checkout()
            vm.accept(Intent.Mode(DemoMode.LostResponse))
            vm.accept(Intent.Reserve)
            advanceUntilIdle()
            val committed = store.state.value.bookings.single()
            vm.accept(Intent.Back)
            vm.accept(Intent.Dates(committed.request.checkIn))
            vm.accept(Intent.Nights(committed.quote.nights.toInt()))
            vm.accept(Intent.Guests(committed.request.guests))
            vm.accept(Intent.Credit(committed.request.useCredit))
            vm.accept(Intent.Checkout)
            advanceUntilIdle()
            assertEquals(committed.request.key, vm.state.value.screen.requestKey)
            assertEquals(committed.quote, vm.quote())
            vm.accept(Intent.Reserve)
            advanceUntilIdle()
            assertEquals(1, store.state.value.bookings.size)
            assertEquals(committed.request.key, vm.state.value.screen.receipt)
        }

    @Test
    fun `receipt back preserves wallet origin`() =
        runTest(dispatcher) {
            vm.accept(Intent.Navigate(Destination.Wallet))
            vm.accept(Intent.Receipt("missing-reservation"))
            vm.accept(Intent.Back)
            advanceUntilIdle()
            assertNull(vm.state.value.screen.receipt)
            assertEquals(Destination.Wallet, vm.state.value.screen.destination)
        }

    @Test
    fun `stale restored stay returns to discovery after loading`() =
        runTest(dispatcher) {
            val restored =
                RoamViewModel(
                    service,
                    SavedStateHandle(mapOf("stay" to "removed", "checkout" to true)),
                )
            advanceUntilIdle()
            assertTrue(restored.state.value.loaded)
            assertNull(restored.state.value.screen.selectedStay)
            assertFalse(restored.state.value.screen.checkout)
            assertNotNull(restored.state.value.screen.error)
        }

    @Test
    fun `malformed saved checkout is normalized with a new request identity`() =
        runTest(dispatcher) {
            val restored =
                RoamViewModel(
                    service,
                    SavedStateHandle(
                        mapOf(
                            "stay" to "kyoto",
                            "checkout" to true,
                            "checkIn" to Long.MAX_VALUE,
                            "nights" to -3,
                            "guests" to 90,
                            "key" to "old-key",
                        )
                    ),
                )
            advanceUntilIdle()
            assertEquals(service.today().plusDays(14), restored.state.value.screen.checkIn)
            assertEquals(1, restored.state.value.screen.nights)
            assertEquals(4, restored.state.value.screen.guests)
            assertNotEquals("old-key", restored.state.value.screen.requestKey)
            assertNotNull(restored.quote())
        }

    @Test
    fun `retry taps create a single observer subscription`() =
        runTest(dispatcher) {
            var subscriptions = 0
            val gateway =
                object : CommerceGateway by service {
                    override val snapshots = flow {
                        subscriptions++
                        if (subscriptions == 1) error("Unavailable")
                        emitAll(store.state)
                    }
                }
            val model = RoamViewModel(gateway, SavedStateHandle())
            advanceUntilIdle()
            assertNotNull(model.state.value.loadError)
            repeat(12) { model.accept(Intent.RetryLoad) }
            advanceUntilIdle()
            assertEquals(2, subscriptions)
            assertTrue(model.state.value.loaded)
            assertNull(model.state.value.loadError)
        }

    @Test
    fun `remote catalog and quote replace all bundled pricing assumptions`() =
        runTest(dispatcher) {
            val remoteStay =
                Catalog.stays
                    .first()
                    .copy(id = "remote-only", category = "Cabin", nightly = Money(99999))
            val authoritative =
                Quote(3, Money(30000), Money(1700), Money(31700), Money(0), Money(31700))
            val gateway =
                object : CommerceGateway by service {
                    override val isDemo = false
                    override val catalog = flowOf(listOf(remoteStay))

                    override suspend fun quote(request: BookingRequest) = authoritative
                }
            val model =
                RoamViewModel(
                    gateway,
                    SavedStateHandle(mapOf("stay" to "remote-only", "checkout" to true)),
                )
            advanceUntilIdle()
            assertFalse(model.state.value.isDemo)
            assertEquals(listOf(remoteStay), model.state.value.stays)
            assertEquals("remote-only", model.state.value.screen.selectedStay)
            assertEquals(authoritative, model.quote())
            assertEquals(authoritative, model.state.value.quote)
        }

    @Test
    fun `only the latest draft may publish a quote`() =
        runTest(dispatcher) {
            val gateway =
                object : CommerceGateway by service {
                    override suspend fun quote(request: BookingRequest): Quote {
                        delay(if (request.guests == 2) 1000 else 50)
                        val original = service.quote(request)
                        val fee = Money(request.guests.toLong())
                        val total = original.subtotal + fee
                        val credit = Money(minOf(original.credit.minor, total.minor))
                        return original.copy(
                            serviceFee = fee,
                            total = total,
                            credit = credit,
                            due = total - credit,
                        )
                    }
                }
            val model = RoamViewModel(gateway, SavedStateHandle())
            runCurrent()
            model.accept(Intent.OpenStay("kyoto"))
            model.accept(Intent.Checkout)
            runCurrent()
            assertTrue(model.state.value.quoteLoading)
            assertNull(model.quote())
            model.accept(Intent.Reserve)
            assertTrue(store.state.value.bookings.isEmpty())
            model.accept(Intent.Guests(3))
            advanceUntilIdle()
            assertEquals(Money(3), model.state.value.quote?.serviceFee)
            assertFalse(model.state.value.quoteLoading)
        }

    @Test
    fun `failed price request can retry without changing reservation identity`() =
        runTest(dispatcher) {
            var attempts = 0
            val gateway =
                object : CommerceGateway by service {
                    override suspend fun quote(request: BookingRequest): Quote {
                        if (++attempts == 1) throw CommerceException("Price unavailable")
                        return service.quote(request)
                    }
                }
            val model = RoamViewModel(gateway, SavedStateHandle())
            runCurrent()
            model.accept(Intent.OpenStay("kyoto"))
            model.accept(Intent.Checkout)
            advanceUntilIdle()
            val key = model.state.value.screen.requestKey
            assertEquals("Price unavailable", model.state.value.quoteError)
            assertNull(model.quote())
            model.accept(Intent.RetryQuote)
            advanceUntilIdle()
            assertEquals(key, model.state.value.screen.requestKey)
            assertNotNull(model.quote())
            assertNull(model.state.value.quoteError)
        }

    @Test
    fun `live session rejects simulation and fabricated benefit actions`() =
        runTest(dispatcher) {
            val gateway =
                object : CommerceGateway by service {
                    override val isDemo = false
                }
            val model = RoamViewModel(gateway, SavedStateHandle(mapOf("demoEditor" to true)))
            runCurrent()
            model.accept(Intent.Demo(true))
            model.accept(Intent.Mode(DemoMode.Decline))
            model.accept(Intent.Redeem)
            advanceUntilIdle()
            assertFalse(model.state.value.screen.demoEditor)
            assertEquals(DemoMode.Normal, model.state.value.screen.demoMode)
            assertEquals(8500, store.state.value.account.balance.minor)
            assertTrue(store.state.value.account.redeemed.isEmpty())
        }

    @Test
    fun `process restoration after a committed response loss recovers original booking`() =
        runTest(dispatcher) {
            checkout()
            vm.accept(Intent.Mode(DemoMode.LostResponse))
            vm.accept(Intent.Reserve)
            advanceUntilIdle()
            val booking = store.state.value.bookings.single()
            val restored =
                RoamViewModel(
                    service,
                    SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
                )
            advanceUntilIdle()
            assertEquals(booking.request.key, restored.state.value.screen.requestKey)
            assertEquals(booking.quote, restored.quote())
            restored.accept(Intent.Reserve)
            advanceUntilIdle()
            assertEquals(1, store.state.value.bookings.size)
            assertEquals(booking.request.key, restored.state.value.screen.receipt)
        }

    @Test
    fun `pending trip resumes its stored request even when inventory left the catalog`() =
        runTest(dispatcher) {
            val request =
                BookingRequest(
                    "persistent-key",
                    "removed-stay",
                    service.today().plusDays(2),
                    service.today().plusDays(5),
                    2,
                    false,
                )
            val pending =
                Booking(
                    "pending-booking",
                    request,
                    Quote(3, Money(30000), Money(1200), Money(31200), Money(0), Money(31200)),
                    0,
                    stay = BookedStay("Original stay", "Original location", "Portugal", "coast"),
                    simulated = false,
                    paymentPending = true,
                )
            store.state.value = AccountSnapshot(bookings = listOf(pending))
            var resumed: BookingRequest? = null
            val gateway =
                object : CommerceGateway by service {
                    override val isDemo = false
                    override val catalog = flowOf(emptyList<Stay>())

                    override suspend fun reserve(request: BookingRequest): Booking {
                        resumed = request
                        val confirmed = pending.copy(paymentPending = false)
                        store.state.value = store.state.value.copy(bookings = listOf(confirmed))
                        return confirmed
                    }
                }
            val model = RoamViewModel(gateway, SavedStateHandle())
            runCurrent()
            model.accept(Intent.Receipt(request.key))
            model.accept(Intent.ResumePayment(request.key))
            advanceUntilIdle()
            assertEquals(request, resumed)
            assertEquals(request.key, model.state.value.screen.receipt)
            assertFalse(model.state.value.snapshot.bookings.single().paymentPending)
        }

    @Test
    fun `background refresh cannot bless an old profile draft with a new server revision`() =
        runTest(dispatcher) {
            var serverRevision = 1
            var clientRevision = 1
            var submittedRevision: Int? = null
            var refreshes = 0
            val gateway =
                object : CommerceGateway by service {
                    override suspend fun refresh() {
                        refreshes++
                        clientRevision = serverRevision
                        store.state.value =
                            store.state.value.copy(
                                account =
                                    store.state.value.account.copy(
                                        profile = Profile(name = "Other device edit")
                                    )
                            )
                    }

                    override suspend fun editProfile(name: String, hometown: String, bio: String) {
                        submittedRevision = clientRevision
                        if (clientRevision != serverRevision)
                            throw CommerceException(
                                "Your profile changed elsewhere. Refresh before editing again."
                            )
                        service.editProfile(name, hometown, bio)
                    }
                }
            val model = RoamViewModel(gateway, SavedStateHandle())
            runCurrent()
            model.accept(Intent.EditProfile(true))
            serverRevision = 2
            model.refreshAccount()
            runCurrent()
            model.accept(Intent.SaveProfile("Old local draft", "San Francisco", "Draft"))
            advanceUntilIdle()
            assertEquals(0, refreshes)
            assertEquals(1, submittedRevision)
            assertTrue(model.state.value.screen.profileEditor)
            assertNotNull(model.state.value.screen.error)
            assertEquals("Alex Morgan", store.state.value.account.profile.name)
            model.accept(Intent.EditProfile(false))
            model.refreshAccount()
            advanceUntilIdle()
            assertEquals(1, refreshes)
            assertEquals("Other device edit", model.state.value.snapshot.account.profile.name)
        }

    @Test
    fun `opening either editor cancels a refresh already waiting on the network`() =
        runTest(dispatcher) {
            for (openEditor in listOf(Intent.EditProfile(true), Intent.Privacy(true))) {
                val gate = CompletableDeferred<Unit>()
                var cancelled = false
                var published = false
                val gateway =
                    object : CommerceGateway by service {
                        override suspend fun refresh() {
                            try {
                                gate.await()
                                published = true
                            } finally {
                                cancelled = !currentCoroutineContext().isActive
                            }
                        }
                    }
                val model = RoamViewModel(gateway, SavedStateHandle())
                runCurrent()
                model.refreshAccount()
                runCurrent()
                model.accept(openEditor)
                gate.complete(Unit)
                advanceUntilIdle()
                assertTrue(cancelled)
                assertFalse(published)
                assertNull(model.state.value.screen.error)
            }
        }
}

private class TestStore : AccountStore {
    val state = MutableStateFlow(AccountSnapshot())
    private val mutex = Mutex()

    override fun observe(): Flow<AccountSnapshot> = state

    override suspend fun <T> transaction(block: suspend AccountTransaction.() -> T): T =
        mutex.withLock {
            var next = state.value
            val transaction =
                object : AccountTransaction {
                    override suspend fun account() = next.account

                    override suspend fun save(account: Account) {
                        next = next.copy(account = account)
                    }

                    override suspend fun booking(key: String) =
                        next.bookings.find { it.request.key == key }

                    override suspend fun insert(booking: Booking) {
                        next = next.copy(bookings = next.bookings + booking)
                    }

                    override suspend fun update(booking: Booking) {
                        next =
                            next.copy(
                                bookings =
                                    next.bookings.map { if (it.id == booking.id) booking else it }
                            )
                    }

                    override suspend fun append(entry: LedgerEntry) {
                        next = next.copy(ledger = next.ledger + entry)
                    }
                }
            val result = transaction.block()
            state.value = next
            result
        }
}
