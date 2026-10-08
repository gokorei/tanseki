package gokorei.tanseki.adapters.embedder

import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.ports.RetryPolicy
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The ONNX runtime is deliberately absent from the test classpath (see
 * build.gradle.kts), so these tests exercise the session-lifecycle locking that
 * guards a native session without needing one: after close, every embed must fail
 * with a typed error before any reflective ORT call is attempted.
 */
class OnnxEmbedderConcurrencyTest {
    private fun embedder() =
        OnnxEmbedder(
            modelPath = Path.of("/nonexistent/model.onnx"),
            tokenizer = Tokenizer { _, _ -> longArrayOf(1L) },
            model = "test-model",
            dimensions = 3,
            retryPolicy = RetryPolicy(maxAttempts = 1)
        )

    @Test
    fun `embedding after close fails with a typed error`() {
        val embedder = embedder()
        embedder.close()

        val error =
            assertThrows(UnavailableException::class.java) {
                embedder.embed(listOf("hello"))
            }
        assertTrue(error.message.orEmpty().contains("closed"), "got: ${error.message}")
    }

    @Test
    fun `concurrent close calls are safe and idempotent`() {
        val embedder = embedder()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val threads =
            (1..8).map {
                Thread { runCatching { embedder.close() }.onFailure { error -> failures += error } }
            }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(failures.isEmpty(), "concurrent close threw: ${failures.map { it::class.simpleName }}")
        assertThrows(UnavailableException::class.java) { embedder.embed(listOf("after-close")) }
    }

    @Test
    fun `many threads embedding after close all fail with a typed error`() {
        val embedder = embedder()
        val unexpected = ConcurrentLinkedQueue<Throwable>()
        val closeStarted = java.util.concurrent.CountDownLatch(1)
        val threads =
            (1..8).map {
                Thread {
                    // Released only after close() has returned, so every attempt
                    // meets a closed embedder and must be rejected before any
                    // reflective ORT call is made.
                    closeStarted.await()
                    repeat(50) {
                        try {
                            embedder.embed(listOf("x"))
                            // No ORT on the test classpath, so a successful embed is
                            // impossible and means the closed check was skipped.
                            unexpected += IllegalStateException("embed succeeded on a closed embedder")
                        } catch (error: UnavailableException) {
                            // The defined failure for a closed embedder.
                        } catch (error: Throwable) {
                            unexpected += error
                        }
                    }
                }
            }
        threads.forEach { it.start() }
        embedder.close()
        closeStarted.countDown()
        threads.forEach { it.join() }

        assertTrue(
            unexpected.isEmpty(),
            "embed/close produced unexpected failures: ${unexpected.map { it::class.simpleName }}"
        )
    }
}
