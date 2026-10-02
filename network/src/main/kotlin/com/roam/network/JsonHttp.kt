package com.roam.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Carries no response body or URL: provider diagnostics must never expose credentials or PII. */
class HttpFailure(val status: Int, val code: String? = null) :
    IOException("Request failed ($status)")

class JsonHttp(
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun request(
        url: HttpUrl,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = Request.Builder().url(url).header("Accept", "application/json")
        headers.forEach { (name, value) -> request.header(name, value) }
        val data = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
        request.method(method, data)
        return execute(request.build())
    }

    private fun read(response: Response): String =
        response.use {
            val source = response.body?.source()
            if (source != null && source.request(MAX_RESPONSE_BYTES + 1)) {
                throw IOException("Response exceeded the supported size")
            }
            val text = source?.readUtf8().orEmpty()
            if (!response.isSuccessful) {
                val code =
                    runCatching {
                            val element = json.parseToJsonElement(text)
                            (element as? kotlinx.serialization.json.JsonObject)
                                ?.get("code")
                                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                                ?.content
                        }
                        .getOrNull()
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
                throw HttpFailure(response.code, code)
            }
            if (
                text.isNotEmpty() && response.body?.contentType()?.subtype?.contains("json") != true
            ) {
                throw IOException("The service returned an unsupported response")
            }
            text
        }

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                IOException("The service could not be reached")
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val text = read(response)
                            if (continuation.isActive) continuation.resume(text)
                        } catch (failure: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                }
            )
        }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024

        fun baseUrl(value: String, allowLocalHttp: Boolean = false): HttpUrl {
            val url = value.trimEnd('/').toHttpUrl()
            require(
                url.username.isEmpty() &&
                    url.password.isEmpty() &&
                    url.query == null &&
                    url.fragment == null
            ) {
                "Service URL must not contain credentials, queries, or fragments"
            }
            val local = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
            require(url.isHttps || (allowLocalHttp && local)) { "Service URLs require HTTPS" }
            return url
        }
    }
}
