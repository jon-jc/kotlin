package com.roam.server.comparison

/** Provider credentials never belong in the Android build or a client response. */
data class ComparisonConfig(
    val apiKey: String?,
    val port: Int = 8080,
    val ingressRateLimited: Boolean = false,
    val dailyRequestLimit: Int = 200,
) {
    override fun toString(): String =
        "ComparisonConfig(configured=${apiKey != null}, port=$port, ingressRateLimited=$ingressRateLimited, dailyRequestLimit=$dailyRequestLimit)"

    companion object {
        fun load(env: Map<String, String> = System.getenv()): ComparisonConfig {
            val key =
                env["SERPAPI_API_KEY"]?.trim()?.takeIf {
                    it.isNotEmpty() && !it.startsWith("replace-")
                }
            require(key == null || key.matches(Regex("[A-Za-z0-9_-]{16,256}"))) {
                "Invalid SERPAPI_API_KEY configuration"
            }
            val development = env["ROAM_ENV"] == "development"
            val ingress = env["ROAM_INGRESS_RATE_LIMITED"] == "true"
            if (!development) {
                require(ingress) {
                    "Production comparison requires rate limiting at the TLS ingress"
                }
                if (key != null)
                    require(env["ROAM_COMPARISON_PUBLIC_ACCESS"] == "true") {
                        "A credentialed public comparison service requires ROAM_COMPARISON_PUBLIC_ACCESS=true and an upstream spend budget"
                    }
            }
            val daily = (env["ROAM_COMPARISON_DAILY_REQUEST_LIMIT"] ?: "200").toInt()
            require(daily in 1..10000) {
                "Comparison daily request limit must be between 1 and 10000"
            }
            val port = (env["PORT"] ?: "8080").toInt()
            require(port in 1..65535) { "Invalid PORT" }
            return ComparisonConfig(key, port, ingress, daily)
        }
    }
}
