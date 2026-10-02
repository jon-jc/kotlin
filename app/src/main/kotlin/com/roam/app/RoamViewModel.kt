package com.roam.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roam.core.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Destination {
    Explore,
    Trips,
    Wallet,
    Passport,
}

enum class DemoMode {
    Normal,
    Decline,
    LostResponse,
}

data class ScreenState(
    val destination: Destination = Destination.Explore,
    val selectedStay: String? = null,
    val checkout: Boolean = false,
    val receipt: String? = null,
    val query: String = "",
    val category: String = "All stays",
    val savedOnly: Boolean = false,
    val checkIn: LocalDate,
    val nights: Int = 3,
    val guests: Int = 2,
    val useCredit: Boolean = true,
    val requestKey: String,
    val busy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    val demoMode: DemoMode = DemoMode.Normal,
    val profileEditor: Boolean = false,
    val privacyEditor: Boolean = false,
    val demoEditor: Boolean = false,
    val ledgerFilter: String = "All",
)

data class RoamState(
    val screen: ScreenState,
    val snapshot: AccountSnapshot = AccountSnapshot(),
    val loaded: Boolean = false,
    val loadError: String? = null,
) {
    val stays: List<Stay>
        get() =
            Catalog.stays.filter { stay ->
                (screen.category == "All stays" || screen.category == stay.category) &&
                    (!screen.savedOnly || stay.id in snapshot.account.saved) &&
                    (screen.query.isBlank() ||
                        "${stay.name} ${stay.location} ${stay.country}"
                            .contains(screen.query.trim(), ignoreCase = true))
            }
}

sealed interface Intent {
    data class Navigate(val destination: Destination) : Intent

    data class OpenStay(val id: String) : Intent

    data class Query(val text: String) : Intent

    data class Category(val name: String) : Intent

    data object SavedOnly : Intent

    data class SaveStay(val id: String) : Intent

    data object Back : Intent

    data object Checkout : Intent

    data object Reserve : Intent

    data class Cancel(val key: String) : Intent

    data class Receipt(val key: String) : Intent

    data class Dates(val checkIn: LocalDate) : Intent

    data class Nights(val value: Int) : Intent

    data class Guests(val value: Int) : Intent

    data class Credit(val use: Boolean) : Intent

    data object Redeem : Intent

    data class EditProfile(val show: Boolean) : Intent

    data class SaveProfile(val name: String, val hometown: String, val bio: String) : Intent

    data class Privacy(val show: Boolean) : Intent

    data class SavePrivacy(val hometown: Boolean, val activity: Boolean) : Intent

    data class Demo(val show: Boolean) : Intent

    data class Mode(val mode: DemoMode) : Intent

    data class LedgerFilter(val name: String) : Intent

    data object DismissNotice : Intent

    data object RetryLoad : Intent
}

class RoamViewModel(private val commerce: CommerceService, private val saved: SavedStateHandle) :
    ViewModel() {
    private val initial =
        ScreenState(
            destination =
                runCatching { Destination.valueOf(saved["destination"] ?: "Explore") }
                    .getOrDefault(Destination.Explore),
            selectedStay = saved["stay"],
            checkout = saved["checkout"] ?: false,
            receipt = saved["receipt"],
            checkIn =
                LocalDate.ofEpochDay(
                    saved["checkIn"] ?: commerce.today().plusDays(14).toEpochDay()
                ),
            nights = saved["nights"] ?: 3,
            guests = saved["guests"] ?: 2,
            useCredit = saved["credit"] ?: true,
            requestKey = saved.get<String>("key") ?: UUID.randomUUID().toString(),
        )
    private val screen = MutableStateFlow(initial)
    private val snapshot = MutableStateFlow<AccountSnapshot?>(null)
    private val loadError = MutableStateFlow<String?>(null)
    val state: StateFlow<RoamState> =
        combine(screen, snapshot, loadError) { view, account, error ->
                RoamState(view, account ?: AccountSnapshot(), account != null, error)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RoamState(initial))

    init {
        persist(initial)
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            loadError.value = null
            try {
                commerce.snapshots.collect { snapshot.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                loadError.value = "Your passport couldn't be opened. Try again."
            }
        }
    }

    private fun persist(value: ScreenState) {
        saved["destination"] = value.destination.name
        saved["stay"] = value.selectedStay
        saved["checkout"] = value.checkout
        saved["receipt"] = value.receipt
        saved["checkIn"] = value.checkIn.toEpochDay()
        saved["nights"] = value.nights
        saved["guests"] = value.guests
        saved["credit"] = value.useCredit
        saved["key"] = value.requestKey
    }

    private fun update(block: (ScreenState) -> ScreenState) {
        screen.update(block)
        persist(screen.value)
    }

    private fun draft(block: (ScreenState) -> ScreenState) {
        if (!screen.value.busy)
            update { block(it).copy(requestKey = UUID.randomUUID().toString(), error = null) }
    }

    private fun operation(block: suspend () -> Unit) {
        if (screen.value.busy || snapshot.value == null) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CommerceException) {
                update { it.copy(error = e.message) }
            } catch (_: Exception) {
                update { it.copy(error = "We couldn't save that change. Please try again.") }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    fun quote(): Quote? {
        val current = screen.value
        val account = snapshot.value?.account ?: return null
        val id = current.selectedStay ?: return null
        snapshot.value
            ?.bookings
            ?.firstOrNull { it.request.key == current.requestKey }
            ?.let {
                return it.quote
            }
        return runCatching {
                BookingPolicy.quote(current.request(id), account.balance, commerce.today())
            }
            .getOrNull()
    }

    fun today() = commerce.today()

    fun accept(intent: Intent) {
        // Freeze checkout fields and navigation while a transaction is in flight.
        if (screen.value.busy && intent !is Intent.DismissNotice) return
        when (intent) {
            is Intent.Navigate ->
                update {
                    it.copy(
                        destination = intent.destination,
                        selectedStay = null,
                        receipt = null,
                        checkout = false,
                        error = null,
                    )
                }
            is Intent.OpenStay ->
                draft {
                    it.copy(
                        selectedStay = intent.id,
                        checkout = false,
                        receipt = null,
                        guests = minOf(it.guests, Catalog.find(intent.id).maxGuests),
                        checkIn = maxOf(it.checkIn, today()),
                    )
                }
            is Intent.Query -> update { it.copy(query = intent.text.take(100)) }
            is Intent.Category -> update { it.copy(category = intent.name) }
            Intent.SavedOnly -> update { it.copy(savedOnly = !it.savedOnly) }
            is Intent.SaveStay -> operation { commerce.toggleSaved(intent.id) }
            Intent.Back ->
                update {
                    when {
                        it.receipt != null ->
                            it.copy(receipt = null, destination = Destination.Trips)
                        it.checkout -> it.copy(checkout = false, error = null)
                        else -> it.copy(selectedStay = null, error = null)
                    }
                }
            Intent.Checkout -> update { it.copy(checkout = true, error = null) }
            Intent.Reserve -> {
                val current = screen.value
                val id = current.selectedStay ?: return
                operation {
                    delay(450)
                    if (current.demoMode == DemoMode.Decline) {
                        update {
                            it.copy(
                                error =
                                    "The sample card was declined. No credits were spent. Choose Normal in Demo controls to retry."
                            )
                        }
                    } else {
                        val booking = commerce.reserve(current.request(id))
                        if (current.demoMode == DemoMode.LostResponse) {
                            update {
                                it.copy(
                                    demoMode = DemoMode.Normal,
                                    error =
                                        "The booking was saved, but its response was interrupted. Retry safely to recover your receipt.",
                                )
                            }
                        } else
                            update {
                                it.copy(
                                    receipt = booking.request.key,
                                    selectedStay = null,
                                    checkout = false,
                                    destination = Destination.Trips,
                                    error = null,
                                )
                            }
                    }
                }
            }
            is Intent.Cancel ->
                operation {
                    commerce.cancel(intent.key)
                    update {
                        it.copy(notice = "Stay cancelled. Your travel credits have been returned.")
                    }
                }
            is Intent.Receipt -> update { it.copy(receipt = intent.key, error = null) }
            is Intent.Dates -> draft { it.copy(checkIn = intent.checkIn) }
            is Intent.Nights -> draft { it.copy(nights = intent.value.coerceIn(1, 28)) }
            is Intent.Guests ->
                draft {
                    it.copy(
                        guests =
                            intent.value.coerceIn(
                                1,
                                Catalog.find(it.selectedStay ?: "kyoto").maxGuests,
                            )
                    )
                }
            is Intent.Credit -> draft { it.copy(useCredit = intent.use) }
            Intent.Redeem ->
                operation {
                    commerce.redeem("welcome")
                    update { it.copy(notice = "$25 in travel credit is now yours.") }
                }
            is Intent.EditProfile -> update { it.copy(profileEditor = intent.show, error = null) }
            is Intent.SaveProfile ->
                operation {
                    commerce.editProfile(intent.name, intent.hometown, intent.bio)
                    update {
                        it.copy(
                            profileEditor = false,
                            notice = "Your passport feels a little more like you.",
                        )
                    }
                }
            is Intent.Privacy -> update { it.copy(privacyEditor = intent.show) }
            is Intent.SavePrivacy ->
                operation {
                    commerce.setPrivacy(intent.hometown, intent.activity)
                    update { it.copy(privacyEditor = false, notice = "Privacy preferences saved.") }
                }
            is Intent.Demo -> update { it.copy(demoEditor = intent.show) }
            is Intent.Mode ->
                update { it.copy(demoMode = intent.mode, demoEditor = false, error = null) }
            is Intent.LedgerFilter -> update { it.copy(ledgerFilter = intent.name) }
            Intent.DismissNotice -> update { it.copy(notice = null) }
            Intent.RetryLoad -> if (loadError.value != null) observe()
        }
    }

    private fun ScreenState.request(id: String) =
        BookingRequest(
            requestKey,
            id,
            checkIn,
            checkIn.plusDays(nights.toLong()),
            guests,
            useCredit,
        )
}
