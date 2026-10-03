package com.roam.core

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale
import kotlinx.serialization.Serializable

const val COMPARISON_SOURCE = "Google Hotels via SerpAPI"

val COMPARISON_CURRENCIES: Set<String> = setOf("USD", "EUR", "GBP", "CAD", "AUD", "JPY")

val COMPARISON_MARKETS: Set<String> = setOf("us", "uk", "ca", "au", "de", "fr", "jp")

@Serializable
enum class ComparisonScope {
    ALL,
    HOTELS,
    RENTALS,
}

@Serializable
enum class ComparisonKind {
    HOTEL,
    RENTAL,
}

@Serializable
enum class TaxCoverage {
    UNKNOWN,
    REPORTED_INCLUDED,
}

@Serializable
enum class ComparisonSourceState {
    LIVE,
    EMPTY,
    NOT_CONFIGURED,
    TIMEOUT,
    QUOTA,
    ERROR,
    UNSUPPORTED,
    BUSY,
}

class ComparisonException(message: String) : IllegalArgumentException(message)

/** A comparison searches one room; children are represented by their ages at check-in. */
@Serializable
data class ComparisonQuery(
    val destination: String,
    val checkIn: String,
    val checkOut: String,
    val adults: Int = 2,
    val childAges: List<Int> = emptyList(),
    val currency: String = "USD",
    val market: String = "us",
    val scope: ComparisonScope = ComparisonScope.ALL,
) {
    init {
        checkComparison(
            destination == destination.trim() &&
                destination.length in 2..120 &&
                destination.none(Char::isISOControl),
            "Enter a destination between 2 and 120 characters.",
        )
        checkComparison(adults in 1..6, "Choose between 1 and 6 adults for one room.")
        checkComparison(
            childAges.size <= 4 && childAges.all { it in 1..17 },
            "Enter ages from 1 to 17 for up to 4 children.",
        )
        checkComparison(
            currency in COMPARISON_CURRENCIES,
            "Choose a supported comparison currency.",
        )
        checkComparison(market in COMPARISON_MARKETS, "Choose a supported country market.")
        val start = comparisonDate(checkIn)
        val end = comparisonDate(checkOut)
        checkComparison(
            ChronoUnit.DAYS.between(start, end) in 1..28,
            "Choose a stay between 1 and 28 nights.",
        )
    }

    fun validate(today: LocalDate) {
        val start = comparisonDate(checkIn)
        checkComparison(
            !start.isBefore(today) && !start.isAfter(today.plusDays(365)),
            "Choose a check-in date within the next 365 days.",
        )
    }

    fun includes(kind: ComparisonKind): Boolean =
        scope == ComparisonScope.ALL ||
            (scope == ComparisonScope.HOTELS && kind == ComparisonKind.HOTEL) ||
            (scope == ComparisonScope.RENTALS && kind == ComparisonKind.RENTAL)

    fun kinds(): List<ComparisonKind> = ComparisonKind.entries.filter(::includes)
}

/** Decimal major units, without floating-point arithmetic or assumed currency conversion. */
@Serializable
data class ComparisonMoney(val amount: String, val currency: String) {
    init {
        checkComparison(
            currency in COMPARISON_CURRENCIES,
            "The provider returned an unsupported currency.",
        )
        val digits = if (currency == "JPY") 0 else 2
        val pattern =
            if (digits == 0) "(?:0|[1-9][0-9]*)" else "(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,$digits})?"
        checkComparison(
            amount.length in 1..24 && amount.matches(Regex(pattern)),
            "The provider returned an invalid price.",
        )
        checkComparison(
            runCatching { minorUnits() }.isSuccess,
            "The provider returned an unsupported price.",
        )
    }

    fun minorUnits(): Long =
        BigDecimal(amount).movePointRight(if (currency == "JPY") 0 else 2).longValueExact()

    fun formatted(locale: Locale = Locale.US): String =
        NumberFormat.getCurrencyInstance(locale)
            .apply {
                currency = Currency.getInstance(this@ComparisonMoney.currency)
                minimumFractionDigits = currency.defaultFractionDigits
                maximumFractionDigits = currency.defaultFractionDigits
            }
            .format(BigDecimal(amount))
}

/** A provider's advertised whole-stay total is never synthesized from its nightly rate. */
@Serializable
data class ComparisonPrice(
    val total: ComparisonMoney? = null,
    val nightly: ComparisonMoney? = null,
    val taxCoverage: TaxCoverage = TaxCoverage.UNKNOWN,
) {
    init {
        checkComparison(total != null || nightly != null, "The provider returned an empty price.")
        checkComparison(
            total == null || nightly == null || total.currency == nightly.currency,
            "The provider returned mixed currencies.",
        )
    }

    /** Compare reported tax-inclusive totals in one currency; final provider terms still apply. */
    fun comparableTotal(currency: String): ComparisonMoney? =
        total?.takeIf { it.currency == currency && taxCoverage == TaxCoverage.REPORTED_INCLUDED }
}

@Serializable
data class ComparisonProperty(
    val id: String,
    val propertyToken: String,
    val kind: ComparisonKind,
    val title: String,
    val imageUrl: String? = null,
    val summaryPrice: ComparisonPrice? = null,
    val rating: Double? = null,
    val source: String = COMPARISON_SOURCE,
) {
    init {
        comparisonText(id, 1, 256)
        validateComparisonToken(propertyToken)
        comparisonText(title, 1, 300)
        comparisonText(source, 1, 100)
        checkComparison(
            imageUrl == null || imageUrl.length <= 4096,
            "The provider returned an unsupported image link.",
        )
        checkComparison(
            rating == null || (rating.isFinite() && rating in 0.0..5.0),
            "The provider returned an invalid rating.",
        )
    }
}

@Serializable
data class ComparisonSourceStatus(
    val kind: ComparisonKind,
    val status: ComparisonSourceState,
    val message: String? = null,
    val source: String = COMPARISON_SOURCE,
) {
    init {
        if (message != null) comparisonText(message, 1, 500)
        comparisonText(source, 1, 100)
    }
}

@Serializable
data class ComparisonResult(
    val query: ComparisonQuery,
    val checkedAt: String,
    val expiresAt: String,
    val properties: List<ComparisonProperty>,
    val sourceStatuses: List<ComparisonSourceStatus>,
) {
    init {
        validateComparisonTimes(checkedAt, expiresAt)
        checkComparison(properties.size <= 100, "The service returned too many properties.")
        checkComparison(
            properties.map { it.kind to it.propertyToken }.distinct().size == properties.size,
            "The service returned duplicate properties.",
        )
        checkComparison(
            properties.map { it.id }.distinct().size == properties.size,
            "The service returned duplicate property identifiers.",
        )
        validateComparisonStatuses(query.kinds(), sourceStatuses)
        properties.forEach {
            checkComparison(query.includes(it.kind), "The property does not match this search.")
            checkComparison(
                sourceStatuses.single { status -> status.kind == it.kind }.status ==
                    ComparisonSourceState.LIVE,
                "The property has no live provider coverage.",
            )
            validateComparisonPrice(it.summaryPrice, query.currency)
        }
    }
}

@Serializable
data class ComparisonPropertyRequest(
    val query: ComparisonQuery,
    val propertyToken: String,
    val kind: ComparisonKind,
) {
    init {
        validateComparisonToken(propertyToken)
        checkComparison(query.includes(kind), "The property does not match this search.")
    }
}

@Serializable
data class ComparisonRate(
    val id: String,
    val provider: String,
    val title: String? = null,
    val price: ComparisonPrice? = null,
    val bookingUrl: String? = null,
    val cancellation: String? = null,
) {
    init {
        comparisonText(id, 1, 256)
        comparisonText(provider, 1, 200)
        if (title != null) comparisonText(title, 1, 500)
        if (cancellation != null) comparisonText(cancellation, 1, 1000)
        checkComparison(
            bookingUrl == null || bookingUrl.length <= 8192,
            "The provider returned an unsupported booking link.",
        )
    }
}

@Serializable
data class ComparisonDetails(
    val query: ComparisonQuery,
    val propertyToken: String,
    val kind: ComparisonKind,
    val checkedAt: String,
    val expiresAt: String,
    val rates: List<ComparisonRate>,
    val sourceStatuses: List<ComparisonSourceStatus>,
) {
    init {
        ComparisonPropertyRequest(query, propertyToken, kind)
        validateComparisonTimes(checkedAt, expiresAt)
        checkComparison(rates.size <= 200, "The service returned too many rates.")
        checkComparison(
            rates.map { it.id }.distinct().size == rates.size,
            "The service returned duplicate rates.",
        )
        validateComparisonStatuses(listOf(kind), sourceStatuses)
        checkComparison(
            rates.isEmpty() || sourceStatuses.single().status == ComparisonSourceState.LIVE,
            "These prices have no live provider coverage.",
        )
        rates.forEach { validateComparisonPrice(it.price, query.currency) }
    }
}

private fun comparisonDate(value: String): LocalDate {
    val parsed = runCatching { LocalDate.parse(value) }.getOrNull()
    checkComparison(
        value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && parsed?.toString() == value,
        "Choose valid check-in and check-out dates.",
    )
    return requireNotNull(parsed)
}

private fun validateComparisonToken(token: String) {
    checkComparison(
        token.matches(Regex("[A-Za-z0-9_-]{1,2048}")),
        "Choose a valid property from the search results.",
    )
}

private fun comparisonText(value: String, min: Int, max: Int) {
    checkComparison(
        value.isNotBlank() && value.length in min..max && value.none(Char::isISOControl),
        "The service returned invalid comparison information.",
    )
}

private fun validateComparisonTimes(checkedAt: String, expiresAt: String) {
    val start = runCatching { Instant.parse(checkedAt) }.getOrNull()
    val end = runCatching { Instant.parse(expiresAt) }.getOrNull()
    checkComparison(
        start != null && end != null && end.isAfter(start) && !end.isAfter(start.plusSeconds(3600)),
        "The service returned invalid price freshness information.",
    )
}

private fun validateComparisonStatuses(
    kinds: List<ComparisonKind>,
    statuses: List<ComparisonSourceStatus>,
) {
    checkComparison(
        statuses.size == kinds.size && statuses.map { it.kind }.toSet() == kinds.toSet(),
        "The service returned incomplete provider status information.",
    )
}

private fun validateComparisonPrice(price: ComparisonPrice?, currency: String) {
    checkComparison(
        listOfNotNull(price?.total, price?.nightly).all { it.currency == currency },
        "The provider returned prices in another currency.",
    )
}

private fun checkComparison(condition: Boolean, message: String) {
    if (!condition) throw ComparisonException(message)
}
