package com.roam.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roam.core.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ComparisonSort {
    RELEVANCE,
    TOTAL,
}

data class ComparisonDraft(
    val destination: String,
    val checkIn: String,
    val checkOut: String,
    val adults: Int = 2,
    val childAges: List<Int> = emptyList(),
    val currency: String = "USD",
    val market: String = "us",
    val scope: ComparisonScope = ComparisonScope.ALL,
) {
    fun validated(today: LocalDate): ComparisonQuery =
        ComparisonQuery(
                destination.trim(),
                checkIn,
                checkOut,
                adults,
                childAges,
                currency,
                market,
                scope,
            )
            .also { it.validate(today) }

    fun matches(query: ComparisonQuery): Boolean =
        destination.trim() == query.destination &&
            checkIn == query.checkIn &&
            checkOut == query.checkOut &&
            adults == query.adults &&
            childAges == query.childAges &&
            currency == query.currency &&
            market == query.market &&
            scope == query.scope

    companion object {
        fun from(query: ComparisonQuery) =
            ComparisonDraft(
                query.destination,
                query.checkIn,
                query.checkOut,
                query.adults,
                query.childAges,
                query.currency,
                query.market,
                query.scope,
            )
    }
}

data class ComparisonUiState(
    val query: ComparisonDraft,
    val result: ComparisonResult? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val selected: ComparisonProperty? = null,
    val details: ComparisonDetails? = null,
    val detailsLoading: Boolean = false,
    val detailsError: String? = null,
    val sort: ComparisonSort = ComparisonSort.RELEVANCE,
    val now: Instant = Instant.now(),
) {
    val properties: List<ComparisonProperty>
        get() =
            result?.properties.orEmpty().let { properties ->
                if (sort == ComparisonSort.RELEVANCE) properties
                else
                    properties.sortedWith(
                        compareBy<ComparisonProperty> {
                                comparisonTotal(it.summaryPrice, query.currency) == null
                            }
                            .thenBy { comparisonTotal(it.summaryPrice, query.currency) }
                    )
            }

    val rates: List<ComparisonRate>
        get() =
            details
                ?.rates
                .orEmpty()
                .sortedWith(
                    compareBy<ComparisonRate> { comparisonTotal(it.price, query.currency) == null }
                        .thenBy { comparisonTotal(it.price, query.currency) }
                )

    val resultFresh: Boolean
        get() = result?.let { comparisonFresh(it.checkedAt, it.expiresAt, now) } == true

    val detailsFresh: Boolean
        get() = details?.let { comparisonFresh(it.checkedAt, it.expiresAt, now) } == true
}

internal fun comparisonTotal(price: ComparisonPrice?, currency: String): Long? =
    price?.comparableTotal(currency)?.let { runCatching { it.minorUnits() }.getOrNull() }

internal fun comparisonFresh(checkedAt: String, expiresAt: String, now: Instant): Boolean =
    runCatching {
            val checked = Instant.parse(checkedAt)
            val expiry = minOf(Instant.parse(expiresAt), checked.plusSeconds(300))
            !checked.isAfter(now.plusSeconds(30)) && expiry.isAfter(checked) && now.isBefore(expiry)
        }
        .getOrDefault(false)

sealed interface ComparisonIntent {
    data class Destination(val value: String) : ComparisonIntent

    data class Dates(val checkIn: LocalDate, val checkOut: LocalDate) : ComparisonIntent

    data class Adults(val value: Int) : ComparisonIntent

    data object AddChild : ComparisonIntent

    data class ChildAge(val index: Int, val value: Int) : ComparisonIntent

    data class RemoveChild(val index: Int) : ComparisonIntent

    data class Currency(val value: String) : ComparisonIntent

    data class Market(val value: String) : ComparisonIntent

    data class Scope(val value: ComparisonScope) : ComparisonIntent

    data class Sort(val value: ComparisonSort) : ComparisonIntent

    data object Search : ComparisonIntent

    data class OpenProperty(val id: String) : ComparisonIntent

    data object CloseDetails : ComparisonIntent

    data object RefreshDetails : ComparisonIntent
}

/** Search criteria survive recreation; provider prices and links must always be fetched again. */
class ComparisonViewModel(
    private val gateway: ComparisonGateway,
    private val saved: SavedStateHandle,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val current =
        MutableStateFlow(
            ComparisonUiState(restoreComparison(saved, LocalDate.now(clock)), now = clock.instant())
        )
    val state = current.asStateFlow()
    private var searchJob: Job? = null
    private var detailsJob: Job? = null
    private var generation = 0L
    private var detailGeneration = 0L

    init {
        persist(current.value.query)
    }

    fun today(): LocalDate = LocalDate.now(clock)

    fun updateTime() {
        current.update { it.copy(now = clock.instant()) }
    }

    fun accept(intent: ComparisonIntent) {
        when (intent) {
            is ComparisonIntent.Destination ->
                edit { it.copy(destination = intent.value.take(120)) }
            is ComparisonIntent.Dates ->
                edit {
                    it.copy(
                        checkIn = intent.checkIn.toString(),
                        checkOut = intent.checkOut.toString(),
                    )
                }
            is ComparisonIntent.Adults -> edit { it.copy(adults = intent.value.coerceIn(1, 6)) }
            ComparisonIntent.AddChild ->
                edit { if (it.childAges.size < 4) it.copy(childAges = it.childAges + 1) else it }
            is ComparisonIntent.ChildAge ->
                edit {
                    it.copy(
                        childAges =
                            it.childAges.mapIndexed { index, age ->
                                if (index == intent.index) intent.value.coerceIn(1, 17) else age
                            }
                    )
                }
            is ComparisonIntent.RemoveChild ->
                edit {
                    it.copy(
                        childAges = it.childAges.filterIndexed { index, _ -> index != intent.index }
                    )
                }
            is ComparisonIntent.Currency ->
                if (intent.value in comparisonCurrencies) edit { it.copy(currency = intent.value) }
            is ComparisonIntent.Market ->
                if (intent.value in comparisonMarkets.map { it.first })
                    edit { it.copy(market = intent.value) }
            is ComparisonIntent.Scope -> edit { it.copy(scope = intent.value) }
            is ComparisonIntent.Sort -> current.update { it.copy(sort = intent.value) }
            ComparisonIntent.Search -> search()
            is ComparisonIntent.OpenProperty ->
                current.value.result
                    ?.properties
                    ?.firstOrNull { it.id == intent.id }
                    ?.let(::openDetails)
            ComparisonIntent.CloseDetails -> {
                detailGeneration++
                detailsJob?.cancel()
                current.update {
                    it.copy(
                        selected = null,
                        details = null,
                        detailsLoading = false,
                        detailsError = null,
                    )
                }
            }
            ComparisonIntent.RefreshDetails -> current.value.selected?.let(::openDetails)
        }
    }

    private fun edit(change: (ComparisonDraft) -> ComparisonDraft) {
        val query = change(current.value.query)
        if (query == current.value.query) return
        generation++
        detailGeneration++
        searchJob?.cancel()
        detailsJob?.cancel()
        current.value = ComparisonUiState(query, sort = current.value.sort, now = clock.instant())
        persist(query)
    }

    private fun validatedQuery(): ComparisonQuery? {
        val query =
            try {
                current.value.query.validated(today())
            } catch (error: IllegalArgumentException) {
                current.update {
                    it.copy(error = error.message ?: "Check your destination, dates, and guests.")
                }
                return null
            }
        val normalized = ComparisonDraft.from(query)
        if (normalized != current.value.query) edit { normalized }
        return query
    }

    fun airbnbQuery(): ComparisonQuery? = validatedQuery()

    private fun search() {
        val query = validatedQuery() ?: return
        val requestGeneration = ++generation
        detailGeneration++
        searchJob?.cancel()
        detailsJob?.cancel()
        current.update {
            it.copy(
                result = null,
                loading = true,
                error = null,
                selected = null,
                details = null,
                detailsLoading = false,
                detailsError = null,
                now = clock.instant(),
            )
        }
        searchJob =
            viewModelScope.launch {
                try {
                    val result = gateway.search(query)
                    if (generation == requestGeneration && current.value.query.matches(query)) {
                        check(result.query == query) {
                            "The search response belongs to another trip."
                        }
                        current.update {
                            it.copy(result = result, loading = false, now = clock.instant())
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == requestGeneration)
                        current.update {
                            it.copy(
                                loading = false,
                                error =
                                    if (error is ComparisonException) error.message
                                    else "We couldn't compare prices. Please try again.",
                            )
                        }
                }
            }
    }

    private fun openDetails(property: ComparisonProperty) {
        val query = current.value.result?.query ?: return
        if (!current.value.query.matches(query)) return
        val requestGeneration = generation
        val requestDetail = ++detailGeneration
        detailsJob?.cancel()
        current.update {
            it.copy(
                selected = property,
                details = null,
                detailsLoading = true,
                detailsError = null,
                now = clock.instant(),
            )
        }
        detailsJob =
            viewModelScope.launch {
                try {
                    val details = gateway.details(query, property.propertyToken, property.kind)
                    if (
                        generation == requestGeneration &&
                            detailGeneration == requestDetail &&
                            current.value.selected?.id == property.id
                    ) {
                        check(
                            details.query == query &&
                                details.propertyToken == property.propertyToken &&
                                details.kind == property.kind
                        )
                        current.update {
                            it.copy(
                                details = details,
                                detailsLoading = false,
                                now = clock.instant(),
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (generation == requestGeneration && detailGeneration == requestDetail)
                        current.update {
                            it.copy(
                                detailsLoading = false,
                                detailsError =
                                    if (error is ComparisonException) error.message
                                    else "We couldn't load booking options. Please try again.",
                            )
                        }
                }
            }
    }

    /** Revalidate at the moment of handoff, even if the screen's freshness timer has not ticked. */
    fun bookingUrl(rateId: String): String? {
        updateTime()
        val state = current.value
        val details = state.details ?: return null
        if (state.detailsLoading || !state.query.matches(details.query) || !state.detailsFresh) {
            current.update {
                it.copy(
                    detailsError =
                        "These prices have expired. Refresh booking options before continuing."
                )
            }
            return null
        }
        val rate = details.rates.firstOrNull { it.id == rateId } ?: return null
        return ComparisonLinks.safeBookingUrl(rate.bookingUrl).also { url ->
            if (url == null)
                current.update {
                    it.copy(detailsError = "This booking option has no available provider link.")
                }
        }
    }

    private fun persist(query: ComparisonDraft) {
        saved["comparison.destination"] = query.destination
        saved["comparison.checkIn"] = query.checkIn
        saved["comparison.checkOut"] = query.checkOut
        saved["comparison.adults"] = query.adults
        saved["comparison.childAges"] = query.childAges.toIntArray()
        saved["comparison.currency"] = query.currency
        saved["comparison.market"] = query.market
        saved["comparison.scope"] = query.scope.name
    }
}

internal val comparisonCurrencies = listOf("USD", "EUR", "GBP", "CAD", "AUD", "JPY")
internal val comparisonMarkets =
    listOf(
        "us" to "United States",
        "uk" to "United Kingdom",
        "ca" to "Canada",
        "au" to "Australia",
        "fr" to "France",
        "de" to "Germany",
        "jp" to "Japan",
    )

private fun restoreComparison(saved: SavedStateHandle, today: LocalDate): ComparisonDraft {
    fun text(key: String) = runCatching { saved.get<String>("comparison.$key") }.getOrNull()
    val checkIn =
        runCatching { LocalDate.parse(text("checkIn")) }
            .getOrNull()
            ?.takeIf { it in today..today.plusDays(365) } ?: today.plusDays(14)
    val savedOut = runCatching { LocalDate.parse(text("checkOut")) }.getOrNull()
    val checkOut =
        savedOut?.takeIf { ChronoUnit.DAYS.between(checkIn, it) in 1..28 } ?: checkIn.plusDays(3)
    return ComparisonDraft(
        destination = text("destination")?.take(120).orEmpty(),
        checkIn = checkIn.toString(),
        checkOut = checkOut.toString(),
        adults =
            (runCatching { saved.get<Int>("comparison.adults") }.getOrNull() ?: 2).coerceIn(1, 6),
        childAges =
            runCatching { saved.get<IntArray>("comparison.childAges")?.toList() }
                .getOrNull()
                .orEmpty()
                .take(4)
                .map { it.coerceIn(1, 17) },
        currency = text("currency")?.takeIf { it in comparisonCurrencies } ?: "USD",
        market =
            text("market")?.takeIf { it in comparisonMarkets.map { market -> market.first } }
                ?: "us",
        scope =
            runCatching { ComparisonScope.valueOf(text("scope").orEmpty()) }
                .getOrDefault(ComparisonScope.ALL),
    )
}
