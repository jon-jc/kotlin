package com.roam.server.comparison

import com.roam.core.*
import com.roam.server.ApiFailure
import com.roam.server.wireJson
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComparisonServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    private val key = "synthetic-provider-key-only-for-tests"
    private val query = ComparisonQuery("Kyoto", "2026-10-10", "2026-10-13")

    private fun service(
        timeout: Long = 12500,
        budget: ComparisonBudget = ComparisonBudget(clock),
        parallel: Semaphore = Semaphore(4),
        includeEcho: Boolean = true,
        transport: ComparisonTransport,
    ) =
        ComparisonService(
            SerpApiProvider(key) { parameters ->
                val reply = transport.get(parameters)
                val root =
                    runCatching { wireJson.parseToJsonElement(reply.body) as? JsonObject }
                        .getOrNull()
                if (!includeEcho || root == null || "search_parameters" in root) reply
                else
                    reply.copy(
                        body =
                            JsonObject(
                                    root +
                                        ("search_parameters" to
                                            JsonObject(
                                                parameters
                                                    .filterKeys {
                                                        it !in
                                                            setOf("api_key", "no_cache", "output")
                                                    }
                                                    .mapValues { JsonPrimitive(it.value) }
                                            ))
                                )
                                .toString()
                    )
            },
            clock,
            timeout,
            budget,
            parallel,
        )

    @Test
    fun `all searches use separate fresh hotel and rental requests with exact trip context`() =
        runTest {
            val calls = mutableListOf<Map<String, String>>()
            val service = service { parameters ->
                calls += parameters
                ProviderReply(
                    200,
                    propertyReply(
                        if (parameters["vacation_rentals"] == "true") "vacation rental" else "hotel"
                    ),
                )
            }
            val request = query.copy(childAges = listOf(1, 9), currency = "EUR", market = "de")
            val result = service.search(request)
            assertEquals(2, calls.size)
            calls.forEach {
                assertEquals("true", it["no_cache"])
                assertEquals(key, it["api_key"])
                assertEquals("2026-10-10", it["check_in_date"])
                assertEquals("2026-10-13", it["check_out_date"])
                assertEquals("2", it["children"])
                assertEquals("1,9", it["children_ages"])
                assertEquals("EUR", it["currency"])
                assertEquals("de", it["gl"])
            }
            assertEquals(1, calls.count { it["vacation_rentals"] == "true" })
            assertEquals(2, result.properties.size)
            assertTrue(result.sourceStatuses.all { it.status == ComparisonSourceState.LIVE })
            assertEquals("321.07", result.properties.first().summaryPrice!!.total!!.amount)
            assertEquals(
                TaxCoverage.REPORTED_INCLUDED,
                result.properties.first().summaryPrice!!.taxCoverage,
            )
            assertEquals(request, result.query)
            assertEquals("2026-10-02T12:05:00Z", result.expiresAt)
        }

    @Test
    fun `exact hotel name supports documented root details only with valid marker type token and echo`() =
        runTest {
            val list = wireJson.parseToJsonElement(propertyReply()).jsonObject
            val property = list.getValue("properties").jsonArray.single().jsonObject
            val root =
                JsonObject(
                    property +
                        mapOf(
                            "search_metadata" to list.getValue("search_metadata"),
                            "search_information" to
                                buildJsonObject {
                                    put(
                                        "hotels_results_state",
                                        "Showing results for property details",
                                    )
                                },
                        )
                )
            val request =
                query.copy(destination = "Example property", scope = ComparisonScope.HOTELS)
            val result = service { ProviderReply(200, root.toString()) }.search(request)
            assertEquals(ComparisonSourceState.LIVE, result.sourceStatuses.single().status)
            assertEquals("property_token", result.properties.single().propertyToken)
            assertEquals("Example property", result.properties.single().title)
            assertEquals("321.07", result.properties.single().summaryPrice!!.total!!.amount)

            val invalid =
                listOf(
                    JsonObject(root - "search_information"),
                    JsonObject(root + ("property_token" to JsonPrimitive("https://invalid/token"))),
                    JsonObject(root + ("type" to JsonPrimitive("restaurant"))),
                    JsonObject(root + ("type" to JsonPrimitive("vacation rental"))),
                    JsonObject(root + ("properties" to JsonNull)),
                )
            for (reply in invalid) {
                val rejected = service { ProviderReply(200, reply.toString()) }.search(request)
                assertTrue(rejected.properties.isEmpty())
                assertEquals(ComparisonSourceState.ERROR, rejected.sourceStatuses.single().status)
            }
            val missingEcho =
                service(includeEcho = false) { ProviderReply(200, root.toString()) }.search(request)
            assertTrue(missingEcho.properties.isEmpty())
            assertEquals(ComparisonSourceState.ERROR, missingEcho.sourceStatuses.single().status)
        }

    @Test
    fun `total is never multiplied from nightly or rounded and unknown taxes stay unknown`() =
        runTest {
            val service = service {
                ProviderReply(
                    200,
                    propertyReply(total = "null", nightly = "99.99", before = "null"),
                )
            }
            val price =
                service
                    .search(query.copy(scope = ComparisonScope.HOTELS))
                    .properties
                    .single()
                    .summaryPrice!!
            assertNull(price.total)
            assertEquals("99.99", price.nightly!!.amount)
            assertEquals(TaxCoverage.UNKNOWN, price.taxCoverage)
            val invalid =
                service { ProviderReply(200, propertyReply(total = "123.456", before = "999")) }
                    .search(query.copy(scope = ComparisonScope.HOTELS))
                    .properties
                    .single()
                    .summaryPrice!!
            assertNull(invalid.total)
            assertEquals(TaxCoverage.UNKNOWN, invalid.taxCoverage)
            val unknown =
                service { ProviderReply(200, propertyReply(before = "999")) }
                    .search(query.copy(scope = ComparisonScope.HOTELS))
                    .properties
                    .single()
                    .summaryPrice!!
            assertEquals("321.07", unknown.total!!.amount)
            assertEquals(TaxCoverage.UNKNOWN, unknown.taxCoverage)
        }

    @Test
    fun `JPY is preserved as exact whole units without assuming cents`() = runTest {
        val result =
            service {
                    ProviderReply(
                        200,
                        propertyReply(total = "25000.0", nightly = "8333", before = "23000"),
                    )
                }
                .search(query.copy(currency = "JPY", scope = ComparisonScope.HOTELS))
        assertEquals("25000", result.properties.single().summaryPrice!!.total!!.amount)
        assertEquals(25000L, result.properties.single().summaryPrice!!.total!!.minorUnits())
    }

    @Test
    fun `slow rental search times out without discarding successful hotels`() = runTest {
        val service =
            service(timeout = 100) { parameters ->
                if (parameters["vacation_rentals"] == "true") delay(1000)
                ProviderReply(200, propertyReply())
            }
        val result = service.search(query)
        assertEquals(1, result.properties.size)
        assertEquals(
            listOf(ComparisonSourceState.LIVE, ComparisonSourceState.TIMEOUT),
            result.sourceStatuses.map { it.status },
        )
    }

    @Test
    fun `HTTP quota and provider errors stay distinct and never expose response secrets`() =
        runTest {
            val result =
                service { parameters ->
                        if (parameters["vacation_rentals"] == "true")
                            ProviderReply(429, "secret=$key")
                        else
                            ProviderReply(
                                200,
                                """{"search_metadata":{"status":"Error"},"error":"Upstream $key"}""",
                            )
                    }
                    .search(query)
            assertEquals(
                listOf(ComparisonSourceState.ERROR, ComparisonSourceState.QUOTA),
                result.sourceStatuses.map { it.status },
            )
            assertFalse(wireJson.encodeToString(result).contains(key))
        }

    @Test
    fun `unconfigured provider and empty live result are not reported as the same thing`() =
        runTest {
            var called = false
            val unavailable =
                ComparisonService(
                    SerpApiProvider(null) {
                        called = true
                        error("not called")
                    },
                    clock,
                )
            val result = unavailable.search(query)
            assertFalse(called)
            assertTrue(
                result.sourceStatuses.all { it.status == ComparisonSourceState.NOT_CONFIGURED }
            )
            val empty =
                service {
                        ProviderReply(
                            200,
                            """{"search_metadata":{"status":"Success"},"properties":[]}""",
                        )
                    }
                    .search(query)
            assertTrue(empty.sourceStatuses.all { it.status == ComparisonSourceState.EMPTY })
        }

    @Test
    fun `malformed and mismatched provider replies cannot become live results`() = runTest {
        val malformed =
            service { ProviderReply(200, """{"search_metadata":{"status":"Success"}}""") }
                .search(query)
        assertTrue(malformed.sourceStatuses.all { it.status == ComparisonSourceState.ERROR })
        val mismatch =
            service {
                    ProviderReply(
                        200,
                        propertyReply()
                            .replace(
                                "\"properties\"",
                                "\"search_parameters\":{\"currency\":\"JPY\"},\"properties\"",
                            ),
                    )
                }
                .search(query)
        assertTrue(mismatch.sourceStatuses.all { it.status == ComparisonSourceState.ERROR })
        val missingEcho =
            service(includeEcho = false) { ProviderReply(200, propertyReply()) }.search(query)
        assertTrue(missingEcho.properties.isEmpty())
        assertTrue(missingEcho.sourceStatuses.all { it.status == ComparisonSourceState.ERROR })
    }

    @Test
    fun `malformed detail rows remain errors while an explicit empty prices list stays empty`() =
        runTest {
            val request = ComparisonPropertyRequest(query, "property_token", ComparisonKind.HOTEL)
            val broken =
                service {
                        ProviderReply(
                            200,
                            """{"search_metadata":{"status":"Success"},"prices":[{}]}""",
                        )
                    }
                    .details(request)
            assertEquals(ComparisonSourceState.ERROR, broken.sourceStatuses.single().status)
            val empty =
                service {
                        ProviderReply(
                            200,
                            """{"search_metadata":{"status":"Success"},"prices":[]}""",
                        )
                    }
                    .details(request)
            assertEquals(ComparisonSourceState.EMPTY, empty.sourceStatuses.single().status)
        }

    @Test
    fun `invalid current dates reject before spending provider searches`() = runTest {
        var calls = 0
        val service = service {
            calls++
            ProviderReply(200, propertyReply())
        }
        for (request in
            listOf(
                query.copy(checkIn = "2026-10-01", checkOut = "2026-10-03"),
                query.copy(checkIn = "2027-10-03", checkOut = "2027-10-05"),
            )) {
            try {
                service.search(request)
                fail("invalid dates accepted")
            } catch (failure: ApiFailure) {
                assertEquals(422, failure.status)
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun `details preserve search and compare distinct provider totals with safe handoffs`() =
        runTest {
            var captured: Map<String, String>? = null
            val service = service { parameters ->
                captured = parameters
                ProviderReply(
                    200,
                    """{
              "search_metadata":{"status":"Success"},"property_token":"property_token",
              "prices":[
                {"source":"Booking example","link":"https://www.booking.com/hotel/example.html","num_guests":2,"total_rate":{"extracted_lowest":321.07,"extracted_before_taxes_fees":300},"discount_remarks":["Member rate"],"free_cancellation":true,"free_cancellation_until_date":"2026-10-07"},
                {"source":"Other example","link":"https://www.expedia.com/example","num_guests":2,"total_rate":{"extracted_lowest":310}},
                {"source":"Unsafe","link":"https://serpapi.com/search.json?api_key=$key"},
                {"source":"Wrong occupancy","link":"https://www.booking.com/example","num_guests":1,"total_rate":{"extracted_lowest":5}}
              ]}""",
                )
            }
            val result =
                service.details(
                    ComparisonPropertyRequest(query, "property_token", ComparisonKind.HOTEL)
                )
            assertEquals("property_token", captured!!["property_token"])
            assertEquals("true", captured!!["no_cache"])
            assertEquals(2, result.rates.size)
            assertEquals("Member rate", result.rates.first().title)
            assertTrue(result.rates.first().cancellation!!.contains("2026-10-07"))
            assertEquals(TaxCoverage.UNKNOWN, result.rates.last().price!!.taxCoverage)
            assertFalse(wireJson.encodeToString(result).contains(key))
        }

    @Test
    fun `provider text and links cannot reflect the credential into the API`() = runTest {
        val body =
            propertyReply()
                .replace("Example property", key)
                .replace("\"property_token\"", "\"$key\"")
        val result = service { ProviderReply(200, body) }.search(query)
        assertTrue(result.properties.isEmpty())
        assertFalse(wireJson.encodeToString(result).contains(key))
    }

    @Test
    fun `all search charges each upstream call and the daily budget stops extra calls`() = runTest {
        var calls = 0
        val service =
            service(budget = ComparisonBudget(clock, dailyLimit = 2)) {
                calls++
                ProviderReply(
                    200,
                    propertyReply(
                        if (it["vacation_rentals"] == "true") "vacation rental" else "hotel"
                    ),
                )
            }
        assertEquals(2, service.search(query).properties.size)
        assertTrue(
            service.search(query).sourceStatuses.all { it.status == ComparisonSourceState.QUOTA }
        )
        assertEquals(2, calls)
    }

    @Test
    fun `bulkhead does not queue unlimited provider calls`() = runTest {
        val service =
            service(parallel = Semaphore(1)) {
                delay(10)
                ProviderReply(200, propertyReply())
            }
        val result = service.search(query)
        assertEquals(1, result.sourceStatuses.count { it.status == ComparisonSourceState.BUSY })
        assertEquals(1, result.properties.size)
    }

    @Test
    fun `cancelled caller cancels provider work and releases the concurrency permit`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val permit = Semaphore(1)
        val service =
            service(parallel = permit) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        val job = launch { service.search(query.copy(scope = ComparisonScope.HOTELS)) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(1, permit.availablePermits)
    }

    @Test
    fun `public configuration needs explicit production cost acknowledgement and redacts secrets`() {
        assertNull(ComparisonConfig.load(mapOf("ROAM_ENV" to "development")).apiKey)
        val production = mapOf("SERPAPI_API_KEY" to key, "ROAM_INGRESS_RATE_LIMITED" to "true")
        assertTrue(runCatching { ComparisonConfig.load(production) }.isFailure)
        val allowed =
            ComparisonConfig.load(production + ("ROAM_COMPARISON_PUBLIC_ACCESS" to "true"))
        assertEquals(key, allowed.apiKey)
        assertFalse(allowed.toString().contains(key))
        assertTrue(
            runCatching {
                    ComparisonConfig.load(
                        production + ("ROAM_COMPARISON_DAILY_REQUEST_LIMIT" to "0")
                    )
                }
                .isFailure
        )
    }

    private fun propertyReply(
        type: String = "hotel",
        total: String = "321.07",
        nightly: String = "99.99",
        before: String = "300",
    ): String =
        """{"search_metadata":{"status":"Success"},"properties":[{
        "type":"$type","property_token":"property_token","name":"Example property",
        "total_rate":{"extracted_lowest":$total,"extracted_before_taxes_fees":$before},
        "rate_per_night":{"extracted_lowest":$nightly},"overall_rating":4.2
    }]}"""
}
