package com.roam.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.net.http.HttpRequest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Accepts only configured-issuer user tokens; token-provided URLs and symmetric keys are ignored.
 */
class TokenVerifier(
    private val issuer: String,
    private val audience: String,
    private val keys: () -> JWKSet,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(token: String): String {
        try {
            require(token.length in 1..8192)
            val jwt = SignedJWT.parse(token)
            val header = jwt.header
            require(header.algorithm in setOf(JWSAlgorithm.ES256, JWSAlgorithm.RS256))
            require(header.criticalParams.isNullOrEmpty())
            val key =
                keys().getKeyByKeyId(header.keyID ?: error("kid required"))
                    ?: error("Unknown signing key")
            require(!key.isPrivate)
            require(key.algorithm == null || key.algorithm == header.algorithm)
            val verified =
                when {
                    key is ECKey &&
                        header.algorithm == JWSAlgorithm.ES256 &&
                        key.curve == Curve.P_256 -> jwt.verify(ECDSAVerifier(key))
                    key is RSAKey && header.algorithm == JWSAlgorithm.RS256 && key.size() >= 2048 ->
                        jwt.verify(RSASSAVerifier(key))
                    else -> false
                }
            require(verified)
            val claims = jwt.jwtClaimsSet
            val now = clock.instant()
            require(claims.issuer == issuer && audience in claims.audience)
            require(claims.expirationTime?.toInstant()?.isAfter(now) == true)
            require(claims.issueTime?.toInstant()?.isAfter(now.plusSeconds(30)) != true)
            require(claims.notBeforeTime?.toInstant()?.isAfter(now.plusSeconds(30)) != true)
            require(claims.getStringClaim("role") == "authenticated")
            // Anonymous Supabase sessions must not create paid reservations.
            require(claims.getBooleanClaim("is_anonymous") != true)
            return UUID.fromString(claims.subject).toString()
        } catch (failure: ApiFailure) {
            throw failure
        } catch (_: Exception) {
            throw ApiFailure(401, "UNAUTHENTICATED", "Sign in again to continue.")
        }
    }
}

class RemoteKeys(issuer: String, private val clock: Clock = Clock.systemUTC()) : () -> JWKSet {
    private val uri = URI("$issuer/.well-known/jwks.json")
    private val http = BoundedHttp()
    private var cached: JWKSet? = null
    private var expires = Instant.EPOCH

    init {
        require(uri.scheme == "https")
    }

    @Synchronized
    override fun invoke(): JWKSet {
        if (cached != null && clock.instant().isBefore(expires)) return cached!!
        try {
            val response =
                http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    65536,
                    10_000,
                )
            require(response.statusCode() == 200) { "Identity keys unavailable" }
            return JWKSet.parse(response.body().toString(Charsets.UTF_8)).also {
                require(it.keys.isNotEmpty() && it.keys.size <= 20)
                cached = it
                expires = clock.instant().plusSeconds(300)
            }
        } catch (_: Exception) {
            throw ApiFailure(
                503,
                "IDENTITY_UNAVAILABLE",
                "Sign-in verification is temporarily unavailable. Try again shortly.",
            )
        }
    }
}
