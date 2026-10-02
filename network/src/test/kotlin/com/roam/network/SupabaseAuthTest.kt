package com.roam.network

import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseAuthTest {
    private lateinit var server: MockWebServer
    private val user = "65c82baa-3ad6-40ca-bb4f-1e6b83f126b6"
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    private val store = MemorySessions()
    private lateinit var auth: SupabaseAuth

    @Before
    fun setup() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        auth =
            SupabaseAuth(
                server.loopbackUrl("/").toString(),
                "sb_publishable_unit_test",
                store,
                clock = clock,
                allowLocalHttp = true,
            )
    }

    @After
    fun close() {
        server.shutdown()
    }

    private fun token(
        access: String = "fresh-access",
        refresh: String = "fresh-refresh",
        id: String = user,
    ) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$id"}}"""

    private fun json(text: String, code: Int = 200) =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(text)

    @Test
    fun `OTP uses public key and session remains absent until verified`() = runBlocking {
        server.enqueue(json("{}"))
        server.enqueue(json(token()))
        auth.sendCode("  PERSON@example.com  ")
        assertNull(auth.sessions.value)
        val sent = server.takeRequest()
        assertEquals("/auth/v1/otp", sent.path)
        assertEquals("sb_publishable_unit_test", sent.getHeader("apikey"))
        assertNull(sent.getHeader("Authorization"))
        assertTrue(sent.body.readUtf8().contains("person@example.com"))
        auth.verifyCode("person@example.com", "123456")
        assertEquals(user, auth.sessions.value?.userId)
        assertEquals(auth.sessions.value, store.value)
        assertFalse(auth.sessions.value.toString().contains("fresh-access"))
    }

    @Test
    fun `concurrent expired token requests rotate once`() = runBlocking {
        store.value =
            AuthSession(user, "expired-access", "old-refresh", clock.instant().epochSecond - 1)
        auth.restore()
        server.enqueue(json(token()).setBodyDelay(100, TimeUnit.MILLISECONDS))
        val values = (1..16).map { async(Dispatchers.Default) { auth.accessToken() } }.awaitAll()
        assertEquals(setOf("fresh-access"), values.toSet())
        assertEquals(1, server.requestCount)
        assertEquals("fresh-refresh", store.value?.refreshToken)
    }

    @Test
    fun `logout during refresh erases credentials and rejects late response`() = runBlocking {
        store.value = AuthSession(user, "expired", "refresh", clock.instant().epochSecond - 1)
        auth.restore()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return if (request.path!!.contains("token")) {
                        started.countDown()
                        check(finish.await(10, TimeUnit.SECONDS))
                        json(token())
                    } else MockResponse().setResponseCode(204)
                }
            }
        val refresh = async(Dispatchers.Default) { runCatching { auth.accessToken() } }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        auth.signOut()
        assertNull(auth.sessions.value)
        assertNull(store.value)
        finish.countDown()
        assertTrue(refresh.await().exceptionOrNull() is AuthException)
        assertNull(auth.sessions.value)
    }

    @Test
    fun `invalid refresh revokes local session but transient outage preserves it`() = runBlocking {
        store.value = AuthSession(user, "expired", "refresh", clock.instant().epochSecond - 1)
        auth.restore()
        server.enqueue(json("{}", 503))
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is AuthException)
        assertNotNull(store.value)
        server.enqueue(json("{}", 401))
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is AuthException)
        assertNull(store.value)
        assertNull(auth.sessions.value)
    }

    @Test
    fun `refresh cannot replace signed in user`() = runBlocking {
        store.value = AuthSession(user, "expired", "refresh", clock.instant().epochSecond - 1)
        auth.restore()
        server.enqueue(json(token(id = "3157afbc-e2c9-4b1b-8b5d-655ff6885066")))
        assertTrue(runCatching { auth.accessToken() }.exceptionOrNull() is AuthException)
        assertNull(store.value)
    }

    @Test
    fun `HTTP redirects never forward credentials`() = runBlocking {
        val destination = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .setHeader("Location", destination.loopbackUrl("/steal"))
            )
            assertTrue(runCatching { auth.sendCode("person@example.com") }.isFailure)
            assertEquals(0, destination.requestCount)
        } finally {
            destination.shutdown()
        }
    }

    @Test
    fun `configuration rejects remote cleartext URLs and server secrets`() {
        assertThrows(IllegalArgumentException::class.java) {
            JsonHttp.baseUrl("http://example.com", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonHttp.baseUrl("https://name:secret@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseAuth("https://example.com", "sb_secret_bad", store)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseAuth("https://example.com", "eyJservice_role", store)
        }
    }
}

internal class MemorySessions : SessionStore {
    var value: AuthSession? = null

    override suspend fun read() = value

    override suspend fun write(session: AuthSession?) {
        value = session
    }
}
