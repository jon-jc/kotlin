package com.roam.server

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*

data class ProviderIntent(
    val id: String,
    val clientSecret: String?,
    val status: String,
    val amount: Long,
    val currency: String,
    val reservationId: String,
    val live: Boolean,
    val receivedAmount: Long = 0,
)

data class ProviderRefund(
    val id: String,
    val status: String,
    val amount: Long,
    val paymentIntentId: String,
)

interface Payments {
    fun create(reservationId: String, amount: Long): ProviderIntent

    fun retrieve(id: String): ProviderIntent

    fun cancel(id: String, reservationId: String): ProviderIntent

    fun refund(id: String, reservationId: String, amount: Long): ProviderRefund

    fun retrieveRefund(id: String): ProviderRefund
}

/** Provider requests never carry a client-controlled amount, URL, or idempotency key. */
class StripePayments(private val secret: String) : Payments {
    private val http = BoundedHttp()

    private fun request(
        path: String,
        params: Map<String, String>? = null,
        key: String? = null,
    ): JsonObject {
        val request =
            HttpRequest.newBuilder(URI("https://api.stripe.com/v1/$path"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $secret")
                .header("Stripe-Version", "2025-09-30.clover")
        if (key != null) request.header("Idempotency-Key", key)
        if (params == null) request.GET()
        else {
            fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
            val form = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
            request
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
        }
        try {
            val response = http.send(request.build(), 262144)
            if (response.statusCode() !in 200..299) unavailable()
            return wireJson.parseToJsonElement(response.body().toString(Charsets.UTF_8)).jsonObject
        } catch (e: ApiFailure) {
            throw e
        } catch (_: Exception) {
            unavailable()
        }
    }

    override fun create(reservationId: String, amount: Long): ProviderIntent =
        intent(
            request(
                "payment_intents",
                linkedMapOf(
                    "amount" to amount.toString(),
                    "currency" to "usd",
                    "payment_method_types[]" to "card",
                    "metadata[reservation_id]" to reservationId,
                    "description" to "Roam reservation $reservationId",
                ),
                "roam-reserve-$reservationId",
            )
        )

    override fun retrieve(id: String) = intent(request("payment_intents/${safeId(id)}"))

    override fun cancel(id: String, reservationId: String) =
        intent(
            request(
                "payment_intents/${safeId(id)}/cancel",
                mapOf("cancellation_reason" to "requested_by_customer"),
                "roam-cancel-$reservationId",
            )
        )

    override fun refund(id: String, reservationId: String, amount: Long) =
        refundResult(
            request(
                "refunds",
                linkedMapOf(
                    "payment_intent" to safeId(id),
                    "amount" to amount.toString(),
                    "metadata[reservation_id]" to reservationId,
                ),
                "roam-refund-$reservationId",
            )
        )

    override fun retrieveRefund(id: String) = refundResult(request("refunds/${safeId(id)}"))

    private fun intent(json: JsonObject) =
        ProviderIntent(
            json.text("id"),
            json["client_secret"]?.jsonPrimitive?.contentOrNull,
            json.text("status"),
            json["amount"]!!.jsonPrimitive.long,
            json.text("currency"),
            json["metadata"]?.jsonObject?.get("reservation_id")?.jsonPrimitive?.content ?: "",
            json["livemode"]!!.jsonPrimitive.boolean,
            json["amount_received"]!!.jsonPrimitive.long,
        )

    private fun refundResult(json: JsonObject) =
        ProviderRefund(
            json.text("id"),
            json.text("status"),
            json["amount"]!!.jsonPrimitive.long,
            json.text("payment_intent"),
        )

    private fun safeId(value: String): String =
        value.also { require(it.matches(Regex("[A-Za-z0-9_]{1,100}"))) }

    private fun unavailable(): Nothing =
        throw ApiFailure(
            503,
            "PAYMENT_UNAVAILABLE",
            "Payment confirmation is temporarily unavailable. Your request is saved; retry safely.",
        )
}

internal fun JsonObject.text(key: String): String =
    get(key)?.jsonPrimitive?.content ?: error("Missing provider field")

/**
 * Verifies the exact received bytes before parsing. Timestamp checks also bound replay eligibility.
 */
class StripeWebhook(
    private val secret: String,
    private val live: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(bytes: ByteArray, signature: String?): JsonObject {
        try {
            require(bytes.size <= 65536 && signature != null && signature.length <= 2048)
            val parts =
                signature.split(',').mapNotNull { value ->
                    val pair = value.trim().split('=', limit = 2)
                    if (pair.size == 2) pair[0] to pair[1] else null
                }
            val timestamps = parts.filter { it.first == "t" }
            require(timestamps.size == 1)
            val timestamp = timestamps.single().second.toLong()
            val now = clock.instant().epochSecond
            require(timestamp in (now - 300)..(now + 300))
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            mac.update("$timestamp.".toByteArray())
            val expected = mac.doFinal(bytes)
            val valid =
                parts
                    .filter { it.first == "v1" }
                    .any { (_, value) ->
                        if (!value.matches(Regex("[a-fA-F0-9]{64}"))) false
                        else
                            MessageDigest.isEqual(
                                expected,
                                value.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                            )
                    }
            require(valid)
            val event = wireJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(event["livemode"]?.jsonPrimitive?.boolean == live)
            require(event.text("id").matches(Regex("evt_[A-Za-z0-9]{1,100}")))
            return event
        } catch (_: Exception) {
            throw ApiFailure(400, "INVALID_WEBHOOK", "The webhook could not be verified.")
        }
    }
}
