package gokorei.tanseki.service.daemon

import gokorei.tanseki.service.api.LatencyMetrics

/**
 * Request and indexing latency counters, kept together and reported as DTOs.
 *
 * Extracted from [OperationalMetrics]: the counters are a small, self-contained,
 * mutable concern with their own synchronisation, not part of the metrics state
 * machine.
 */
internal class LatencyRecorder {
    private data class State(
        var attempts: Long = 0,
        var completed: Long = 0,
        var errors: Long = 0,
        var totalLatencyMillis: Long = 0,
        var lastLatencyMillis: Long? = null,
        var maxLatencyMillis: Long = 0,
        var lastError: String? = null
    ) {
        fun toDto() =
            LatencyMetrics(
                attempts = attempts,
                completed = completed,
                errors = errors,
                totalLatencyMillis = totalLatencyMillis,
                lastLatencyMillis = lastLatencyMillis,
                maxLatencyMillis = maxLatencyMillis,
                lastError = lastError
            )
    }

    private val requests = State()
    private val indexing = State()

    @Synchronized
    fun recordRequest(durationNanos: Long, statusCode: Int, error: String?) {
        val latency = durationNanos.coerceAtLeast(0) / 1_000_000
        requests.attempts++
        requests.completed++
        requests.totalLatencyMillis += latency
        requests.lastLatencyMillis = latency
        requests.maxLatencyMillis = maxOf(requests.maxLatencyMillis, latency)
        if (statusCode >= 400) {
            requests.errors++
            // An error already shaped for a caller (http_*) is kept; anything else
            // is summarised rather than echoed, because it may carry user data.
            requests.lastError =
                error?.let { if (it.startsWith("http_")) it else "request_failed" } ?: "http_$statusCode"
        }
    }

    /** Records one indexing pass; [failed] folds into the indexing error count. */
    @Synchronized
    fun recordIndex(durationNanos: Long, failed: Boolean) {
        val latency = durationNanos.coerceAtLeast(0) / 1_000_000
        indexing.attempts++
        indexing.totalLatencyMillis += latency
        indexing.lastLatencyMillis = latency
        indexing.maxLatencyMillis = maxOf(indexing.maxLatencyMillis, latency)
        if (failed) {
            indexing.errors++
            indexing.lastError = "projection_failed"
        } else {
            indexing.completed++
        }
    }

    @Synchronized
    fun requests(): LatencyMetrics = requests.toDto()

    @Synchronized
    fun indexing(): LatencyMetrics = indexing.toDto()
}
