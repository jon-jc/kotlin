package com.roam.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.roam.core.api.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ApiIntegrationTest {
    @Test
    fun `HTTP boundary authenticates limits payloads and returns only provider confirmed bookings`() =
        testApplication {
            val database = testDatabase()
            try {
                database.transaction { it.exec("TRUNCATE accounts,stays,webhook_events CASCADE") }
                database.seedDemo()
                val now = Instant.parse("2026-10-02T12:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val payments = TestPayments()
                val commerce = Commerce(database, payments, false, clock)
                val key = RSAKeyGenerator(2048).keyID("api-test").generate()
                val user = UUID.randomUUID().toString()
                val issuer = "https://api-test.supabase.co/auth/v1"
                val token =
                    SignedJWT(
                            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(),
                            JWTClaimsSet.Builder()
                                .issuer(issuer)
                                .subject(user)
                                .audience("authenticated")
                                .claim("role", "authenticated")
                                .expirationTime(Date.from(now.plusSeconds(300)))
                                .build(),
                        )
                        .apply { sign(RSASSASigner(key)) }
                        .serialize()
                application {
                    module(
                        database,
                        commerce,
                        TokenVerifier(
                            issuer,
                            "authenticated",
                            { JWKSet(key.toPublicJWK()) },
                            clock,
                        ),
                        StripeWebhook("whsec_api_test_secret", false, clock),
                        workerEnabled = false,
                    )
                }

                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/account").status)
                val account = client.get("/v1/account") { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, account.status)
                assertEquals("no-store", account.headers[HttpHeaders.CacheControl])
                assertNotNull(account.headers["X-Request-Id"])
                assertEquals(
                    "0",
                    wireJson.decodeFromString<ApiAccount>(account.bodyAsText()).balanceMinor,
                )
                val oversized =
                    client.post("/v1/quotes") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("x".repeat(65537))
                    }
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                val malformed =
                    client.post("/v1/quotes") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.BadRequest, malformed.status)
                val quoteResponse =
                    client.post("/v1/quotes") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            wireJson.encodeToString(
                                ApiQuoteRequest("kyoto", "2026-10-10", "2026-10-13", 2, false)
                            )
                        )
                    }
                assertEquals(HttpStatusCode.Created, quoteResponse.status)
                val quote = wireJson.decodeFromString<ApiQuote>(quoteResponse.bodyAsText())
                val reserve =
                    client.post("/v1/reservations") {
                        bearerAuth(token)
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        contentType(ContentType.Application.Json)
                        setBody(wireJson.encodeToString(ApiReserveRequest(quote.id)))
                    }
                assertEquals(HttpStatusCode.OK, reserve.status)
                val result = wireJson.decodeFromString<ApiReservationResult>(reserve.bodyAsText())
                assertEquals("pending_payment", result.reservation.state)
                payments.succeed(result.reservation.id)
                val confirmed =
                    client.get("/v1/reservations/${result.reservation.id}") { bearerAuth(token) }
                assertEquals(
                    "confirmed",
                    wireJson
                        .decodeFromString<ApiReservationResult>(confirmed.bodyAsText())
                        .reservation
                        .state,
                )
                val invalidWebhook =
                    client.post("/v1/webhooks/stripe") {
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.BadRequest, invalidWebhook.status)
            } finally {
                database.close()
            }
        }
}
