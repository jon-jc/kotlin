package com.roam.app

import androidx.lifecycle.ViewModel
import com.roam.core.CommerceException
import com.roam.network.PaymentCoordinator
import com.roam.network.PaymentOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Retained by the session's navigation entry; no payment secret is persisted or exposed as UI
 * state.
 */
class StripePaymentCoordinator : ViewModel(), PaymentCoordinator {
    private var launcher: ((String) -> Unit)? = null
    private var pending: CompletableDeferred<PaymentOutcome>? = null

    fun attach(present: (String) -> Unit) {
        launcher = present
    }

    fun detach(present: (String) -> Unit) {
        if (launcher === present) launcher = null
    }

    override suspend fun present(clientSecret: String): PaymentOutcome =
        withContext(Dispatchers.Main.immediate) {
            val present =
                launcher ?: throw CommerceException("Payment isn't ready. Please try again.")
            if (pending != null) throw CommerceException("A payment is already in progress.")
            val result = CompletableDeferred<PaymentOutcome>()
            pending = result
            try {
                present(clientSecret)
                result.await()
            } finally {
                if (pending === result) pending = null
                result.cancel()
            }
        }

    fun complete(outcome: PaymentOutcome) {
        pending?.complete(outcome)
    }

    override fun onCleared() {
        launcher = null
        pending?.cancel()
        pending = null
    }
}
