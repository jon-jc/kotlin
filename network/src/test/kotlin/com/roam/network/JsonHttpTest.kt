package com.roam.network

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class JsonHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun close() {
        server.shutdown()
    }

    @Test
    fun `cancelling after headers interrupts an unfinished body read`() = runBlocking {
        val headersReceived = CountDownLatch(1)
        val client =
            OkHttpClient.Builder()
                .eventListener(
                    object : EventListener() {
                        override fun responseHeadersEnd(call: Call, response: Response) {
                            headersReceived.countDown()
                        }
                    }
                )
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        server.enqueue(json("{}").setBodyDelay(3, TimeUnit.SECONDS))
        val operation =
            async(Dispatchers.Default) { JsonHttp(client).request(server.loopbackUrl("/slow")) }
        assertTrue(headersReceived.await(5, TimeUnit.SECONDS))
        withTimeout(2000) { operation.cancelAndJoin() }
        assertTrue(operation.isCancelled)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `response body timeout remains effective after immediate headers`() = runBlocking {
        val client =
            OkHttpClient.Builder()
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build()
        server.enqueue(json("{}").setBodyDelay(2, TimeUnit.SECONDS))
        val failure =
            withTimeout(2000) {
                runCatching { JsonHttp(client).request(server.loopbackUrl("/slow")) }
                    .exceptionOrNull()
            }
        assertTrue(failure is IOException)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `responses exceeding the byte cap fail without returning the body`() = runBlocking {
        server.enqueue(json("x".repeat(2 * 1024 * 1024 + 1)))
        val failure =
            runCatching { JsonHttp().request(server.loopbackUrl("/large")) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Response exceeded the supported size", failure!!.message)
    }

    @Test
    fun `provider diagnostics never escape through the failure message`() = runBlocking {
        server.enqueue(
            json("""{"code":"DECLINED","message":"private-token-and-profile-data"}""")
                .setResponseCode(402)
        )
        val failure =
            runCatching { JsonHttp().request(server.loopbackUrl("/private")) }.exceptionOrNull()
        assertTrue(failure is HttpFailure)
        assertEquals("DECLINED", (failure as HttpFailure).code)
        assertFalse(failure.toString().contains("private-token-and-profile-data"))
        assertNull(failure.cause)
    }

    private fun json(text: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(text)
}
