package gokorei.tanseki.service.daemon

import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.LogLevel
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Daemon composition settings for a single vault. */
data class DaemonConfig(
    val vault: VaultPath,
    val indexDir: Path,
    val socketPath: Path,
    val lockFile: Path = Path.of(vault.value).resolve(VaultDirs.DERIVED).resolve("daemon.lock"),
    val logLevel: LogLevel = LogLevel.INFO,
    /** Watcher debounce/coalesce window; configurable via `TANSEKI_WATCH_DEBOUNCE_MS`. */
    val watchDebounce: Duration = DEFAULT_WATCH_DEBOUNCE,
    val reconcileInterval: Duration = DEFAULT_RECONCILE_INTERVAL,
    val projectionStaleAfter: Duration = DEFAULT_PROJECTION_STALE_AFTER,
    val eventRetryLimit: Int = DEFAULT_EVENT_RETRY_LIMIT,
    val eventRetryBackoff: Duration = DEFAULT_EVENT_RETRY_BACKOFF,
    val collectorRestartDelay: Duration = DEFAULT_COLLECTOR_RESTART_DELAY
) {
    init {
        require(eventRetryLimit >= 0) { "eventRetryLimit must not be negative" }
        require(eventRetryBackoff >= Duration.ZERO) { "eventRetryBackoff must not be negative" }
        require(collectorRestartDelay >= Duration.ZERO) { "collectorRestartDelay must not be negative" }
        require(projectionStaleAfter > Duration.ZERO) { "projectionStaleAfter must be positive" }
    }

    companion object {
        val DEFAULT_WATCH_DEBOUNCE: Duration = 200.milliseconds
        val DEFAULT_RECONCILE_INTERVAL: Duration = 30.seconds
        val DEFAULT_PROJECTION_STALE_AFTER: Duration = 60.seconds
        const val DEFAULT_EVENT_RETRY_LIMIT: Int = 2
        val DEFAULT_EVENT_RETRY_BACKOFF: Duration = 50.milliseconds
        val DEFAULT_COLLECTOR_RESTART_DELAY: Duration = 500.milliseconds

        fun fromTansekiConfig(
            config: TansekiConfig,
            watchDebounce: Duration = DEFAULT_WATCH_DEBOUNCE,
            reconcileInterval: Duration = DEFAULT_RECONCILE_INTERVAL
        ): DaemonConfig =
            DaemonConfig(
                vault = VaultPath(config.path.toString()),
                indexDir = config.indexDir,
                socketPath = config.socketPath ?: config.path.resolve(VaultDirs.DERIVED).resolve("tanseki.sock"),
                logLevel = config.logLevel,
                watchDebounce = watchDebounce,
                reconcileInterval = reconcileInterval
            )

        /** Reads a variable under its `TANSEKI_*` name. */
        private fun env(
            env: (String) -> String?,
            name: String
        ): String? = env(name)

        /** Reads `TANSEKI_WATCH_DEBOUNCE_MS`, ignoring missing/non-positive values. */
        fun watchDebounceFromEnv(env: (String) -> String? = System::getenv): Duration =
            env(env, "TANSEKI_WATCH_DEBOUNCE_MS")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.milliseconds ?: DEFAULT_WATCH_DEBOUNCE

        fun reconcileIntervalFromEnv(env: (String) -> String? = System::getenv): Duration =
            env(env, "TANSEKI_RECONCILE_INTERVAL_MS")
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.milliseconds ?: DEFAULT_RECONCILE_INTERVAL
    }
}

/** Health snapshot: indexing lag and pending-set size are the key signals. */
data class Health(
    val pendingSize: Int,
    val indexingLag: Long,
    val indexedDocuments: Int,
    val timestamp: kotlin.time.Instant,
    val ready: Boolean = true,
    val status: String = "ok",
    val degraded: Boolean = false,
    val degradedReason: String? = null,
    val failureReasons: List<String> = emptyList(),
    val failedEvents: Long = 0,
    val projectionBacklog: Int = 0,
    val lastReconciledAt: kotlin.time.Instant? = null,
    val currentDocuments: Int = indexedDocuments,
    val currentEdges: Int = 0,
    val projectionAgeMillis: Long? = null,
    val projectionState: String = "unknown",
    val watcherState: String = "unknown",
    /** Evicted overlay entries still awaiting projection. */
    val unprojected: Int = 0,
    /** Outbox entries whose delivery has already failed once. */
    val stuckProjections: Int = 0,
    /** Outbox entries that could not be decoded. */
    val corruptProjections: Int = 0,
    /** Outbox entries whose delivery budget is spent; never delivered. */
    val deadLetteredProjections: Int = 0,
    /** Evicted overlay entries whose projection never landed within the TTL. */
    val expiredUnprojected: Int = 0
)
