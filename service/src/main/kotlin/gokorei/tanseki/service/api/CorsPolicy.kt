package gokorei.tanseki.service.api

import io.ktor.http.HttpMethod

/**
 * Cross-origin policy for browser clients.
 *
 * Off unless an operator configures origins explicitly. There is deliberately
 * no wildcard and no default: a permissive default on a knowledge store holding
 * personal notes is a decision nobody should make by accident.
 *
 * Credential support is intentionally absent. The seam authenticates with
 * header-borne keys (`X-API-Key` / `Authorization`), never cookies, so
 * `Access-Control-Allow-Credentials` would widen the attack surface for no
 * benefit — and it would make `Access-Control-Allow-Origin: *` unusable, which
 * is a hint that the two belong together.
 *
 * Origins are matched exactly, scheme + host + port. Ktor 3.5's CORS plugin has
 * no path restriction (`allowPath` does not exist in this version), so the
 * allowlist applies across the route set. The exact-origin match is the control
 * that matters; a path filter here would be a second, weaker guard that also
 * reads as if it were enforced.
 */
data class CorsPolicy(
    /** Exact origins permitted to make cross-origin requests, e.g. `http://localhost:5173`. */
    val allowedOrigins: Set<String> = emptySet()
) {
    init {
        require(allowedOrigins.none { it == "*" || it.contains('*') }) {
            "CORS allowedOrigins must be explicit origins; wildcards are not supported"
        }
        require(allowedOrigins.none(String::isBlank)) { "CORS allowedOrigins must not be blank" }
        allowedOrigins.forEach {
            require(it.startsWith("http://") || it.startsWith("https://")) {
                "CORS allowed origin must include a scheme: $it"
            }
            require(it.substringAfter("://").isNotBlank()) { "CORS allowed origin must name a host: $it" }
        }
    }

    val enabled: Boolean get() = allowedOrigins.isNotEmpty()

    /** Headers a browser client may send. Covers every credential and precondition header on the seam. */
    val allowedHeaders: Set<String> =
        setOf(
            HEADER_API_KEY,
            "Authorization",
            "If-Match",
            "If-None-Match",
            HEADER_IDEMPOTENCY,
            HEADER_REQUEST_ID,
            "Content-Type",
            "Accept"
        )

    /** Methods a UI needs: reads are GET, every write is a POST custom method. */
    val allowedMethods: Set<HttpMethod> = setOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Options)

    /** Response headers a browser client may read off a cross-origin response. */
    val exposedHeaders: Set<String> =
        setOf(HEADER_REQUEST_ID, "ETag", "Location", "Retry-After", "Content-Type")

    /** Seconds a browser may cache the preflight result. */
    val preflightMaxAgeSeconds: Long = 600

    companion object {
        const val ENV_VAR: String = "TANSEKI_CORS_ALLOW_ORIGINS"

        /** Disabled unless [raw] names at least one origin. Never infers a wildcard. */
        fun parse(raw: String?): CorsPolicy {
            val origins =
                raw
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
                    .orEmpty()
            return CorsPolicy(origins)
        }
    }
}
