package gokorei.tanseki.service.daemon

import gokorei.tanseki.adapters.watcher.DirectoryWatcherAdapter
import gokorei.tanseki.composition.Composition
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.ChangeFeed
import gokorei.tanseki.core.application.Indexer
import gokorei.tanseki.core.application.PendingOverlay
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.application.Reconciler
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.service.api.MetricsRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

interface DaemonRuntime : AutoCloseable {
    val metrics: MetricsRecorder

    fun start()
}

class DaemonHandles(
    val daemon: DaemonRuntime,
    val facade: QueryFacade,
    val metrics: MetricsRecorder,
    val profile: Profile,
    private val composition: Composition,
    /**
     * Shared by the [Indexer] that publishes into it and the HTTP layer that
     * serves it. Two instances would mean a stream that never sees the writes
     * that just committed, which is the failure this whole feed exists to
     * prevent.
     */
    val changeFeed: ChangeFeed
) : AutoCloseable {
    internal val store: ContextStore get() = composition.store
    internal val lookup: Lookup get() = composition.lookup

    override fun close() {
        try {
            daemon.close()
        } finally {
            composition.close()
        }
    }
}

/**
 * Reconciler for the profiles with no filesystem watcher.
 *
 * Library and server keep their documents in a database, so nothing tells them a
 * document changed. Reconciling only at startup meant a projection that failed
 * once — a transient dependency blip — stayed pending until the next client write
 * or a restart, which is precisely when nobody is looking. This drains the outbox
 * on a timer so recovery does not depend on traffic.
 *
 * The interval is the same [DaemonConfig.reconcileInterval] the vault profile
 * uses, and `<= 0` disables the loop, so a deployment can opt out.
 */
private class ReconcilingDaemon(
    private val reconciler: Reconciler,
    private val operationalMetrics: OperationalMetrics,
    private val reconcileInterval: Duration
) : DaemonRuntime {
    override val metrics: MetricsRecorder
        get() = operationalMetrics

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Owned here so close() can cancel the whole tree: no one else holds it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recoveryJob: Job? = null

    override fun start() {
        check(running.compareAndSet(false, true)) { "daemon already started" }
        operationalMetrics.markWatcherNotApplicable()
        try {
            val report = reconciler.reconcile()
            operationalMetrics.recordReconcile(
                "startup",
                report,
                report.readFailures == 0 && report.projectionFailed == 0
            )
        } catch (error: Exception) {
            operationalMetrics.recordReconcileFailure("startup", error)
            running.set(false)
            throw error
        }
        startRecoveryLoop()
    }

    private fun startRecoveryLoop() {
        if (reconcileInterval <= Duration.ZERO) return
        recoveryJob =
            scope.launch {
                while (isActive) {
                    delay(reconcileInterval)
                    if (!isActive) break
                    try {
                        val report = reconciler.reconcile()
                        operationalMetrics.recordReconcile(
                            "periodic",
                            report,
                            report.readFailures == 0 && report.projectionFailed == 0
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // A failed recovery tick must not end the loop. The next
                        // tick is the retry, and the backoff in ProjectionWorker
                        // keeps the outbox from spinning against the same
                        // dependency in between.
                        operationalMetrics.recordReconcileFailure("periodic", error)
                    }
                }
            }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        recoveryJob?.cancel()
        // The scope owns the recovery loop; cancelling it stops the whole tree
        // this daemon launched, which the old code left to nobody.
        scope.cancel()
        recoveryJob = null
    }
}

object DaemonFactory {
    private val systemClock =
        Clock {
            kotlin.time.Clock.System
                .now()
        }

    fun open(
        config: TansekiConfig,
        watchDebounce: Duration = DaemonConfig.DEFAULT_WATCH_DEBOUNCE,
        logger: TansekiLogger = TansekiLogger.Noop,
        reconcileInterval: Duration = DaemonConfig.reconcileIntervalFromEnv()
    ): DaemonHandles {
        val daemonConfig = DaemonConfig.fromTansekiConfig(config, watchDebounce, reconcileInterval)
        // The vault is single-writer: take the advisory lock *before* any store,
        // index, or watcher is opened, so a second writer never initialises
        // against (or migrates) a vault another process owns.
        val vaultLock =
            when (config.profile) {
                Profile.VAULT -> VaultLock(daemonConfig.lockFile).acquire()
                else -> null
            }
        val composition =
            try {
                Compositions.open(config, logger)
            } catch (error: Exception) {
                vaultLock?.release()
                throw error
            }
        return try {
            val overlay = PendingOverlay()
            val changeFeed = ChangeFeed()
            val operationalMetrics =
                OperationalMetrics(
                    store = composition.store,
                    overlay = overlay,
                    config = daemonConfig,
                    lookup = composition.lookup
                )
            val indexer =
                Indexer(
                    store = composition.store,
                    lookup = composition.lookup,
                    embedder = composition.embedder,
                    vectorWriter = composition.vectorWriter,
                    logger = logger,
                    observer = operationalMetrics,
                    changeFeed = changeFeed
                )
            val reconciler = Reconciler(composition.store, composition.lookup, indexer, overlay)
            val facade =
                QueryFacade(
                    store = composition.store,
                    lookup = composition.lookup,
                    clock = systemClock,
                    logger = logger,
                    embedder = composition.embedder,
                    vectorWriter = composition.vectorWriter,
                    overlay = overlay,
                    indexObserver = operationalMetrics
                )
            val daemon =
                when (config.profile) {
                    Profile.VAULT -> {
                        openVault(
                            composition,
                            daemonConfig,
                            vaultLock,
                            indexer,
                            reconciler,
                            overlay,
                            operationalMetrics,
                            logger
                        )
                    }

                    Profile.LIBRARY, Profile.SERVER, Profile.DIRECTORY -> {
                        ReconcilingDaemon(
                            reconciler = reconciler,
                            operationalMetrics = operationalMetrics,
                            reconcileInterval = daemonConfig.reconcileInterval
                        )
                    }
                }
            DaemonHandles(daemon, facade, operationalMetrics, config.profile, composition, changeFeed)
        } catch (error: Exception) {
            composition.close()
            vaultLock?.release()
            throw error
        }
    }

    private fun openVault(
        composition: Composition,
        daemonConfig: DaemonConfig,
        lock: VaultLock?,
        indexer: Indexer,
        reconciler: Reconciler,
        overlay: PendingOverlay,
        operationalMetrics: OperationalMetrics,
        logger: TansekiLogger
    ): VaultDaemon {
        val watcher = DirectoryWatcherAdapter(debounce = daemonConfig.watchDebounce, logger = logger)
        return VaultDaemon(
            config = daemonConfig,
            store = composition.store,
            lookup = composition.lookup,
            watcher = watcher,
            indexer = indexer,
            reconciler = reconciler,
            overlay = overlay,
            metricsState = operationalMetrics,
            onSelfWrite = watcher::suppress,
            lock = lock,
            logger = logger
        )
    }
}
