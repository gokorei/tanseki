package gokorei.tanseki.service.daemon

import gokorei.tanseki.service.api.MetricsResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A bounded, TTL'd cache of metrics snapshots keyed by scope.
 *
 * Extracted from [OperationalMetrics]: caching a snapshot is a concern of its
 * own, and the class no longer carries the entry type, the eviction rule and the
 * time-to-live alongside everything else it does.
 */
internal class MetricsCache(
    private val ttl: Duration = 1.seconds,
    private val maxScopes: Int = 256
) {
    private data class Entry(val collectedAt: Instant, val snapshot: MetricsResponse)

    private val entries = LinkedHashMap<String, Entry>()

    /** The snapshot cached for [key] when still fresh, else null (dropping a stale one). */
    @Synchronized
    fun get(key: String, now: Instant): MetricsResponse? {
        val entry = entries[key] ?: return null
        if (now - entry.collectedAt < ttl) return entry.snapshot
        entries.remove(key)
        return null
    }

    /** Caches [snapshot] for [key], evicting the oldest scope once over [maxScopes]. */
    @Synchronized
    fun put(key: String, now: Instant, snapshot: MetricsResponse) {
        entries[key] = Entry(now, snapshot)
        while (entries.size > maxScopes) entries.remove(entries.entries.first().key)
    }

    @Synchronized
    fun clear() = entries.clear()
}
