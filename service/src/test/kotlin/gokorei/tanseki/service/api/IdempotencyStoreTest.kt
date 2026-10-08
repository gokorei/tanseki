package gokorei.tanseki.service.api

import gokorei.tanseki.core.ports.LogLevel
import gokorei.tanseki.core.ports.TansekiLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class IdempotencyStoreTest {
    @Test
    fun `same key with a different fingerprint conflicts`() {
        val store = IdempotencyStore()
        val acquired = store.reserve("key", "fingerprint-a") as IdempotencyReservation.Acquired
        store.complete("key", "fingerprint-a", acquired.token, StoredResponse(200, "ok"))

        val result = store.reserve("key", "fingerprint-b")

        assertTrue(result is IdempotencyReservation.Conflict)
    }

    @Test
    fun `same key and fingerprint is in flight until completed`() {
        val store = IdempotencyStore()
        val first = store.reserve("key", "fingerprint") as IdempotencyReservation.Acquired

        val pending = store.reserve("key", "fingerprint")

        assertTrue(pending is IdempotencyReservation.InFlight)
        store.complete("key", "fingerprint", first.token, StoredResponse(200, "ok"))
        assertTrue(store.reserve("key", "fingerprint") is IdempotencyReservation.Replay)
    }

    @Test
    fun `concurrent reservation is in flight, replayed, or refused but never double writes`() {
        val store = IdempotencyStore()
        val first = store.reserve("key", "fingerprint") as IdempotencyReservation.Acquired
        val start = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending =
                executor.submit<IdempotencyReservation> {
                    start.await()
                    store.reserve("key", "fingerprint")
                }
            start.countDown()
            store.complete("key", "fingerprint", first.token, StoredResponse(200, "ok", mapOf("ETag" to "\"r1\"")))
            when (val result = pending.get(5, TimeUnit.SECONDS)) {
                is IdempotencyReservation.Replay -> {
                    assertEquals(200, result.response.status)
                    assertEquals("\"r1\"", result.response.headers["ETag"])
                }

                is IdempotencyReservation.Conflict -> {
                    Unit
                }

                is IdempotencyReservation.InFlight -> {
                    // The retry lost the race to the holder's publish; waiting is
                    // what makes it safe, and it must end in the same response.
                    val settled = store.awaitCompletion("key", "fingerprint", 5.seconds)
                    assertTrue(settled is IdempotencyReservation.Replay, settled.toString())
                    assertEquals("\"r1\"", (settled as IdempotencyReservation.Replay).response.headers["ETag"])
                }

                is IdempotencyReservation.Acquired -> {
                    fail("the in-flight reservation was handed out twice: ${result.token}")
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a retry that races the holder still replays instead of failing`() {
        repeat(25) { attempt ->
            val store = IdempotencyStore()
            val first = store.reserve("key-$attempt", "fingerprint") as IdempotencyReservation.Acquired
            val start = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val retry =
                    executor.submit<IdempotencyReservation?> {
                        start.await()
                        store.awaitCompletion("key-$attempt", "fingerprint", 5.seconds)
                    }
                start.countDown()
                store.complete(
                    "key-$attempt",
                    "fingerprint",
                    first.token,
                    StoredResponse(200, "replayed", mapOf("ETag" to "\"r1\""))
                )
                val settled = retry.get(10, TimeUnit.SECONDS)
                assertTrue(settled is IdempotencyReservation.Replay, "attempt $attempt settled with $settled")
                assertEquals("\"r1\"", (settled as IdempotencyReservation.Replay).response.headers["ETag"])
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `entries expire and bounded storage evicts oldest completed entry`() {
        val clock = TestClock(Instant.fromEpochSeconds(0))
        val expiring = IdempotencyStore(ttl = 1.seconds, clock = clock)
        val first = expiring.reserve("old", "fingerprint") as IdempotencyReservation.Acquired
        expiring.complete("old", "fingerprint", first.token, StoredResponse(200, "old"))
        clock.instant += 2.seconds
        assertEquals(null, expiring.get("old"))

        val bounded = IdempotencyStore(maxEntries = 1, ttl = 1.hours)
        val left = bounded.reserve("left", "fingerprint") as IdempotencyReservation.Acquired
        bounded.complete("left", "fingerprint", left.token, StoredResponse(200, "left"))
        val right = bounded.reserve("right", "fingerprint") as IdempotencyReservation.Acquired
        bounded.complete("right", "fingerprint", right.token, StoredResponse(200, "right"))
        assertEquals(1, bounded.size())
        assertEquals(null, bounded.get("left"))
    }

    @Test
    fun `an active reservation is never evicted at capacity`() {
        val store = IdempotencyStore(maxEntries = 2, ttl = 1.hours)
        val first = store.reserve("first", "fingerprint-a") as IdempotencyReservation.Acquired
        val second = store.reserve("second", "fingerprint-b") as IdempotencyReservation.Acquired

        val refused = store.reserve("third", "fingerprint-c")
        assertTrue(refused is IdempotencyReservation.Conflict, refused.toString())
        assertEquals(2, store.activeReservations())

        // Both holders can still publish; dropping either would lose the write.
        assertTrue(store.complete("first", "fingerprint-a", first.token, StoredResponse(200, "first")))
        assertTrue(store.complete("second", "fingerprint-b", second.token, StoredResponse(200, "second")))
        assertEquals(0, store.activeReservations())
        assertEquals(StoredResponse(200, "first"), store.get("first"))
        assertEquals(StoredResponse(200, "second"), store.get("second"))
    }

    @Test
    fun `an in flight retry replays once the holder publishes`() {
        val store = IdempotencyStore()
        val holder = CountDownLatch(1)
        val publishing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val acquired =
                executor.submit<IdempotencyReservation.Acquired> {
                    store.reserve("key", "fingerprint") as IdempotencyReservation.Acquired
                }
            val token = acquired.get(5, TimeUnit.SECONDS).token
            holder.countDown()
            val retry =
                executor.submit<IdempotencyReservation?> {
                    holder.await()
                    store.awaitCompletion("key", "fingerprint", 5.seconds)
                }
            Thread.sleep(50)
            publishing.countDown()
            assertTrue(store.complete("key", "fingerprint", token, StoredResponse(200, "replayed")))

            val settled = retry.get(5, TimeUnit.SECONDS)
            assertTrue(settled is IdempotencyReservation.Replay, settled.toString())
            assertEquals(200, (settled as IdempotencyReservation.Replay).response.status)
            assertEquals(0, store.activeReservations())
        } finally {
            publishing.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `an in flight retry can take over a released reservation`() {
        val store = IdempotencyStore()
        val holder = store.reserve("key", "fingerprint") as IdempotencyReservation.Acquired

        // Still held after the wait budget: the caller is told, not silently
        // handed a second reservation for the same write.
        assertTrue(store.awaitCompletion("key", "fingerprint", 50.milliseconds) is IdempotencyReservation.InFlight)
        assertTrue(store.release("key", "fingerprint", holder.token))

        // The key is gone, so a retry resolves to "reserve it again".
        assertEquals(null, store.awaitCompletion("key", "fingerprint", 50.milliseconds))
        val retaken = store.reserve("key", "fingerprint")
        assertTrue(retaken is IdempotencyReservation.Acquired, retaken.toString())
    }

    @Test
    fun `an in flight retry waits for the full budget before giving up`() {
        val store = IdempotencyStore()
        store.reserve("key", "fingerprint")
        val start = System.nanoTime()

        val settled = store.awaitCompletion("key", "fingerprint", 300.milliseconds)

        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue(settled is IdempotencyReservation.InFlight, settled.toString())
        assertTrue(elapsed >= 250, "the wait collapsed to ${elapsed}ms instead of the requested budget")
    }

    @Test
    fun `an expired in flight reservation is reclaimable`() {
        val clock = TestClock(Instant.fromEpochSeconds(0))
        val store = IdempotencyStore(ttl = 1.seconds, clock = clock)
        store.reserve("key", "fingerprint")
        clock.instant += 2.seconds

        assertEquals(null, store.awaitCompletion("key", "fingerprint", 0.seconds))
        assertTrue(store.reserve("key", "fingerprint") is IdempotencyReservation.Acquired)
    }

    @Test
    fun `a released reservation is not reported as replayable`() {
        val store = IdempotencyStore()
        val holder = store.reserve("key", "fingerprint") as IdempotencyReservation.Acquired
        store.complete("key", "fingerprint", holder.token, StoredResponse(200, "ok"))

        assertFalse(store.release("key", "fingerprint", holder.token))
        assertFalse(store.complete("key", "fingerprint", holder.token, StoredResponse(200, "again")))
        assertEquals(StoredResponse(200, "ok"), store.get("key"))
    }

    @Test
    fun `a dropped completion is logged and does not fail the committed write`() {
        val clock = TestClock(Instant.fromEpochSeconds(0))
        val store = IdempotencyStore(ttl = 1.seconds, clock = clock)
        val acquired = store.reserve("key", "fingerprint") as IdempotencyReservation.Acquired
        // The reservation expires before completion, which is what an eviction or a
        // very slow write looks like from complete()'s point of view.
        clock.instant += 2.seconds

        val warnings = mutableListOf<String>()
        val logger =
            object : TansekiLogger {
                override val level = LogLevel.WARN

                override fun log(
                    level: LogLevel,
                    message: String,
                    fields: Map<String, Any?>,
                    error: Throwable?
                ) {
                    if (level == LogLevel.WARN) warnings += message
                }
            }

        // Must return normally: the write is already committed, so a dropped
        // completion is a lost optimization, not a failed request.
        completeIdempotency(store, "key", "fingerprint", acquired.token, StoredResponse(200, "{}"), logger)

        assertEquals(1, warnings.size)
    }

    private class TestClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
