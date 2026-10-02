package com.roam.server

import com.roam.core.*
import com.roam.server.comparison.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ComparisonRouteTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    private val query = ComparisonQuery("Kyoto", "2026-10-10", "2026-10-13")

    @Test
    fun `comparison bootstrap works without database identity or payments and never fabricates offers`() =
        testApplication {
            application { comparisonModule(ComparisonService(SerpApiProvider(null), clock)) }
            assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/account").status)
            val response =
                client.post("/v1/comparison/search") {
                    contentType(ContentType.Application.Json)
                    setBody(wireJson.encodeToString(query))
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertNotNull(response.headers["X-Request-Id"])
            val result = wireJson.decodeFromString<ComparisonResult>(response.bodyAsText())
            assertTrue(result.properties.isEmpty())
            assertTrue(
                result.sourceStatuses.all { it.status == ComparisonSourceState.NOT_CONFIGURED }
            )
            val details =
                client.post("/v1/comparison/property") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        wireJson.encodeToString(
                            ComparisonPropertyRequest(query, "property", ComparisonKind.HOTEL)
                        )
                    )
                }
            assertEquals(HttpStatusCode.OK, details.status)
            assertTrue(
                wireJson.decodeFromString<ComparisonDetails>(details.bodyAsText()).rates.isEmpty()
            )
        }

    @Test
    fun `invalid input and oversized bodies are rejected before upstream work`() = testApplication {
        var upstream = 0
        val service =
            ComparisonService(
                SerpApiProvider("synthetic-test-provider-key") {
                    upstream++
                    ProviderReply(200, "{}")
                },
                clock,
            )
        application { comparisonModule(service) }
        suspend fun post(body: String) =
            client.post("/v1/comparison/search") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        assertEquals(HttpStatusCode.BadRequest, post("{}").status)
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            post(wireJson.encodeToString(query).replace("2026-10-10", "2026-10-32")).status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            post(wireJson.encodeToString(query).replace("\"adults\":2", "\"adults\":0")).status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            post(wireJson.encodeToString(query.copy(childAges = listOf(1))).replace("[1]", "[0]"))
                .status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            post(
                    wireJson.encodeToString(
                        query.copy(checkIn = "2026-10-01", checkOut = "2026-10-03")
                    )
                )
                .status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            post(wireJson.encodeToString(query).replace("\"USD\"", "\"XXX\"")).status,
        )
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            post(wireJson.encodeToString(query).replace("\"us\"", "\"zz\"")).status,
        )
        assertEquals(HttpStatusCode.PayloadTooLarge, post(" ".repeat(8193)).status)
        assertEquals(
            HttpStatusCode.UnsupportedMediaType,
            client.post("/v1/comparison/search") { setBody("{}") }.status,
        )
        assertEquals(0, upstream)
    }

    @Test
    fun `detail tokens are data and cannot become arbitrary provider URLs`() = testApplication {
        var upstream = 0
        val service =
            ComparisonService(
                SerpApiProvider("synthetic-test-provider-key") {
                    upstream++
                    ProviderReply(200, "{}")
                },
                clock,
            )
        application { comparisonModule(service) }
        val response =
            client.post("/v1/comparison/property") {
                contentType(ContentType.Application.Json)
                setBody(
                    wireJson
                        .encodeToString(
                            ComparisonPropertyRequest(query, "property", ComparisonKind.HOTEL)
                        )
                        .replace("\"property\"", "\"https://localhost/private\"")
                )
            }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals(0, upstream)
    }

    @Test
    fun `comparison calls are rate limited while health remains available`() = testApplication {
        application { comparisonModule(ComparisonService(SerpApiProvider(null), clock)) }
        repeat(10) {
            assertEquals(
                HttpStatusCode.OK,
                client
                    .post("/v1/comparison/search") {
                        contentType(ContentType.Application.Json)
                        setBody(wireJson.encodeToString(query))
                    }
                    .status,
            )
        }
        assertEquals(
            HttpStatusCode.TooManyRequests,
            client
                .post("/v1/comparison/search") {
                    contentType(ContentType.Application.Json)
                    setBody(wireJson.encodeToString(query))
                }
                .status,
        )
        assertEquals(HttpStatusCode.OK, client.get("/health/ready").status)
    }

    @Test
    fun `provider exception messages are never included in API responses`() = testApplication {
        val secret = "synthetic-secret-that-must-not-leak"
        val service =
            ComparisonService(
                SerpApiProvider(secret) {
                    error("https://serpapi.com/search.json?api_key=$secret")
                },
                clock,
            )
        application { comparisonModule(service) }
        val response =
            client.post("/v1/comparison/search") {
                contentType(ContentType.Application.Json)
                setBody(wireJson.encodeToString(query))
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains(secret))
        assertFalse(body.contains("api_key"))
        assertTrue(
            wireJson.decodeFromString<ComparisonResult>(body).sourceStatuses.all {
                it.status == ComparisonSourceState.ERROR
            }
        )
    }
}
