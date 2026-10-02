package com.roam.network

import com.roam.core.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

/**
 * Public comparison transport; no account token or payment credentials accompany these requests.
 */
class RemoteComparisonGateway(
    endpoint: String,
    allowLocalHttp: Boolean = false,
    private val http: JsonHttp = JsonHttp(),
    private val clock: Clock = Clock.systemUTC(),
) : ComparisonGateway {
    private val base = JsonHttp.baseUrl(endpoint, allowLocalHttp)

    override suspend fun search(query: ComparisonQuery): ComparisonResult {
        query.validate(LocalDate.now(clock))
        return safely {
            val result =
                http.json.decodeFromString<ComparisonResult>(
                    post("search", http.json.encodeToString(query))
                )
            if (result.query != query)
                throw ComparisonException(
                    "The comparison does not match your search. Search again."
                )
            validateFreshness(result.checkedAt, result.expiresAt)
            result.copy(
                properties =
                    result.properties.map { property ->
                        property.copy(imageUrl = ComparisonLinks.safeImageUrl(property.imageUrl))
                    }
            )
        }
    }

    override suspend fun details(
        query: ComparisonQuery,
        propertyToken: String,
        kind: ComparisonKind,
    ): ComparisonDetails {
        query.validate(LocalDate.now(clock))
        val input = ComparisonPropertyRequest(query, propertyToken, kind)
        return safely {
            val result =
                http.json.decodeFromString<ComparisonDetails>(
                    post("property", http.json.encodeToString(input))
                )
            if (
                result.query != query ||
                    result.propertyToken != propertyToken ||
                    result.kind != kind
            ) {
                throw ComparisonException(
                    "These prices do not match your selected property. Search again."
                )
            }
            validateFreshness(result.checkedAt, result.expiresAt)
            result.copy(
                rates =
                    result.rates.map { rate ->
                        rate.copy(bookingUrl = ComparisonLinks.safeBookingUrl(rate.bookingUrl))
                    }
            )
        }
    }

    private suspend fun post(action: String, body: String): String =
        http.request(
            base.newBuilder().addPathSegments("v1/comparison/$action").build(),
            "POST",
            body,
        )

    private fun validateFreshness(checkedAt: String, expiresAt: String) {
        val now = clock.instant()
        if (Instant.parse(checkedAt).isAfter(now.plusSeconds(60))) {
            throw ComparisonException("The service returned invalid price freshness information.")
        }
        if (!Instant.parse(expiresAt).isAfter(now)) {
            throw ComparisonException("These comparison prices are out of date. Search again.")
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ComparisonException) {
            throw failure
        } catch (failure: HttpFailure) {
            throw ComparisonException(
                when (failure.status) {
                    429 -> "Comparison is busy. Please try again shortly."
                    400,
                    422 -> "Check your destination, dates, and guests, then search again."
                    else -> "Live comparison is unavailable right now. Please try again."
                }
            )
        } catch (_: IOException) {
            throw ComparisonException("Live comparison could not be reached. Please try again.")
        } catch (_: Exception) {
            throw ComparisonException(
                "The comparison service returned an unsupported response. Please try again."
            )
        }
}
