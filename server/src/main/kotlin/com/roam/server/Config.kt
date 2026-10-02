package com.roam.server

import java.net.URI
import java.net.URLDecoder

data class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val issuer: String,
    val audience: String,
    val stripeSecret: String,
    val webhookSecret: String,
    val livePayments: Boolean,
    val port: Int,
    val ingressRateLimited: Boolean,
) {
    companion object {
        fun load(env: Map<String, String> = System.getenv()): ServerConfig {
            fun required(key: String): String =
                env[key]?.takeIf { it.isNotBlank() && !it.startsWith("replace-") }
                    ?: error("Missing required configuration: $key")
            val databaseUrl = required("DATABASE_URL")
            require(databaseUrl.startsWith("jdbc:postgresql://")) {
                "DATABASE_URL must be PostgreSQL JDBC"
            }
            val development = env["ROAM_ENV"] == "development"
            val ingressRateLimited = env["ROAM_INGRESS_RATE_LIMITED"] == "true"
            if (!development)
                require(ingressRateLimited) {
                    "Production requires rate limiting at the TLS ingress (ROAM_INGRESS_RATE_LIMITED=true)"
                }
            val jdbcUri = URI(databaseUrl.removePrefix("jdbc:"))
            require(jdbcUri.rawUserInfo == null && jdbcUri.rawFragment == null) {
                "Use separate database credentials"
            }
            val properties =
                jdbcUri.rawQuery
                    .orEmpty()
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .map {
                        val parts = it.split('=', limit = 2)
                        require(parts.size == 2) { "Malformed database connection property" }
                        URLDecoder.decode(parts[0], Charsets.UTF_8) to
                            URLDecoder.decode(parts[1], Charsets.UTF_8)
                    }
            val allowed =
                setOf(
                    "sslmode",
                    "sslrootcert",
                    "connectTimeout",
                    "socketTimeout",
                    "tcpKeepAlive",
                    "ApplicationName",
                )
            require(
                properties.all { it.first in allowed } &&
                    properties.map { it.first }.toSet().size == properties.size
            ) {
                "Unsupported or duplicate database connection properties"
            }
            if (!development)
                require(properties.toMap()["sslmode"] == "verify-full") {
                    "Production database connections require sslmode=verify-full"
                }
            val supabase = URI(required("SUPABASE_URL").trimEnd('/'))
            require(
                supabase.scheme == "https" &&
                    supabase.host != null &&
                    supabase.rawUserInfo == null &&
                    supabase.rawQuery == null &&
                    supabase.rawFragment == null &&
                    supabase.path.isEmpty()
            ) {
                "SUPABASE_URL must be an HTTPS origin"
            }
            val stripe = required("STRIPE_SECRET_KEY")
            val live = env["ROAM_LIVE_PAYMENTS"] == "true"
            require(stripe.startsWith(if (live) "sk_live_" else "sk_test_")) {
                "Stripe key must match the explicitly configured payment mode"
            }
            val webhook = required("STRIPE_WEBHOOK_SECRET")
            require(webhook.startsWith("whsec_") && webhook.length > 12) {
                "Invalid webhook configuration"
            }
            return ServerConfig(
                databaseUrl,
                required("DATABASE_USER"),
                required("DATABASE_PASSWORD"),
                "$supabase/auth/v1",
                "authenticated",
                stripe,
                webhook,
                live,
                (env["PORT"] ?: "8080").toInt().also { require(it in 1..65535) },
                ingressRateLimited,
            )
        }
    }
}

class ApiFailure(val status: Int, val code: String, override val message: String) :
    RuntimeException(message)

fun invalid(message: String): Nothing = throw ApiFailure(422, "INVALID_REQUEST", message)

fun missing(): Nothing = throw ApiFailure(404, "NOT_FOUND", "This item could not be found.")

fun conflict(code: String, message: String): Nothing = throw ApiFailure(409, code, message)
