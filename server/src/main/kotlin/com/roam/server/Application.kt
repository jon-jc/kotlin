package com.roam.server

import com.roam.core.api.*
import com.roam.server.comparison.ComparisonConfig
import com.roam.server.comparison.ComparisonService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

private val requestIdKey = AttributeKey<String>("request-id")

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--comparison-only"))) {
        val config = ComparisonConfig.load()
        val comparison = ComparisonService.create(config)
        embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
                comparisonModule(comparison, config.ingressRateLimited)
            }
            .start(wait = true)
        return
    }
    if (args.contentEquals(arrayOf("--seed-demo"))) {
        require(System.getenv("ROAM_ENV") == "development") {
            "Demo seeding requires ROAM_ENV=development"
        }
        Database(
                requireNotNull(System.getenv("DATABASE_URL")),
                requireNotNull(System.getenv("DATABASE_USER")),
                requireNotNull(System.getenv("DATABASE_PASSWORD")),
            )
            .use { it.seedDemo() }
        return
    }
    require(args.isEmpty()) { "Unsupported command" }
    val config = ServerConfig.load()
    val database = Database(config.databaseUrl, config.databaseUser, config.databasePassword)
    val commerce = Commerce(database, StripePayments(config.stripeSecret), config.livePayments)
    val verifier = TokenVerifier(config.issuer, config.audience, RemoteKeys(config.issuer))
    val comparison = ComparisonService.create(ComparisonConfig.load())
    embeddedServer(Netty, host = "0.0.0.0", port = config.port) {
            module(
                database,
                commerce,
                verifier,
                StripeWebhook(config.webhookSecret, config.livePayments),
                ingressRateLimited = config.ingressRateLimited,
                comparison = comparison,
            )
            monitor.subscribe(ApplicationStopped) { database.close() }
        }
        .start(wait = true)
}

fun Application.module(
    database: Database,
    commerce: Commerce,
    verifier: TokenVerifier,
    webhook: StripeWebhook,
    workerEnabled: Boolean = true,
    ingressRateLimited: Boolean = false,
    comparison: ComparisonService = ComparisonService.unconfigured(),
) {
    val limiter = apiBoundary(ingressRateLimited)

    suspend fun ApplicationCall.user(): String =
        withContext(Dispatchers.IO) {
            val authorization = request.headers[HttpHeaders.Authorization]
            if (authorization == null || !authorization.startsWith("Bearer "))
                throw ApiFailure(401, "UNAUTHENTICATED", "Sign in to continue.")
            verifier.verify(authorization.removePrefix("Bearer ")).also {
                limiter.check("user:$it", 120)
            }
        }

    routing {
        comparisonRoutes(comparison, ingressRateLimited)
        get("/health/live") { call.respond(ApiOk()) }
        get("/health/ready") {
            if (!withContext(Dispatchers.IO) { database.healthy() })
                throw ApiFailure(503, "NOT_READY", "The service is not ready.")
            call.respond(ApiOk())
        }
        post("/v1/webhooks/stripe") {
            val event = webhook.verify(call.boundedBody(), call.request.headers["Stripe-Signature"])
            withContext(Dispatchers.IO) { commerce.webhook(event) }
            call.respond(ApiOk())
        }
        get("/v1/catalog") { call.respond(withContext(Dispatchers.IO) { commerce.catalog() }) }
        get("/v1/account") {
            val user = call.user()
            call.respond(withContext(Dispatchers.IO) { commerce.account(user) })
        }
        put("/v1/profile") {
            val user = call.user()
            val profile = call.body<ApiProfile>()
            call.respond(withContext(Dispatchers.IO) { commerce.profile(user, profile) })
        }
        put("/v1/saved/{stayId}") {
            val user = call.user()
            withContext(Dispatchers.IO) { commerce.saved(user, call.parameters["stayId"]!!, true) }
            call.respond(ApiOk())
        }
        delete("/v1/saved/{stayId}") {
            val user = call.user()
            withContext(Dispatchers.IO) { commerce.saved(user, call.parameters["stayId"]!!, false) }
            call.respond(ApiOk())
        }
        post("/v1/quotes") {
            val user = call.user()
            limiter.check("quote:$user", 15)
            val input = call.body<ApiQuoteRequest>()
            call.respond(
                HttpStatusCode.Created,
                withContext(Dispatchers.IO) { commerce.quote(user, input) },
            )
        }
        post("/v1/reservations") {
            val user = call.user()
            val key =
                call.request.headers["Idempotency-Key"]
                    ?: invalid("An idempotency key is required.")
            val input = call.body<ApiReserveRequest>()
            call.respond(withContext(Dispatchers.IO) { commerce.reserve(user, key, input) })
        }
        get("/v1/reservations/by-request/{key}") {
            val user = call.user()
            call.respond(
                withContext(Dispatchers.IO) { commerce.byRequest(user, call.parameters["key"]!!) }
            )
        }
        get("/v1/reservations/{id}") {
            val user = call.user()
            call.respond(
                withContext(Dispatchers.IO) { commerce.reservation(user, call.parameters["id"]!!) }
            )
        }
        post("/v1/reservations/{id}/cancel") {
            val user = call.user()
            call.respond(
                withContext(Dispatchers.IO) { commerce.cancel(user, call.parameters["id"]!!) }
            )
        }
    }
    if (workerEnabled) {
        val worker =
            launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val failures = commerce.reconcilePending { isActive }
                        if (failures > 0)
                            log.warn(
                                "Payment reconciliation: {} requests still need a retry or operator review",
                                failures,
                            )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        log.error("Payment reconciliation unavailable; durable requests retained")
                    }
                    delay(15_000)
                }
            }
        monitor.subscribe(ApplicationStopping) { worker.cancel() }
    }
}

internal fun Application.apiBoundary(ingressRateLimited: Boolean): RequestLimiter {
    install(ContentNegotiation) { json(wireJson) }
    install(StatusPages) {
        exception<ApiFailure> { call, failure ->
            call.respond(
                HttpStatusCode.fromValue(failure.status),
                ApiError(failure.code, failure.message, call.id()),
            )
        }
        exception<SerializationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("INVALID_JSON", "The request could not be read.", call.id()),
            )
        }
        exception<Throwable> { call, failure ->
            if (failure is CancellationException) throw failure
            // Never log credentials, request/response bodies, provider responses, or exception
            // messages.
            call.application.log.warn(
                "Request {} failed ({})",
                call.id(),
                failure.javaClass.simpleName,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError(
                    "INTERNAL_ERROR",
                    "We could not complete this request. Retry safely.",
                    call.id(),
                ),
            )
        }
    }
    val limiter = RequestLimiter()
    intercept(ApplicationCallPipeline.Call) {
        call.attributes.put(requestIdKey, UUID.randomUUID().toString())
        call.response.header("X-Request-Id", call.id())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        if (call.request.uri.length > 2048)
            throw ApiFailure(414, "REQUEST_TOO_LARGE", "The request is too large.")
        if (
            call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > 65536 } ==
                true
        )
            throw ApiFailure(413, "REQUEST_TOO_LARGE", "The request is too large.")
        // Forwarding headers are not trusted. Configure additional distributed limits at the
        // ingress.
        if (!ingressRateLimited && call.request.path() != "/v1/webhooks/stripe")
            limiter.check("ip:${call.request.local.remoteHost}", 240)
    }

    return limiter
}

private fun ApplicationCall.id() = attributes.getOrNull(requestIdKey) ?: "unavailable"

internal suspend inline fun <reified T> ApplicationCall.body(maximum: Int = 65536): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json)
        throw ApiFailure(415, "CONTENT_TYPE", "Use application/json for this request.")
    return wireJson.decodeFromString(boundedBody(maximum).toString(Charsets.UTF_8))
}

internal suspend fun ApplicationCall.boundedBody(maximum: Int = 65536): ByteArray {
    val channel = receiveChannel()
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    withTimeout(10_000) {
        while (true) {
            val size = channel.readAvailable(buffer, 0, buffer.size)
            if (size == -1) break
            if (result.size() + size > maximum)
                throw ApiFailure(413, "REQUEST_TOO_LARGE", "The request is too large.")
            result.write(buffer, 0, size)
        }
    }
    return result.toByteArray()
}

/** Bounded per-process defense; the deployment ingress supplies global limits across replicas. */
internal class RequestLimiter {
    private data class Window(val minute: Long, var count: Int)

    private val windows = LinkedHashMap<String, Window>()

    @Synchronized
    fun check(key: String, maximum: Int) {
        val minute = System.currentTimeMillis() / 60000
        if (windows.size >= 10000) {
            windows.entries.removeIf { it.value.minute != minute }
            if (windows.size >= 10000 && key !in windows)
                throw ApiFailure(429, "RATE_LIMITED", "Please wait a moment before trying again.")
        }
        val entry =
            windows[key]?.takeIf { it.minute == minute }
                ?: Window(minute, 0).also { windows[key] = it }
        if (++entry.count > maximum)
            throw ApiFailure(429, "RATE_LIMITED", "Please wait a moment before trying again.")
    }
}
