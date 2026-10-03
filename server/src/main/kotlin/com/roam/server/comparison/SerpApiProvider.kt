package com.roam.server.comparison

import com.roam.core.*
import com.roam.server.wireJson
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.*

internal class ProviderFailure(val state: ComparisonSourceState) : RuntimeException()

/** A read-only observation of Google Hotels; never a Roam quote or inventory reservation. */
internal class SerpApiProvider(
    private val apiKey: String?,
    private val transport: ComparisonTransport = SerpApiHttp(),
) {
    val configured: Boolean
        get() = !apiKey.isNullOrBlank()

    suspend fun search(query: ComparisonQuery, kind: ComparisonKind): List<ComparisonProperty> {
        val root = fetch(query, kind)
        val rows =
            when (val properties = root["properties"]) {
                is JsonArray -> properties
                null -> {
                    // An exact-name query may return the documented property-details shape.
                    // Do not treat arbitrary/malformed objects as a successful search result.
                    val details =
                        (root["search_information"] as? JsonObject)?.text("hotels_results_state") ==
                            "Showing results for property details"
                    if (
                        details &&
                            root.text("type")?.lowercase() in setOf("hotel", "vacation rental") &&
                            root.text("property_token")?.let(::validToken) == true
                    )
                        JsonArray(listOf(root))
                    else throw ProviderFailure(ComparisonSourceState.ERROR)
                }
                else -> throw ProviderFailure(ComparisonSourceState.ERROR)
            }
        val properties =
            rows
                .take(50)
                .mapNotNull { item ->
                    val row = item as? JsonObject ?: return@mapNotNull null
                    val token =
                        row.text("property_token")?.takeIf { validToken(it) && !containsSecret(it) }
                            ?: return@mapNotNull null
                    val title = clean(row.text("name"), 240) ?: return@mapNotNull null
                    val actualKind =
                        when (row.text("type")?.lowercase()) {
                            "hotel" -> ComparisonKind.HOTEL
                            "vacation rental" -> ComparisonKind.RENTAL
                            else -> kind
                        }
                    if (actualKind != kind) return@mapNotNull null
                    val image =
                        (row["images"] as? JsonArray).orEmpty().take(5).firstNotNullOfOrNull {
                            val url = (it as? JsonObject)?.text("thumbnail")
                            if (url != null && !containsSecret(url))
                                ComparisonLinks.safeImageUrl(url)
                            else null
                        }
                    ComparisonProperty(
                        id = stableId("${kind.name}:$token"),
                        propertyToken = token,
                        kind = kind,
                        title = title,
                        imageUrl = image,
                        summaryPrice = price(row, query.currency),
                        rating =
                            (row["overall_rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf {
                                it.isFinite() && it in 0.0..5.0
                            },
                    )
                }
                .distinctBy { it.id }
        if (rows.isNotEmpty() && properties.isEmpty())
            throw ProviderFailure(ComparisonSourceState.ERROR)
        return properties
    }

    suspend fun details(request: ComparisonPropertyRequest): List<ComparisonRate> {
        val root = fetch(request.query, request.kind, request.propertyToken)
        root.text("property_token")?.let {
            if (it != request.propertyToken) throw ProviderFailure(ComparisonSourceState.ERROR)
        }
        val rows =
            root["prices"] as? JsonArray
                ?: if (clean(root.text("name"), 240) != null) JsonArray(emptyList())
                else throw ProviderFailure(ComparisonSourceState.ERROR)
        val rates =
            rows
                .take(60)
                .mapNotNull { item ->
                    val row = item as? JsonObject ?: return@mapNotNull null
                    val provider = clean(row.text("source"), 120) ?: return@mapNotNull null
                    val guests = (row["num_guests"] as? JsonPrimitive)?.intOrNull
                    if (
                        guests != null &&
                            guests != request.query.adults + request.query.childAges.size
                    )
                        return@mapNotNull null
                    val link =
                        row.text("link")
                            ?.takeUnless(::containsSecret)
                            ?.let(ComparisonLinks::safeBookingUrl)
                    val amount = price(row, request.query.currency)
                    // An unpriced but usable link can still be shown as "check price"; a row with
                    // neither
                    // useful price nor an allowed handoff is not an offer.
                    if (amount == null && link == null) return@mapNotNull null
                    val remarks =
                        (row["discount_remarks"] as? JsonArray)
                            .orEmpty()
                            .take(4)
                            .mapNotNull { clean((it as? JsonPrimitive)?.contentOrNull, 120) }
                            .joinToString(" · ")
                            .takeIf { it.isNotEmpty() }
                    val cancellation =
                        if ((row["free_cancellation"] as? JsonPrimitive)?.booleanOrNull == true) {
                            val date = clean(row.text("free_cancellation_until_date"), 60)
                            val time = clean(row.text("free_cancellation_until_time"), 30)
                            "Free cancellation reported" +
                                listOfNotNull(date, time)
                                    .joinToString(" ")
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { " until $it" }
                                    .orEmpty() +
                                "; confirm the deadline and terms with the provider."
                        } else null
                    ComparisonRate(
                        id =
                            stableId(
                                "${request.propertyToken}:$provider:$link:${amount?.total?.amount}:$remarks"
                            ),
                        provider = provider,
                        title = remarks,
                        price = amount,
                        bookingUrl = link,
                        cancellation = cancellation,
                    )
                }
                .distinctBy { it.id }
        if (rows.isNotEmpty() && rates.isEmpty()) throw ProviderFailure(ComparisonSourceState.ERROR)
        return rates
    }

    private suspend fun fetch(
        query: ComparisonQuery,
        kind: ComparisonKind,
        token: String? = null,
    ): JsonObject {
        if (!configured) throw ProviderFailure(ComparisonSourceState.NOT_CONFIGURED)
        val parameters =
            linkedMapOf(
                "engine" to "google_hotels",
                "api_key" to apiKey!!,
                "q" to query.destination,
                "check_in_date" to query.checkIn,
                "check_out_date" to query.checkOut,
                "adults" to query.adults.toString(),
                "children" to query.childAges.size.toString(),
                "currency" to query.currency,
                "gl" to query.market,
                "hl" to "en",
                "no_cache" to "true",
                "output" to "json",
            )
        if (query.childAges.isNotEmpty())
            parameters["children_ages"] = query.childAges.joinToString(",")
        if (kind == ComparisonKind.RENTAL) parameters["vacation_rentals"] = "true"
        if (token != null) parameters["property_token"] = token
        val response = transport.get(parameters)
        if (response.status == 429) throw ProviderFailure(ComparisonSourceState.QUOTA)
        if (response.status !in 200..299) throw ProviderFailure(ComparisonSourceState.ERROR)
        if (response.body.length > 2 * 1024 * 1024)
            throw ProviderFailure(ComparisonSourceState.ERROR)
        val root =
            wireJson.parseToJsonElement(response.body) as? JsonObject
                ?: throw ProviderFailure(ComparisonSourceState.ERROR)
        root.text("error")?.let { error ->
            val status =
                when {
                    error.contains("run out of searches", ignoreCase = true) ||
                        error.contains("search quota", ignoreCase = true) ->
                        ComparisonSourceState.QUOTA
                    error.contains("hasn't returned any results", ignoreCase = true) ->
                        ComparisonSourceState.EMPTY
                    else -> ComparisonSourceState.ERROR
                }
            throw ProviderFailure(status)
        }
        if ((root["search_metadata"] as? JsonObject)?.text("status") != "Success")
            throw ProviderFailure(ComparisonSourceState.ERROR)
        // Numeric amounts carry no currency themselves. The provider must confirm the search
        // context before those amounts can safely be labelled with the requested currency/dates.
        val echo =
            root["search_parameters"] as? JsonObject
                ?: throw ProviderFailure(ComparisonSourceState.ERROR)
        for ((key, expected) in
            parameters.filterKeys {
                it in
                    setOf(
                        "engine",
                        "q",
                        "gl",
                        "check_in_date",
                        "check_out_date",
                        "adults",
                        "children",
                        "children_ages",
                        "currency",
                        "vacation_rentals",
                        "property_token",
                    )
            }) {
            val actual =
                if (key == "children_ages" && echo[key] is JsonArray)
                    (echo[key] as JsonArray).joinToString(",") {
                        (it as? JsonPrimitive)?.contentOrNull.orEmpty()
                    }
                else echo.text(key)
            if (actual != expected) throw ProviderFailure(ComparisonSourceState.ERROR)
        }
        if (kind == ComparisonKind.HOTEL && echo.text("vacation_rentals") !in listOf(null, "false"))
            throw ProviderFailure(ComparisonSourceState.ERROR)
        return root
    }

    private fun price(row: JsonObject, currency: String): ComparisonPrice? {
        val totals = row["total_rate"] as? JsonObject
        val total = money(totals?.get("extracted_lowest"), currency)
        val nightly =
            money((row["rate_per_night"] as? JsonObject)?.get("extracted_lowest"), currency)
        if (total == null && nightly == null) return null
        val beforeTaxes = money(totals?.get("extracted_before_taxes_fees"), currency)
        val coverage =
            if (
                total != null &&
                    beforeTaxes != null &&
                    BigDecimal(beforeTaxes.amount) <= BigDecimal(total.amount)
            )
                TaxCoverage.REPORTED_INCLUDED
            else TaxCoverage.UNKNOWN
        return ComparisonPrice(total, nightly, coverage)
    }

    private fun money(value: JsonElement?, currency: String): ComparisonMoney? {
        val raw = (value as? JsonPrimitive)?.contentOrNull ?: return null
        if (!raw.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,8})?"))) return null
        return runCatching {
                val amount =
                    BigDecimal(raw)
                        .setScale(if (currency == "JPY") 0 else 2, RoundingMode.UNNECESSARY)
                require(amount >= BigDecimal.ZERO && amount <= BigDecimal("1000000000"))
                ComparisonMoney(amount.toPlainString(), currency)
            }
            .getOrNull()
    }

    private fun clean(value: String?, maximum: Int): String? =
        value
            ?.takeUnless(::containsSecret)
            ?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            ?.trim()
            ?.take(maximum)
            ?.takeIf { it.isNotEmpty() }

    private fun containsSecret(value: String): Boolean =
        (!apiKey.isNullOrBlank() && value.contains(apiKey)) ||
            value.contains("api_key", ignoreCase = true) ||
            value.contains("serpapi.com", ignoreCase = true)
}

internal fun validToken(token: String): Boolean = token.matches(Regex("[A-Za-z0-9_-]{1,2048}"))

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun stableId(value: String): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        )
