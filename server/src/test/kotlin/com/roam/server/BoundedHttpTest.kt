package com.roam.server

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BoundedHttpTest {
    @Test
    fun `provider redirects are returned without following them or forwarding query credentials`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val target = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/target") { exchange ->
            target.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val request =
                HttpRequest.newBuilder(
                        URI("http://127.0.0.1:${server.address.port}/redirect?api_key=synthetic")
                    )
                    .GET()
                    .build()
            assertEquals(302, BoundedHttp().send(request, 1024).statusCode())
            assertEquals(0, target.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `body stalled after headers still respects whole response deadline`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        server.executor = executor
        server.createContext("/slow") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(byteArrayOf(1))
                exchange.responseBody.flush()
                release.await(2, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val request =
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/slow"))
                    .GET()
                    .build()
            val start = System.nanoTime()
            assertTrue(runCatching { BoundedHttp().send(request, 65536, 200) }.isFailure)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `untrusted response cannot allocate beyond configured body cap`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 4096)
                exchange.responseBody.write(ByteArray(4096))
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val request =
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/large"))
                    .GET()
                    .build()
            assertTrue(runCatching { BoundedHttp().send(request, 64) }.isFailure)
        } finally {
            server.stop(0)
        }
    }
}
