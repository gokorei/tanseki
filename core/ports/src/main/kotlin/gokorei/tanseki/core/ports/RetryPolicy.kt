package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.RateLimitedException
import gokorei.tanseki.core.domain.TansekiException
import gokorei.tanseki.core.domain.UnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Retry policy for adapter calls (Pijul subprocess reads, Lookup reads, embeddings).
 * Retryable errors are [UnavailableException] and [RateLimitedException];
 * everything else (NotFound/Conflict/InvalidInput) is terminal.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelay: Duration = 50.milliseconds,
    val maxDelay: Duration = 2.seconds,
    val jitter: Double = 0.2,
    val isRetryable: (Throwable) -> Boolean = ::defaultRetryable
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(jitter in 0.0..1.0) { "jitter must be in [0, 1]" }
    }

    /** Exponential backoff with jitter for the 1-based [attempt]. */
    fun delayFor(attempt: Int): Duration {
        val exponent = (attempt - 1).coerceIn(0, 20)
        val exponential = baseDelay * (1 shl exponent)
        val capped = if (exponential > maxDelay) maxDelay else exponential
        val factor = if (jitter == 0.0) 1.0 else 1.0 - jitter + Random.nextDouble(0.0, 2 * jitter)
        return capped * factor
    }

    companion object {
        fun defaultRetryable(error: Throwable): Boolean =
            when (error) {
                is UnavailableException, is RateLimitedException -> true
                is TansekiException -> false
                else -> false
            }
    }
}

/** Runs [block] under [policy], retrying only retryable failures. */
suspend fun <T> retry(policy: RetryPolicy = RetryPolicy(), block: suspend () -> T): T {
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (attempt >= policy.maxAttempts || !policy.isRetryable(error)) throw error
            val retryAfter = (error as? RateLimitedException)?.retryAfterMillis
            delay(retryAfter?.let { it.milliseconds } ?: policy.delayFor(attempt))
            attempt++
        }
    }
}
