package com.roam.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roam.core.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val isDemo: Boolean = false,
    val catalog: List<Stay> = emptyList(),
    val quote: Quote? = null,
    val quoteLoading: Boolean = false,
    val quoteError: String? = null,
) {
    val stays: List<Stay>
        get() =
            catalog.filter { stay ->
                (screen.category == "All stays" || screen.category == stay.category) &&
                    (!screen.savedOnly || stay.id in snapshot.account.saved) &&
                    (screen.query.isBlank() ||
                        "${stay.name} ${stay.location} ${stay.country}"
                            .contains(screen.query.trim(), ignoreCase = true))
            }
}

private data class QuoteInput(
    val request: BookingRequest,
    val balance: Money,
    val recoveredQuote: Quote?,
    val revision: Int,
)

private data class QuoteState(
    val request: BookingRequest? = null,
    val quote: Quote? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val balance: Money? = null,
)

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

    data class ResumePayment(val key: String) : Intent

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

    data object RetryQuote : Intent

    data object RefreshAccount : Intent
}

class RoamViewModel(private val commerce: CommerceGateway, private val saved: SavedStateHandle) :
    ViewModel() {
    private val initial = restoreScreen(saved, commerce.today(), commerce.isDemo)
    private val screen = MutableStateFlow(initial)
    private val snapshot = MutableStateFlow<AccountSnapshot?>(null)
    private val catalog = MutableStateFlow<List<Stay>?>(null)
    private val quoted = MutableStateFlow(QuoteState())
    private val quoteRevision = MutableStateFlow(0)
    private val loadError = MutableStateFlow<String?>(null)
    private var observation: Job? = null
    private var refreshing: Job? = null
    val state: StateFlow<RoamState> =
        combine(screen, snapshot, catalog, loadError, quoted) { view, account, stays, error, price
                ->
                val request = view.selectedStay?.let { view.request(it) }
                val matchingQuote =
                    request != null &&
                        price.request == request &&
                        price.balance == account?.account?.balance
                RoamState(
                    screen = view,
                    snapshot = account ?: AccountSnapshot(),
                    loaded = account != null && stays != null,
                    loadError = error,
                    isDemo = commerce.isDemo,
                    catalog = stays.orEmpty(),
                    quote = price.quote.takeIf { matchingQuote },
                    quoteLoading = request != null && (!matchingQuote || price.loading),
                    quoteError = price.error.takeIf { matchingQuote },
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                RoamState(initial, isDemo = commerce.isDemo),
            )

    init {
        persist(initial)
        observe()
        observeQuotes()
    }

    private fun observe() {
        if (observation?.isActive == true) return
        loadError.value = null
        observation =
            viewModelScope.launch {
                try {
                    combine(commerce.snapshots, commerce.catalog) { account, stays ->
                            account to stays
                        }
                        .collect { (account, stays) ->
                            snapshot.value = account
                            catalog.value = stays
                            val current = screen.value
                            if (
                                current.selectedStay != null &&
                                    stays.none { it.id == current.selectedStay }
                            ) {
                                val recovered =
                                    account.bookings.firstOrNull {
                                        it.request.key == current.requestKey
                                    }
                                update {
                                    it.copy(
                                        selectedStay = null,
                                        checkout = false,
                                        receipt = recovered?.request?.key,
                                        destination =
                                            if (recovered != null) Destination.Trips
                                            else it.destination,
                                        error =
                                            if (recovered == null)
                                                "This stay is no longer available."
                                            else null,
                                    )
                                }
                            } else if (current.selectedStay != null) {
                                val stay = stays.first { it.id == current.selectedStay }
                                if (
                                    current.guests > stay.maxGuests &&
                                        account.bookings.none {
                                            it.request.key == current.requestKey
                                        }
                                ) {
                                    draft { it.copy(guests = stay.maxGuests) }
                                }
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    loadError.value = "Your passport couldn't be opened. Try again."
                }
            }
    }

    private fun observeQuotes() {
        viewModelScope.launch {
            combine(screen, snapshot, catalog, quoteRevision) { view, account, stays, revision ->
                    val id = view.selectedStay
                    if (id == null || account == null || stays?.none { it.id == id } != false) null
                    else {
                        val request = view.request(id)
                        QuoteInput(
                            request,
                            account.account.balance,
                            account.bookings.firstOrNull { it.request == request }?.quote,
                            revision,
                        )
                    }
                }
                .distinctUntilChanged()
                .collectLatest { input ->
                    if (input == null) {
                        quoted.value = QuoteState()
                        return@collectLatest
                    }
                    input.recoveredQuote?.let {
                        quoted.value = QuoteState(input.request, it, balance = input.balance)
                        return@collectLatest
                    }
                    quoted.value =
                        QuoteState(input.request, loading = true, balance = input.balance)
                    try {
                        quoted.value =
                            QuoteState(
                                input.request,
                                commerce.quote(input.request),
                                balance = input.balance,
                            )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CommerceException) {
                        quoted.value =
                            QuoteState(input.request, error = e.message, balance = input.balance)
                    } catch (_: Exception) {
                        quoted.value =
                            QuoteState(
                                input.request,
                                error = "We couldn't check this price. Try again.",
                                balance = input.balance,
                            )
                    }
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
        saved["query"] = value.query
        saved["category"] = value.category
        saved["savedOnly"] = value.savedOnly
        saved["ledgerFilter"] = value.ledgerFilter
        saved["profileEditor"] = value.profileEditor
        saved["privacyEditor"] = value.privacyEditor
        saved["demoEditor"] = value.demoEditor
    }

    private fun update(block: (ScreenState) -> ScreenState) {
        screen.update(block)
        persist(screen.value)
    }

    private fun draft(block: (ScreenState) -> ScreenState) {
        if (screen.value.busy) return
        update { current ->
            val next = block(current)
            val changed =
                next.selectedStay != current.selectedStay ||
                    next.checkIn != current.checkIn ||
                    next.nights != current.nights ||
                    next.guests != current.guests ||
                    next.useCredit != current.useCredit
            next.copy(
                requestKey = if (changed) UUID.randomUUID().toString() else current.requestKey,
                error = if (changed) null else current.error,
            )
        }
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
        val id = current.selectedStay ?: return null
        return quoted.value
            .takeIf {
                it.request == current.request(id) &&
                    !it.loading &&
                    it.balance == snapshot.value?.account?.balance
            }
            ?.quote
    }

    fun today() = commerce.today()

    fun refreshAccount() {
        val current = screen.value
        if (
            snapshot.value == null ||
                current.busy ||
                current.profileEditor ||
                current.privacyEditor ||
                refreshing?.isActive == true
        )
            return
        refreshing =
            viewModelScope.launch {
                try {
                    commerce.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CommerceException) {
                    update { it.copy(error = e.message) }
                } catch (_: Exception) {
                    update {
                        it.copy(error = "We couldn't refresh your account. Please try again.")
                    }
                }
            }
    }

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
            is Intent.OpenStay -> {
                val stay = catalog.value?.firstOrNull { it.id == intent.id }
                if (stay == null) {
                    update { it.copy(error = "This stay is no longer available.") }
                    return
                }
                draft {
                    it.copy(
                        selectedStay = intent.id,
                        checkout = false,
                        receipt = null,
                        guests = minOf(it.guests, stay.maxGuests),
                        checkIn = maxOf(it.checkIn, today()),
                    )
                }
            }
            is Intent.Query -> update { it.copy(query = intent.text.take(100)) }
            is Intent.Category -> update { it.copy(category = intent.name) }
            Intent.SavedOnly -> update { it.copy(savedOnly = !it.savedOnly) }
            is Intent.SaveStay -> operation { commerce.toggleSaved(intent.id) }
            Intent.Back ->
                update {
                    when {
                        it.receipt != null -> it.copy(receipt = null, error = null)
                        it.checkout -> it.copy(checkout = false, error = null)
                        else -> it.copy(selectedStay = null, error = null)
                    }
                }
            Intent.Checkout ->
                if (screen.value.selectedStay != null)
                    update { it.copy(checkout = true, error = null) }
            Intent.Reserve -> {
                val current = screen.value
                val id = current.selectedStay ?: return
                if (!current.checkout || quote() == null) return
                operation {
                    if (commerce.isDemo) delay(450)
                    if (commerce.isDemo && current.demoMode == DemoMode.Decline) {
                        update {
                            it.copy(
                                error =
                                    "The sample card was declined. No credits were spent. Choose Normal in Demo controls to retry."
                            )
                        }
                    } else {
                        val booking = commerce.reserve(current.request(id))
                        if (commerce.isDemo && current.demoMode == DemoMode.LostResponse) {
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
                    val booking = commerce.cancel(intent.key)
                    update {
                        it.copy(
                            notice =
                                when {
                                    booking.requiresSupport ->
                                        "Your reservation needs a review. Open its receipt for the support reference."
                                    booking.cancellationPending ->
                                        "Cancellation is still being processed. Refresh your account to check for an update."
                                    commerce.isDemo ->
                                        "Stay cancelled. Your travel credits have been returned."
                                    else ->
                                        "Your reservation is cancelled. Payment refunds may take time to appear."
                                }
                        )
                    }
                }
            is Intent.Receipt ->
                update {
                    it.copy(
                        receipt = intent.key,
                        selectedStay = null,
                        checkout = false,
                        error = null,
                    )
                }
            is Intent.ResumePayment -> {
                if (commerce.isDemo) return
                val pending =
                    snapshot.value?.bookings?.firstOrNull { it.request.key == intent.key } ?: return
                if (
                    !pending.paymentPending ||
                        pending.requiresSupport ||
                        pending.cancellationPending ||
                        pending.cancelled
                )
                    return
                operation {
                    val booking = commerce.reserve(pending.request)
                    update {
                        it.copy(
                            receipt = booking.request.key,
                            selectedStay = null,
                            checkout = false,
                            error = null,
                        )
                    }
                }
            }
            is Intent.Dates -> {
                if (
                    intent.checkIn.isBefore(today()) || intent.checkIn.isAfter(today().plusYears(1))
                ) {
                    update { it.copy(error = "Choose a check-in date within the next year.") }
                } else draft { it.copy(checkIn = intent.checkIn) }
            }
            is Intent.Nights -> draft { it.copy(nights = intent.value.coerceIn(1, 28)) }
            is Intent.Guests ->
                draft {
                    it.copy(
                        guests =
                            intent.value.coerceIn(
                                1,
                                catalog.value
                                    ?.firstOrNull { stay -> stay.id == it.selectedStay }
                                    ?.maxGuests ?: 1,
                            )
                    )
                }
            is Intent.Credit -> draft { it.copy(useCredit = intent.use) }
            Intent.Redeem ->
                if (commerce.isDemo)
                    operation {
                        commerce.redeem("welcome")
                        update { it.copy(notice = "$25 in travel credit is now yours.") }
                    }
            is Intent.EditProfile -> {
                if (intent.show) refreshing?.cancel()
                update { it.copy(profileEditor = intent.show, error = null) }
            }
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
            is Intent.Privacy -> {
                if (intent.show) refreshing?.cancel()
                update { it.copy(privacyEditor = intent.show) }
            }
            is Intent.SavePrivacy ->
                operation {
                    commerce.setPrivacy(intent.hometown, intent.activity)
                    update { it.copy(privacyEditor = false, notice = "Privacy preferences saved.") }
                }
            is Intent.Demo -> if (commerce.isDemo) update { it.copy(demoEditor = intent.show) }
            is Intent.Mode ->
                if (commerce.isDemo)
                    update { it.copy(demoMode = intent.mode, demoEditor = false, error = null) }
            is Intent.LedgerFilter -> update { it.copy(ledgerFilter = intent.name) }
            Intent.DismissNotice -> update { it.copy(notice = null) }
            Intent.RetryLoad -> if (loadError.value != null) observe()
            Intent.RetryQuote -> {
                update { it.copy(error = null) }
                quoted.value = QuoteState()
                quoteRevision.update { it + 1 }
            }
            Intent.RefreshAccount -> refreshAccount()
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

private inline fun <reified T> SavedStateHandle.read(key: String): T? =
    runCatching { get<Any?>(key) as? T }.getOrNull()

/** Restored routes can outlive inventory entries or an older version's input constraints. */
private fun restoreScreen(saved: SavedStateHandle, today: LocalDate, isDemo: Boolean): ScreenState {
    val stayId = saved.read<String>("stay")?.takeIf { it.isNotBlank() }
    val savedNights = saved.read<Int>("nights") ?: 3
    val nights = savedNights.coerceIn(1, 28)
    val savedGuests = saved.read<Int>("guests") ?: 2
    val guests = savedGuests.coerceAtLeast(1)
    val savedDate = saved.read<Long>("checkIn")
    val parsedDate = savedDate?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
    val checkIn =
        parsedDate?.takeIf { runCatching { it.plusDays(nights.toLong()) }.isSuccess }
            ?: today.plusDays(14)
    val changed =
        savedNights != nights ||
            savedGuests != guests ||
            (savedDate != null && checkIn.toEpochDay() != savedDate)
    val key = saved.read<String>("key")?.takeIf { it.isNotBlank() && it.length <= 100 }
    val receipt = saved.read<String>("receipt")?.takeIf { it.isNotBlank() }
    return ScreenState(
        destination =
            runCatching { Destination.valueOf(saved.read<String>("destination") ?: "Explore") }
                .getOrDefault(Destination.Explore),
        selectedStay = if (receipt == null) stayId else null,
        checkout = receipt == null && stayId != null && saved.read<Boolean>("checkout") == true,
        receipt = receipt,
        query = saved.read<String>("query")?.take(100) ?: "",
        category = saved.read<String>("category") ?: "All stays",
        savedOnly = saved.read<Boolean>("savedOnly") ?: false,
        checkIn = checkIn,
        nights = nights,
        guests = guests,
        useCredit = saved.read<Boolean>("credit") ?: true,
        requestKey = if (changed || key == null) UUID.randomUUID().toString() else key,
        profileEditor = saved.read<Boolean>("profileEditor") ?: false,
        privacyEditor = saved.read<Boolean>("privacyEditor") ?: false,
        demoEditor = isDemo && saved.read<Boolean>("demoEditor") == true,
        ledgerFilter =
            saved.read<String>("ledgerFilter")?.takeIf { it in setOf("All", "Earned", "Spent") }
                ?: "All",
    )
}
