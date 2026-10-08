package gokorei.tanseki.adapters.embedder

import gokorei.tanseki.core.ports.RetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import kotlin.time.Duration.Companion.milliseconds

class OnnxEmbedderRetryTest {
    @Test
    fun `transient reflective read failures recover within the bound`() =
        runBlocking {
            var attempts = 0
            val result =
                retrySafeEmbedderRead(deterministicRetryPolicy()) {
                    attempts++
                    if (attempts < 3) {
                        throw InvocationTargetException(IOException("temporarily unavailable"))
                    }
                    floatArrayOf(1f)
                }

            assertEquals(3, attempts)
            assertEquals(listOf(1f), result.toList())
        }

    @Test
    fun `terminal reflective read failures are not retried`() {
        var attempts = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                retrySafeEmbedderRead(deterministicRetryPolicy()) {
                    attempts++
                    throw InvocationTargetException(IllegalArgumentException("invalid model"))
                }
            }
        }
        assertEquals(1, attempts)
    }

    private fun deterministicRetryPolicy(): RetryPolicy =
        RetryPolicy(
            maxAttempts = 3,
            baseDelay = 1.milliseconds,
            maxDelay = 1.milliseconds,
            jitter = 0.0
        )
}
