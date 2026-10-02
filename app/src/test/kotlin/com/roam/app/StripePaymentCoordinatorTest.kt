package com.roam.app

import com.roam.core.CommerceException
import com.roam.network.PaymentOutcome
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class StripePaymentCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `present waits for SDK result instead of inventing payment success`() =
        runTest(dispatcher) {
            val coordinator = StripePaymentCoordinator()
            var launches = 0
            coordinator.attach { launches++ }
            val outcome = async { coordinator.present("pi_test_secret_example") }
            runCurrent()
            assertEquals(1, launches)
            assertFalse(outcome.isCompleted)
            coordinator.complete(PaymentOutcome.Canceled)
            assertEquals(PaymentOutcome.Canceled, outcome.await())
        }

    @Test
    fun `rotation rebinds the launcher without presenting pending payment twice`() =
        runTest(dispatcher) {
            val coordinator = StripePaymentCoordinator()
            var firstLaunches = 0
            var nextLaunches = 0
            val first: (String) -> Unit = { firstLaunches++ }
            val next: (String) -> Unit = { nextLaunches++ }
            coordinator.attach(first)
            val pending = async { coordinator.present("pi_test_secret_example") }
            runCurrent()
            coordinator.detach(first)
            coordinator.attach(next)
            coordinator.complete(PaymentOutcome.Completed)
            assertEquals(PaymentOutcome.Completed, pending.await())
            assertEquals(1, firstLaunches)
            assertEquals(0, nextLaunches)
            val retry = async { coordinator.present("pi_other_secret_example") }
            runCurrent()
            coordinator.complete(PaymentOutcome.Failed)
            assertEquals(PaymentOutcome.Failed, retry.await())
            assertEquals(1, nextLaunches)
        }

    @Test
    fun `unattached payment UI fails safely and cancellation allows later retry`() =
        runTest(dispatcher) {
            val coordinator = StripePaymentCoordinator()
            assertTrue(
                runCatching { coordinator.present("pi_test_secret_example") }.exceptionOrNull()
                    is CommerceException
            )
            coordinator.attach {}
            val abandoned = async { coordinator.present("pi_test_secret_example") }
            runCurrent()
            abandoned.cancelAndJoin()
            val retry = async { coordinator.present("pi_test_secret_example") }
            runCurrent()
            coordinator.complete(PaymentOutcome.Completed)
            assertEquals(PaymentOutcome.Completed, retry.await())
        }
}
