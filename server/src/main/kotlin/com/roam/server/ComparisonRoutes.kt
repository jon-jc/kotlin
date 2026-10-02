package com.roam.server

import com.roam.core.ComparisonException
import com.roam.core.ComparisonPropertyRequest
import com.roam.core.ComparisonQuery
import com.roam.core.api.ApiOk
import com.roam.server.comparison.ComparisonService
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Semaphore

/** This bootstrap has no database, identity-provider, wallet, or payment dependency. */
fun Application.comparisonModule(
    service: ComparisonService = ComparisonService.unconfigured(),
    ingressRateLimited: Boolean = false,
) {
    apiBoundary(ingressRateLimited)
    routing {
        get("/health/live") { call.respond(ApiOk()) }
        // Readiness means the API can respond; configured/provider status is explicit in results.
        get("/health/ready") { call.respond(ApiOk()) }
        comparisonRoutes(service, ingressRateLimited)
    }
}

internal fun Route.comparisonRoutes(service: ComparisonService, ingressRateLimited: Boolean) {
    val limiter = RequestLimiter()
    val requests = Semaphore(8)
    suspend fun <T> ApplicationCall.limited(action: suspend () -> T): T {
        limiter.check("comparison-global", 60)
        if (!ingressRateLimited) limiter.check("comparison-ip:${request.local.remoteHost}", 10)
        if (!requests.tryAcquire())
            throw ApiFailure(429, "COMPARISON_BUSY", "Comparison is busy. Try again shortly.")
        try {
            return action()
        } catch (_: ComparisonException) {
            throw ApiFailure(
                422,
                "INVALID_COMPARISON",
                "Check the destination, dates, guests and currency.",
            )
        } finally {
            requests.release()
        }
    }

    post("/v1/comparison/search") {
        call.respond(call.limited { service.search(call.body<ComparisonQuery>(8192)) })
    }
    post("/v1/comparison/property") {
        call.respond(call.limited { service.details(call.body<ComparisonPropertyRequest>(8192)) })
    }
}
