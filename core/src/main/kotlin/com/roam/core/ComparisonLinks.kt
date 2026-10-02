package com.roam.core

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Checks navigable links without fetching URLs or resolving provider-controlled hostnames. */
object ComparisonLinks {
    fun safeBookingUrl(value: String?): String? = safeUrl(value, 8192)

    fun safeImageUrl(value: String?): String? = safeUrl(value, 4096)

    private fun safeUrl(value: String?, maximumLength: Int): String? {
        if (value == null || value.length !in 1..maximumLength || value != value.trim()) return null
        if (value.any { it.isISOControl() || it.isWhitespace() } || '\\' in value) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
        if (uri.port !in setOf(-1, 443)) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!publicHostname(host)) return null
        // Never turn a provider API URL (which may carry its server key) into a user link.
        if (host == "serpapi.com" || host.endsWith(".serpapi.com")) return null
        if (
            containsCredentials(uri.rawPath) ||
                containsCredentials(uri.rawQuery) ||
                containsCredentials(uri.rawFragment)
        )
            return null
        return value
    }

    private fun publicHostname(host: String): Boolean {
        if (host.length > 253 || host.endsWith('.') || ':' in host) return false
        val labels = host.split('.')
        if (
            labels.size < 2 ||
                labels.any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }
        )
            return false
        if (!labels.last().matches(Regex("[a-z]{2,63}"))) return false
        if (
            labels.last() in
                setOf(
                    "localhost",
                    "local",
                    "localdomain",
                    "internal",
                    "home",
                    "lan",
                    "onion",
                    "invalid",
                    "test",
                    "example",
                )
        )
            return false
        if (host == "home.arpa" || host.endsWith(".home.arpa")) return false
        return true
    }

    private fun containsCredentials(encoded: String?): Boolean {
        var text = encoded ?: return false
        repeat(5) { depth ->
            if (text.any(Char::isISOControl) || '\\' in text) return true
            if (
                Regex(
                        "(?i)(?:^|[?&;#])(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|pwd|authorization|credentials|secret|key|token)="
                    )
                    .containsMatchIn(text)
            )
                return true
            if (Regex("(?i)https?://[^/?#\\s]*@").containsMatchIn(text)) return true
            val next = runCatching { URLDecoder.decode(text, "UTF-8") }.getOrNull() ?: return true
            if (next == text) return false
            if (depth == 4) return true
            text = next
        }
        // Avoid accepting deliberately nested encodings that conceal credentials from the check.
        return true
    }
}
