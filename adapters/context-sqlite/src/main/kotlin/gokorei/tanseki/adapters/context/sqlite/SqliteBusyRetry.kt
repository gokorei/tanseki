package gokorei.tanseki.adapters.context.sqlite

import java.sql.SQLException

/**
 * Retries a statement or transaction that lost a write-lock race.
 *
 * SQLite reports `SQLITE_BUSY_SNAPSHOT` as soon as a deferred transaction
 * that has already read tries to write after another connection committed,
 * and no busy timeout covers that case. Every write path goes through here,
 * so a lost race is a retry rather than a failed request: a second process
 * writing the same database must not turn a caller's `write`, `delete`,
 * edge swap, transfer import, history import, blob write, or outbox update
 * into an error. The replay is safe because a busy error means the statement
 * never took effect, and every retried block either re-reads the state it
 * validates (so the compare-and-swap either commits or reports a conflict)
 * or is idempotent on its own terms.
 */
internal fun <T> withBusyRetry(block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (error: SQLException) {
            if (!isLocked(error) || attempt >= BUSY_RETRIES) throw error
            Thread.sleep(BUSY_BACKOFF_MILLIS shl attempt)
            attempt++
        }
    }
}

private fun isLocked(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        val message = current.message?.uppercase().orEmpty()
        if (LOCK_MARKERS.any { it in message }) return true
        current = current.cause
    }
    return false
}

private const val BUSY_RETRIES = 6
private const val BUSY_BACKOFF_MILLIS = 5L
private val LOCK_MARKERS = listOf("SQLITE_BUSY", "SQLITE_LOCKED", "DATABASE IS LOCKED")
