package gokorei.tanseki.service.api

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

data class StoredResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

sealed interface IdempotencyReservation {
    data class Acquired(val token: String) : IdempotencyReservation

    data class Replay(val response: StoredResponse) : IdempotencyReservation

    data class Conflict(val reason: String) : IdempotencyReservation

    /**
     * The key is held by another in-flight request. Callers should
     * [IdempotencyStore.awaitCompletion] so the retry replays the original
     * outcome instead of failing or double-writing.
     */
    data class InFlight(val holderToken: String) : IdempotencyReservation
}

class IdempotencyStore(
    private val maxEntries: Int = 1_000,
    private val ttl: Duration = 24.hours,
    private val clock: Clock = Clock.System
) {
    private data class Entry(
        val fingerprint: String,
        val token: String,
        val response: StoredResponse?,
        val storedAt: Long
    ) {
        val active: Boolean get() = response == null
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val capacityLock = ReentrantLock()
    private val published = capacityLock.newCondition()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttl > Duration.ZERO) { "ttl must be positive" }
    }

    fun reserve(key: String, fingerprint: String): IdempotencyReservation {
        capacityLock.lock()
        try {
            val now = clock.now().toEpochMilliseconds()
            purgeExpired(now)
            val held = entries[key]
            if (held != null && !expired(held, now)) return classify(held, fingerprint)
            if (entries.size >= maxEntries && !evictOneCompleted()) {
                return IdempotencyReservation.Conflict("Idempotency store is at capacity; retry the request")
            }
            var reservation: IdempotencyReservation =
                IdempotencyReservation.Conflict("Idempotency-Key could not be reserved")
            entries.compute(key) { _, current ->
                if (current == null || expired(current, now)) {
                    val token = UUID.randomUUID().toString()
                    reservation = IdempotencyReservation.Acquired(token)
                    Entry(fingerprint, token, response = null, storedAt = now)
                } else {
                    reservation = classify(current, fingerprint)
                    current
                }
            }
            return reservation
        } finally {
            capacityLock.unlock()
        }
    }

    private fun classify(existing: Entry, fingerprint: String): IdempotencyReservation =
        when {
            existing.fingerprint != fingerprint -> {
                IdempotencyReservation.Conflict("Idempotency-Key is already bound to a different request")
            }

            existing.response != null -> {
                IdempotencyReservation.Replay(existing.response)
            }

            else -> {
                IdempotencyReservation.InFlight(existing.token)
            }
        }

    /**
     * Waits for the in-flight holder to publish its response so a retry replays
     * the original outcome. Returns `null` when the holder released the key (or
     * it expired) within [timeout], meaning the caller may reserve it again.
     */
    fun awaitCompletion(
        key: String,
        fingerprint: String,
        timeout: Duration
    ): IdempotencyReservation? {
        require(!timeout.isNegative()) { "timeout must not be negative" }
        if (timeout == Duration.ZERO) return state(key, fingerprint)
        capacityLock.lock()
        try {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds.coerceAtLeast(1)
            var settled: IdempotencyReservation? = null
            var waiting = true
            while (waiting) {
                val now = clock.now().toEpochMilliseconds()
                val existing = entries[key]
                val remaining = deadline - System.nanoTime()
                when {
                    existing == null || expired(existing, now) -> {
                        waiting = false
                    }

                    existing.fingerprint != fingerprint -> {
                        settled = classify(existing, fingerprint)
                        waiting = false
                    }

                    existing.response != null -> {
                        settled = IdempotencyReservation.Replay(existing.response)
                        waiting = false
                    }

                    remaining <= 0L -> {
                        settled = IdempotencyReservation.InFlight(existing.token)
                        waiting = false
                    }

                    else -> {
                        // awaitNanos returns the *remaining* time, so the deadline
                        // is kept absolute: re-assigning it to a relative budget
                        // would shrink the wait on every poll.
                        published.awaitNanos(remaining.coerceAtMost(POLL_INTERVAL_NANOS))
                    }
                }
            }
            return settled
        } finally {
            capacityLock.unlock()
        }
    }

    private fun state(key: String, fingerprint: String): IdempotencyReservation? =
        capacityLock.withLock {
            val now = clock.now().toEpochMilliseconds()
            val existing = entries[key]
            if (existing == null || expired(existing, now)) null else classify(existing, fingerprint)
        }

    fun complete(
        key: String,
        fingerprint: String,
        token: String,
        response: StoredResponse
    ): Boolean =
        capacityLock.withLock {
            val completed =
                entries
                    .compute(key) { _, existing ->
                        if (existing == null || expired(existing, clock.now().toEpochMilliseconds())) {
                            null
                        } else if (
                            existing.fingerprint == fingerprint &&
                            existing.token == token &&
                            existing.response == null
                        ) {
                            existing.copy(
                                response = response,
                                storedAt = clock.now().toEpochMilliseconds()
                            )
                        } else {
                            existing
                        }
                    }?.response == response
            if (completed) published.signalAll()
            completed
        }

    fun release(key: String, fingerprint: String, token: String): Boolean =
        capacityLock.withLock {
            val existing = entries[key] ?: return@withLock false
            if (
                existing.fingerprint != fingerprint ||
                existing.token != token ||
                existing.response != null
            ) {
                return@withLock false
            }
            val released = entries.remove(key, existing)
            if (released) published.signalAll()
            released
        }

    fun get(key: String): StoredResponse? {
        val entry = entries[key] ?: return null
        if (expired(entry, clock.now().toEpochMilliseconds())) {
            entries.remove(key, entry)
            return null
        }
        return entry.response
    }

    fun put(key: String, response: StoredResponse) {
        capacityLock.withLock {
            val now = clock.now().toEpochMilliseconds()
            purgeExpired(now)
            check(entries.size < maxEntries || evictOneCompleted(exclude = key)) {
                "Idempotency store is at capacity"
            }
            entries[key] = Entry("legacy", "legacy", response, now)
        }
    }

    fun size(): Int = entries.size

    /** Reservations that have been taken but have not published a response yet. */
    fun activeReservations(): Int = entries.values.count { it.active }

    private companion object {
        /** Upper bound on one condition wait, so clock-driven TTLs stay observable. */
        const val POLL_INTERVAL_NANOS = 50_000_000L
    }

    private fun expired(entry: Entry, now: Long): Boolean = now - entry.storedAt > ttl.inWholeMilliseconds

    private fun purgeExpired(now: Long) {
        entries.entries.removeIf { expired(it.value, now) }
    }

    /**
     * Evicts the oldest **completed** entry only. An in-flight reservation is
     * never dropped: its holder could no longer publish (or release) the
     * response, which would lose the write and strand the caller's retry.
     */
    private fun evictOneCompleted(exclude: String? = null): Boolean {
        val victim =
            entries
                .entries
                .filter { !it.value.active && it.key != exclude }
                .minByOrNull { it.value.storedAt }
                ?.key ?: return false
        return entries.remove(victim) != null
    }
}
