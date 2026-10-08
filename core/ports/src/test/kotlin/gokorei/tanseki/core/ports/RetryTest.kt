package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RateLimitedException
import gokorei.tanseki.core.domain.UnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RetryTest {
    @Test
    fun `retries retryable failures then succeeds`() =
        runBlocking {
            var attempts = 0
            val result =
                retry(RetryPolicy(maxAttempts = 3, baseDelay = 1.milliseconds)) {
                    attempts++
                    if (attempts < 3) throw UnavailableException("down")
                    "ok"
                }
            assertEquals("ok", result)
            assertEquals(3, attempts)
        }

    @Test
    fun `gives up after max attempts`() {
        var attempts = 0
        assertThrows(UnavailableException::class.java) {
            runBlocking {
                retry(RetryPolicy(maxAttempts = 2, baseDelay = 1.milliseconds)) {
                    attempts++
                    throw UnavailableException("down")
                }
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `terminal errors are not retried`() {
        var attempts = 0
        assertThrows(NotFoundException::class.java) {
            runBlocking {
                retry(RetryPolicy(maxAttempts = 3, baseDelay = 1.milliseconds)) {
                    attempts++
                    throw NotFoundException()
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation is not retried`() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                retry(RetryPolicy(maxAttempts = 3, baseDelay = 1.milliseconds)) {
                    attempts++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `default classification distinguishes retryable from terminal`() {
        assertTrue(RetryPolicy.defaultRetryable(UnavailableException("x")))
        assertTrue(RetryPolicy.defaultRetryable(RateLimitedException(10)))
        assertFalse(RetryPolicy.defaultRetryable(NotFoundException()))
        assertFalse(RetryPolicy.defaultRetryable(ConflictException(null, "x")))
    }

    @Test
    fun `zero jitter produces deterministic delays`() {
        val policy = RetryPolicy(baseDelay = 1.milliseconds, maxDelay = 1.milliseconds, jitter = 0.0)

        assertEquals(1.milliseconds, policy.delayFor(1))
    }

    @Test
    fun `delay grows exponentially and is capped with jitter bounds`() {
        val policy = RetryPolicy(baseDelay = 100.milliseconds, maxDelay = 400.milliseconds, jitter = 0.5)

        assertTrue(policy.delayFor(1) > Duration.ZERO)
        assertTrue(policy.delayFor(1) <= 200.milliseconds)
        assertTrue(policy.delayFor(2) <= 400.milliseconds)
        // attempt 5 would be 1600ms uncapped; capped at 400ms (plus <= 50% jitter)
        assertTrue(policy.delayFor(5) <= 600.milliseconds)
    }
}
