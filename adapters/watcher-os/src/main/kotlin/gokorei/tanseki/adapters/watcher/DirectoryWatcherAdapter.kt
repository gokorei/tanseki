package gokorei.tanseki.adapters.watcher

import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.FsEvent
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.Watcher
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryWatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun interface RawWatcherFactory {
    fun watch(root: VaultPath, onEvent: (RawFsEvent) -> Unit): AutoCloseable
}

data class RawFsEvent(
    val path: String,
    val kind: RawChangeKind,
    val occurredAt: Instant = Clock.System.now()
)

enum class RawChangeKind { CREATE, MODIFY, DELETE, OVERFLOW }

class DirectoryWatcherAdapter(
    private val factory: RawWatcherFactory = DirectoryWatcherFactory(),
    private val debounce: Duration = 200.milliseconds,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val logger: TansekiLogger = TansekiLogger.Noop,
    private val suppressionTtl: Duration = 2.seconds
) : Watcher, AutoCloseable {
    private val suppressed = ConcurrentHashMap<String, Long>()
    private val active = CopyOnWriteArrayList<AutoCloseable>()

    init {
        require(!debounce.isNegative()) { "debounce must not be negative" }
        require(suppressionTtl.isPositive()) { "suppressionTtl must be positive" }
    }

    /**
     * Suppresses the next event for [path]. The key is recorded in every form it
     * can be reported in — the path as given (vault-relative, which is what a
     * daemon hands over for a self-write) and its absolute form — so suppression
     * works whether the vault is configured absolutely or relative to the
     * working directory.
     */
    fun suppress(path: String) {
        val expiresAt = Clock.System.now().toEpochMilliseconds() + suppressionTtl.inWholeMilliseconds
        suppressionKeys(path).forEach { key -> suppressed[key] = expiresAt }
    }

    fun unsuppress(path: String) {
        suppressionKeys(path).forEach { key -> suppressed.remove(key) }
    }

    private fun suppressionKeys(path: String): Set<String> =
        buildSet {
            val rendered = path.replace('\\', '/')
            add(rendered)
            add(normalizePath(path))
        }

    override fun events(vault: VaultPath): Flow<FsEvent> =
        callbackFlow {
            val root = Path.of(vault.value).toAbsolutePath().normalize()
            val lock = Any()
            val pending = LinkedHashMap<String, RawFsEvent>()
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            var flushJob: Job? = null

            fun flush() {
                val drained =
                    synchronized(lock) {
                        if (pending.isEmpty()) {
                            flushJob = null
                            return@synchronized emptyMap()
                        }
                        LinkedHashMap(pending).also {
                            pending.clear()
                            flushJob = null
                        }
                    }
                drained.values.forEach { event -> trySend(event.toFsEvent()) }
            }

            val handle =
                factory.watch(vault) { raw ->
                    if (raw.kind == RawChangeKind.OVERFLOW) {
                        synchronized(lock) {
                            pending.clear()
                            flushJob?.cancel()
                            flushJob = null
                        }
                        trySend(FsEvent.Overflow(raw.occurredAt))
                        return@watch
                    }
                    val path = normalizePath(raw.path, root)
                    if (!Path.of(path).startsWith(root)) return@watch
                    if (consumeSuppression(path, root)) {
                        logger.debug("suppressed self-write", mapOf("path" to path))
                        return@watch
                    }
                    synchronized(lock) {
                        logger.debug("fs event", mapOf("path" to path, "kind" to raw.kind.name))
                        pending[path] = raw.copy(path = path)
                        if (flushJob?.isActive != true) {
                            flushJob =
                                scope.launch {
                                    delay(debounce)
                                    flush()
                                }
                        }
                    }
                }
            active += handle

            awaitClose {
                handle.close()
                synchronized(lock) {
                    flushJob?.cancel()
                    flushJob = null
                }
                scope.cancel()
                active.remove(handle)
            }
        }

    override fun close() {
        active.forEach { runCatching { it.close() } }
        active.clear()
        suppressed.clear()
    }

    private fun consumeSuppression(path: String, root: Path): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        val keys =
            buildSet {
                addAll(suppressionKeys(path))
                val candidate = Path.of(path)
                if (candidate.startsWith(root)) {
                    val relative = root.relativize(candidate)
                    val rendered = relative.toString().replace('\\', '/')
                    if (rendered.isNotEmpty()) add(rendered)
                }
            }
        synchronized(suppressed) {
            suppressed.entries.removeIf { it.value <= now }
            return keys.any { suppressed.remove(it) != null }
        }
    }

    private fun normalizePath(path: String, root: Path? = null): String {
        val candidate = Path.of(path)
        val resolved =
            if (candidate.isAbsolute) {
                candidate
            } else {
                (root ?: Path.of("").toAbsolutePath()).resolve(candidate)
            }
        return resolved
            .toAbsolutePath()
            .normalize()
            .toString()
            .replace('\\', '/')
    }

    private fun RawFsEvent.toFsEvent(): FsEvent =
        when (kind) {
            RawChangeKind.CREATE -> FsEvent.Created(path, occurredAt)
            RawChangeKind.MODIFY -> FsEvent.Modified(path, occurredAt)
            RawChangeKind.DELETE -> FsEvent.Deleted(path, occurredAt)
            RawChangeKind.OVERFLOW -> FsEvent.Overflow(occurredAt)
        }
}

class DirectoryWatcherFactory : RawWatcherFactory {
    override fun watch(root: VaultPath, onEvent: (RawFsEvent) -> Unit): AutoCloseable {
        val watcher =
            DirectoryWatcher
                .builder()
                .path(Path.of(root.value))
                .listener { event -> onEvent(event.toRaw()) }
                .build()
        watcher.watchAsync()
        return AutoCloseable { watcher.close() }
    }

    private fun DirectoryChangeEvent.toRaw(): RawFsEvent =
        RawFsEvent(
            path = path().toString(),
            kind =
                when (eventType()) {
                    DirectoryChangeEvent.EventType.CREATE -> RawChangeKind.CREATE
                    DirectoryChangeEvent.EventType.MODIFY -> RawChangeKind.MODIFY
                    DirectoryChangeEvent.EventType.DELETE -> RawChangeKind.DELETE
                    DirectoryChangeEvent.EventType.OVERFLOW -> RawChangeKind.OVERFLOW
                },
            occurredAt = Clock.System.now()
        )
}
