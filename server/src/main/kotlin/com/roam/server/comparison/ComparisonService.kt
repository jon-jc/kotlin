package com.roam.server.comparison

import com.roam.core.*
import com.roam.server.ApiFailure
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

class ComparisonService
internal constructor(
    private val provider: SerpApiProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val timeoutMillis: Long = 12_500,
    private val budget: ComparisonBudget = ComparisonBudget(clock),
    private val parallel: Semaphore = Semaphore(4),
) {
    companion object {
        fun create(config: ComparisonConfig): ComparisonService =
            ComparisonService(
                SerpApiProvider(config.apiKey),
                budget = ComparisonBudget(Clock.systemUTC(), config.dailyRequestLimit),
            )

        fun unconfigured(): ComparisonService = ComparisonService(SerpApiProvider(null))
    }

    suspend fun search(query: ComparisonQuery): ComparisonResult {
        validate(query)
        val kinds =
            when (query.scope) {
                ComparisonScope.ALL -> listOf(ComparisonKind.HOTEL, ComparisonKind.RENTAL)
                ComparisonScope.HOTELS -> listOf(ComparisonKind.HOTEL)
                ComparisonScope.RENTALS -> listOf(ComparisonKind.RENTAL)
            }
        val results = supervisorScope {
            kinds
                .map { kind -> async { observe(kind) { provider.search(query, kind) } } }
                .awaitAll()
        }
        val now = clock.instant()
        return ComparisonResult(
            query = query,
            checkedAt = now.toString(),
            expiresAt = now.plusSeconds(300).toString(),
            properties = results.flatMap { it.values },
            sourceStatuses = results.map { it.status },
        )
    }

    suspend fun details(request: ComparisonPropertyRequest): ComparisonDetails {
        validate(request.query)
        if (
            !validToken(request.propertyToken) ||
                (request.query.scope == ComparisonScope.HOTELS &&
                    request.kind != ComparisonKind.HOTEL) ||
                (request.query.scope == ComparisonScope.RENTALS &&
                    request.kind != ComparisonKind.RENTAL)
        )
            throw ApiFailure(422, "INVALID_COMPARISON", "Choose a property from this search.")
        val result = observe(request.kind) { provider.details(request) }
        val now = clock.instant()
        return ComparisonDetails(
            query = request.query,
            propertyToken = request.propertyToken,
            kind = request.kind,
            checkedAt = now.toString(),
            expiresAt = now.plusSeconds(300).toString(),
            rates = result.values,
            sourceStatuses = listOf(result.status),
        )
    }

    private fun validate(query: ComparisonQuery) {
        try {
            query.validate(LocalDate.now(clock))
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(
                422,
                "INVALID_COMPARISON",
                "Check the destination, dates, guests and currency.",
            )
        }
    }

    private data class Outcome<T>(val values: List<T>, val status: ComparisonSourceStatus)

    private suspend fun <T> observe(
        kind: ComparisonKind,
        action: suspend () -> List<T>,
    ): Outcome<T> {
        fun outcome(state: ComparisonSourceState, values: List<T> = emptyList()) =
            Outcome(
                values,
                ComparisonSourceStatus(
                    kind,
                    state,
                    when (state) {
                        ComparisonSourceState.NOT_CONFIGURED ->
                            "Live comparison is not configured on this service."
                        ComparisonSourceState.TIMEOUT ->
                            "This source did not respond in time. Try again."
                        ComparisonSourceState.QUOTA ->
                            "This source's search budget is temporarily unavailable."
                        ComparisonSourceState.BUSY -> "This source is busy. Try again shortly."
                        ComparisonSourceState.ERROR ->
                            "This source could not return usable results. Try again."
                        ComparisonSourceState.EMPTY ->
                            "No matching results were reported for this search."
                        else -> null
                    },
                ),
            )
        if (!provider.configured) return outcome(ComparisonSourceState.NOT_CONFIGURED)
        if (!parallel.tryAcquire()) return outcome(ComparisonSourceState.BUSY)
        try {
            budget.consume()
            val values =
                withTimeoutOrNull(timeoutMillis) { action() }
                    ?: return outcome(ComparisonSourceState.TIMEOUT)
            return outcome(
                if (values.isEmpty()) ComparisonSourceState.EMPTY else ComparisonSourceState.LIVE,
                values,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: ProviderFailure) {
            return outcome(failure.state)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val timeout =
                generateSequence(failure as Throwable?) { it.cause }
                    .take(8)
                    .any { it is TimeoutException || it is HttpTimeoutException }
            return outcome(
                if (timeout) ComparisonSourceState.TIMEOUT else ComparisonSourceState.ERROR
            )
        } finally {
            parallel.release()
        }
    }
}

/** Counts every attempted upstream call, including failures; no retries or saved search data. */
internal class ComparisonBudget(
    private val clock: Clock,
    private val dailyLimit: Int = 200,
    private val minuteLimit: Int = 20,
) {
    private var day: Long = -1
    private var minute: Long = -1
    private var dailyCount = 0
    private var minuteCount = 0

    @Synchronized
    fun consume() {
        val now = clock.instant().epochSecond
        if (day != now / 86400) {
            day = now / 86400
            dailyCount = 0
        }
        if (minute != now / 60) {
            minute = now / 60
            minuteCount = 0
        }
        if (dailyCount >= dailyLimit || minuteCount >= minuteLimit)
            throw ProviderFailure(ComparisonSourceState.QUOTA)
        dailyCount++
        minuteCount++
    }
}
