package com.roam.server.comparison

import com.roam.server.BoundedHttp
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class ProviderReply(val status: Int, val body: String)

/** Injectable only in server tests. Production always uses the fixed HTTPS provider endpoint. */
internal fun interface ComparisonTransport {
    suspend fun get(parameters: Map<String, String>): ProviderReply
}

internal class SerpApiHttp : ComparisonTransport {
    private val http = BoundedHttp()

    override suspend fun get(parameters: Map<String, String>): ProviderReply =
        runInterruptible(Dispatchers.IO) {
            val query =
                parameters.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
                }
            // BoundedHttp uses NEVER redirects. Never log this URI: the provider requires a key
            // in its query string. Cancellation interrupts the wait and cancels the HTTP exchange.
            val request =
                HttpRequest.newBuilder(URI("https://serpapi.com/search.json?$query"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = http.send(request, 2 * 1024 * 1024, 12_000)
            ProviderReply(response.statusCode(), response.body().toString(Charsets.UTF_8))
        }
}
