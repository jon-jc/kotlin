package com.roam.core

import java.time.Clock
import java.time.LocalDate

/** Read-only external comparison. Results cannot be passed into Roam's payment operations. */
interface ComparisonGateway {
    suspend fun search(query: ComparisonQuery): ComparisonResult

    suspend fun details(
        query: ComparisonQuery,
        propertyToken: String,
        kind: ComparisonKind,
    ): ComparisonDetails
}

/** Explicitly unavailable until a comparison service is configured; never invents live offers. */
class UnavailableComparisonGateway(private val clock: Clock = Clock.systemUTC()) :
    ComparisonGateway {
    override suspend fun search(query: ComparisonQuery): ComparisonResult {
        query.validate(LocalDate.now(clock))
        val now = clock.instant()
        return ComparisonResult(
            query,
            now.toString(),
            now.plusSeconds(300).toString(),
            emptyList(),
            query.kinds().map(::unavailable),
        )
    }

    override suspend fun details(
        query: ComparisonQuery,
        propertyToken: String,
        kind: ComparisonKind,
    ): ComparisonDetails {
        query.validate(LocalDate.now(clock))
        ComparisonPropertyRequest(query, propertyToken, kind)
        val now = clock.instant()
        return ComparisonDetails(
            query,
            propertyToken,
            kind,
            now.toString(),
            now.plusSeconds(300).toString(),
            emptyList(),
            listOf(unavailable(kind)),
        )
    }

    private fun unavailable(kind: ComparisonKind) =
        ComparisonSourceStatus(
            kind,
            ComparisonSourceState.NOT_CONFIGURED,
            "Live price comparison is not connected yet.",
        )
}
