package com.roam.server

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val issuer = "https://test.supabase.co/auth/v1"
    private val user = UUID.randomUUID().toString()

    @Test
    fun `Supabase ES256 token verifies but wrong claims and anonymous sessions fail`() {
        val key = ECKeyGenerator(Curve.P_256).keyID("ec-key").generate()
        val verifier = TokenVerifier(issuer, "authenticated", { JWKSet(key.toPublicJWK()) }, clock)
        fun token(
            audience: String = "authenticated",
            iss: String = issuer,
            expires: Instant = now.plusSeconds(300),
            anonymous: Boolean = false,
            role: String = "authenticated",
        ): String {
            val claims =
                JWTClaimsSet.Builder()
                    .issuer(iss)
                    .subject(user)
                    .audience(audience)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expires))
                    .claim("role", role)
                    .claim("is_anonymous", anonymous)
                    .build()
            return SignedJWT(
                    JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(key.keyID)
                        .type(JOSEObjectType.JWT)
                        .build(),
                    claims,
                )
                .apply { sign(ECDSASigner(key)) }
                .serialize()
        }
        assertEquals(user, verifier.verify(token()))
        assertFailure(401) { verifier.verify(token(audience = "wrong")) }
        assertFailure(401) { verifier.verify(token(iss = "https://attacker.example")) }
        assertFailure(401) { verifier.verify(token(expires = now)) }
        assertFailure(401) { verifier.verify(token(anonymous = true)) }
        assertFailure(401) { verifier.verify(token(role = "service_role")) }
        val original = token()
        assertFailure(401) { verifier.verify(original.dropLast(20) + "AAAAAAAAAAAAAAAAAAAA") }
        assertFailure(401) { verifier.verify("x".repeat(8193)) }
    }

    @Test
    fun `Supabase RSA tokens verify and unrelated keys cannot impersonate users`() {
        val key = RSAKeyGenerator(2048).keyID("rsa-key").generate()
        val claims =
            JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user)
                .audience("authenticated")
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("role", "authenticated")
                .build()
        val token =
            SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
                .apply { sign(RSASSASigner(key)) }
                .serialize()
        assertEquals(
            user,
            TokenVerifier(issuer, "authenticated", { JWKSet(key.toPublicJWK()) }, clock)
                .verify(token),
        )
        val unrelated = RSAKeyGenerator(2048).keyID("rsa-key").generate()
        assertFailure(401) {
            TokenVerifier(issuer, "authenticated", { JWKSet(unrelated.toPublicJWK()) }, clock)
                .verify(token)
        }
    }

    @Test
    fun `webhooks reject tampering expiry wrong mode and malformed signatures`() {
        val secret = "whsec_local_test_secret"
        val verifier = StripeWebhook(secret, false, clock)
        val raw =
            """{"id":"evt_test123","livemode":false,"type":"payment_intent.succeeded"}"""
                .toByteArray()
        val signature = signWebhook(raw, secret, now.epochSecond)
        assertEquals("evt_test123", verifier.verify(raw, signature).text("id"))
        assertFailure(400) { verifier.verify(raw + " ".toByteArray(), signature) }
        assertFailure(400) { verifier.verify(raw, signWebhook(raw, secret, now.epochSecond - 301)) }
        assertFailure(400) { StripeWebhook(secret, true, clock).verify(raw, signature) }
        assertFailure(400) { verifier.verify(raw, "t=bad,v1=00") }
        assertFailure(400) { verifier.verify(raw, null) }
    }

    @Test
    fun `configuration rejects missing secrets plaintext identity and ambiguous live modes`() {
        val base =
            mapOf(
                "ROAM_ENV" to "development",
                "DATABASE_URL" to "jdbc:postgresql://localhost/roam",
                "DATABASE_USER" to "roam",
                "DATABASE_PASSWORD" to "test-only",
                "SUPABASE_URL" to "https://test.supabase.co",
                "STRIPE_SECRET_KEY" to "sk_test_local",
                "STRIPE_WEBHOOK_SECRET" to "whsec_local_testing",
            )
        assertFalse(ServerConfig.load(base).livePayments)
        assertTrue(runCatching { ServerConfig.load(base - "STRIPE_SECRET_KEY") }.isFailure)
        assertTrue(
            runCatching { ServerConfig.load(base + ("SUPABASE_URL" to "http://test.supabase.co")) }
                .isFailure
        )
        assertTrue(
            runCatching { ServerConfig.load(base + ("ROAM_LIVE_PAYMENTS" to "true")) }.isFailure
        )
        assertTrue(runCatching { ServerConfig.load(base + ("ROAM_ENV" to "production")) }.isFailure)
        val production =
            base + mapOf("ROAM_ENV" to "production", "ROAM_INGRESS_RATE_LIMITED" to "true")
        assertFalse(
            ServerConfig.load(
                    production +
                        ("DATABASE_URL" to "jdbc:postgresql://db.example/roam?sslmode=verify-full")
                )
                .livePayments
        )
        assertTrue(
            runCatching {
                    ServerConfig.load(
                        production +
                            ("DATABASE_URL" to
                                "jdbc:postgresql://db.example/roam?sslmode=verify-full&sslmode=disable")
                    )
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    ServerConfig.load(
                        production +
                            ("DATABASE_URL" to
                                "jdbc:postgresql://db.example/roam?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory")
                    )
                }
                .isFailure
        )
    }
}

internal fun signWebhook(bytes: ByteArray, secret: String, timestamp: Long): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    mac.update("$timestamp.".toByteArray())
    val digest = mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
    return "t=$timestamp,v1=$digest"
}
