package com.roam.server

import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** A deadline includes the complete body, which HttpRequest.timeout alone does not guarantee. */
internal class BoundedHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun send(
        request: HttpRequest,
        maxBytes: Int,
        timeoutMillis: Long = 20_000,
    ): HttpResponse<ByteArray> {
        val future = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBody(maxBytes) })
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            future.cancel(true)
            throw failure
        }
    }

    private class LimitedBody(private val maximum: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val completed = CompletableFuture<ByteArray>()
        private val bytes = ByteArrayOutputStream()
        private var subscription: Flow.Subscription? = null

        override fun getBody(): CompletionStage<ByteArray> = completed

        override fun onSubscribe(value: Flow.Subscription) {
            if (subscription != null) value.cancel()
            else {
                subscription = value
                value.request(1)
            }
        }

        override fun onNext(chunks: List<ByteBuffer>) {
            for (chunk in chunks) {
                if (chunk.remaining() > maximum - bytes.size()) {
                    subscription?.cancel()
                    completed.completeExceptionally(
                        IllegalStateException("Response exceeded size limit")
                    )
                    return
                }
                val buffer = ByteArray(chunk.remaining())
                chunk.get(buffer)
                bytes.write(buffer)
            }
            subscription?.request(1)
        }

        override fun onError(failure: Throwable) {
            completed.completeExceptionally(failure)
        }

        override fun onComplete() {
            completed.complete(bytes.toByteArray())
        }
    }
}
