package gokorei.tanseki.adapters.pijul

import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RateLimitedException
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.TansekiException
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.PijulClient
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.PijulStatus
import gokorei.tanseki.core.ports.RetryPolicy
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [PijulClient] driven by the `pijul` binary via subprocess.
 *
 * - **Batched**: [record] writes the whole path set with a single `pijul record`;
 *   [logUnion] reads several documents' logs with a single `pijul log`;
 *   [diffMany] reads several paths' diffs with a single `pijul diff`. One
 *   spawn per path set, never one per path — spawning dominates latency and
 *   the semaphore below would serialise the fan-out anyway. [status] and [log]
 *   stay separate calls on purpose: they are different subcommands, so no
 *   single invocation serves both.
 * - **Reads**: `log --output-format json` / `diff --json`.
 * - **Non-interactive**: `pijul record` always prepares a change buffer and
 *   hands it to an editor, so the child process gets `EDITOR`/`VISUAL` pinned to a
 *   no-op (pijul prefers `VISUAL`, so both are set) plus `PAGER=cat`. No editor can
 *   ever be launched, whatever the host environment carries. See [record] for why
 *   `record --from-change` is not used instead.
 * - **Bounded**: a [Semaphore] caps concurrent subprocesses; every call has a
 *   timeout and reports a structured [TansekiException].
 *
 * Pijul has no `status` subcommand, so [status] is derived from `diff --json`
 * (clean when the working tree has no non-root operations).
 */
class PijulCliClient(
    private val binary: String = System.getenv("TANSEKI_PIJUL_BINARY") ?: "pijul",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxConcurrency: Int = 2,
    private val timeout: Duration = 30.seconds,
    private val maxOutputBytes: Int = 1_048_576,
    private val logger: TansekiLogger = TansekiLogger.Noop,
    private val clock: () -> Instant =
        {
            kotlin.time.Clock.System
                .now()
        },
    private val retryPolicy: RetryPolicy =
        RetryPolicy(
            maxAttempts = 3,
            baseDelay = 50.milliseconds,
            maxDelay = 2.seconds,
            jitter = 0.2
        )
) : PijulClient {
    init {
        require(maxConcurrency > 0) { "maxConcurrency must be > 0" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be > 0" }
    }

    private val permits = Semaphore(maxConcurrency)

    /**
     * Subprocesses spawned so far. A spawn dominates every call's latency and
     * the [Semaphore] serialises fan-out, so batching is measured in these:
     * one `log`/`diff`/`record` per path set, never one per path. Tests assert
     * the counts; operators cannot poll it (there is no metrics port yet).
     */
    private val invocations = AtomicLong()

    /** Subprocesses spawned so far; the fan-out gauge the batching exists to keep down. */
    fun subprocessInvocations(): Long = invocations.get()

    /**
     * The executor that drains a subprocess's stdout/stderr. One fixed pool is
     * shared by every invocation — creating and tearing down a two-thread pool per
     * subprocess was pure thread churn on a hot path — sized so every property
     * running process can drain both streams at once: a process whose stderr pipe
     * fills while nobody reads it would deadlock against its own timeout.
     */
    private val streamDrainer: ExecutorService =
        Executors.newFixedThreadPool(maxConcurrency * 2) { runnable ->
            Thread(runnable)
                .apply { isDaemon = true }
        }

    override fun init(vault: VaultPath) {
        if (File(vault.value, ".pijul").isDirectory) return
        logger.debug("pijul init", mapOf("vault" to vault.value))
        runCommand(vault, listOf("init"), retryable = false) { it }
    }

    override fun status(vault: VaultPath): PijulStatus =
        runCommand(vault, listOf("diff", "--json"), retryable = true, transform = PijulOutput::parseStatus)

    override fun log(vault: VaultPath, docId: DocId?, limit: Int): List<PijulPatch> {
        require(limit > 0) { "limit must be > 0" }
        val args = mutableListOf("log", "--output-format", "json", "--limit", limit.toString())
        docId?.let { args += listOf("--", "${it.value}.md") }
        return runCommand(vault, args, retryable = true, transform = PijulOutput::parseLog)
    }

    override fun diff(vault: VaultPath, path: String?): String {
        val args = mutableListOf("diff", "--json")
        path?.let { args += listOf("--", it) }
        return runCommand(vault, args, retryable = true, transform = { it })
    }

    /**
     * One `pijul log -- a.md b.md` for the whole set instead of one filtered
     * `log` per document: pijul accepts several paths after `--` and returns
     * their union, and each extra spawn would queue behind the [permits]
     * semaphore. Dedupes by hash, because a change touching two requested
     * paths (a rename records both ends) belongs to the union once, not once
     * per filter it matches.
     */
    override fun logUnion(vault: VaultPath, docIds: List<DocId>, limit: Int): List<PijulPatch> {
        require(limit > 0) { "limit must be > 0" }
        val distinct = docIds.distinct()
        if (distinct.isEmpty()) return emptyList()
        val args = mutableListOf("log", "--output-format", "json", "--limit", limit.toString())
        args += listOf("--")
        args += distinct.map { "${it.value}.md" }
        return runCommand(vault, args, retryable = true) { PijulOutput.parseLog(it).distinctBy { patch -> patch.hash } }
    }

    /**
     * One `pijul diff --json -- a b` for the whole set instead of one `diff`
     * per path: the unfiltered document is already keyed by path, so
     * [partitionDiff] slices it without another spawn. Paths with
     * no changes are absent from the map.
     */
    override fun diffMany(vault: VaultPath, paths: List<String>): Map<String, String> {
        val distinct = paths.distinct()
        if (distinct.isEmpty()) return emptyMap()
        val args = mutableListOf("diff", "--json", "--")
        args += distinct
        return runCommand(vault, args, retryable = true) { partitionDiff(it, distinct) }
    }

    /**
     * Records the whole path set in one `pijul record` invocation.
     *
     * **Why not `record --from-change <file>`.** `--no-prompt`, which earlier
     * revisions passed, does not exist in the pinned `pijul 1.0.0-beta.24` at
     * all: every record failed with `unexpected argument '--no-prompt' found`.
     * `--from-change` exists but is not a usable substitute for it, because the
     * file it takes must be in the *record* format, which `pijul diff` does not
     * emit. The two differ in exactly the parts a caller cannot reconstruct:
     *
     * - `diff` prefixes a `Root add` hunk, and numbers hunks from that; `record`
     *   needs contiguous hunks with a dependency section and sanakirja byte
     *   offsets (`up 2.1, new 9:37`, not `up 0.2, new 12:40`) that only pijul
     *   itself can compute. Handing `diff` output to `--from-change` fails with
     *   `Cannot parse change from <file>: Byte position 2 from this change
     *   missing`, and handing it a genuine record buffer crashes the binary.
     *
     * Pijul has no flag that suppresses the editor, so the non-interactive path
     * is the editor contract: the child process runs with `EDITOR` *and* `VISUAL`
     * pinned to a no-op command, so pijul records the change it prepared without
     * ever opening an editor (see [NO_EDITOR]).
     *
     * The message is passed through `--message` verbatim. pijul splits a
     * multi-line value, keeping only the first line as the message and moving the
     * rest into the change description, so the idempotency marker is handed to
     * `--description` instead of being appended to the message body.
     */
    override fun record(
        vault: VaultPath,
        message: String,
        author: String,
        paths: List<String>,
        operationId: String?
    ): PijulPatch {
        require(paths.isNotEmpty()) { "record requires at least one path" }
        val args = mutableListOf("record", "--message", message)
        operationId?.let { args += listOf("--description", "$OPERATION_MARKER$it") }
        args += listOf("--author", author, "--")
        args += paths
        return runCommand(vault, args, retryable = false) {
            recordPatch(it, author, message, operationId)
        }
    }

    override fun apply(vault: VaultPath, patch: String) {
        if (patch.isBlank() || containsControl(patch)) {
            throw InvalidInputException("pijul patch must be a single non-control argument")
        }
        runCommand(vault, listOf("apply", "--", patch), retryable = false) { it }
    }

    private fun containsControl(value: String): Boolean =
        value.any { it == '\n' || it == '\r' || it.isISOControl() }

    /**
     * `pijul record` prints `Hash: <hash>` on success; there is no JSON mode, so
     * parse the hash and use the caller-provided metadata.
     */
    private fun recordPatch(
        output: String,
        author: String,
        message: String,
        operationId: String?
    ): PijulPatch {
        val hash =
            output
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Hash:") }
                ?.removePrefix("Hash:")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw UnavailableException("pijul record produced no hash: ${output.trim()}")
        return PijulPatch(
            hash = RevisionId(hash),
            author = author,
            message = message,
            timestamp = clock(),
            operationId = operationId
        )
    }

    private fun <T> runCommand(
        vault: VaultPath,
        args: List<String>,
        retryable: Boolean,
        transform: (String) -> T
    ): T =
        runBlocking(dispatcher) {
            val rendered = args.joinToString(" ")
            logger.debug("pijul exec", mapOf("vault" to vault.value, "args" to rendered))
            permits.withPermit {
                try {
                    if (retryable) {
                        retry(retryPolicy) { transform(execute(vault, args)) }
                    } else {
                        transform(execute(vault, args))
                    }
                } catch (error: CancellationException) {
                    // Control flow, not a failure: rethrow without recording it as one.
                    throw error
                } catch (error: TansekiException) {
                    logger.error("pijul command failed", error, mapOf("args" to rendered))
                    throw error
                } catch (error: Throwable) {
                    // Everything else (a RuntimeException from our own parsing, an
                    // AssertionError) must be visible in the log too, not only the
                    // typed domain failures.
                    logger.error("pijul command failed unexpectedly", error, mapOf("args" to rendered))
                    throw error
                }
            }
        }

    private fun execute(vault: VaultPath, args: List<String>): String {
        val root = File(vault.value)
        if (!root.isDirectory) {
            throw UnavailableException("vault not found: ${vault.value}")
        }
        var process: Process? = null
        val descendants = linkedSetOf<ProcessHandle>()
        var interrupted = false
        try {
            val builder = ProcessBuilder(listOf(binary) + args).directory(root)
            // pijul resolves its change editor from VISUAL first and EDITOR second,
            // so both are pinned: a daemon started from an interactive shell would
            // otherwise inherit that shell's editor and block on it.
            builder.environment()["EDITOR"] = NO_EDITOR
            builder.environment()["VISUAL"] = NO_EDITOR
            builder.environment()["PAGER"] = "cat"
            val startedProcess = builder.start()
            process = startedProcess
            invocations.incrementAndGet()
            snapshotDescendants(startedProcess, descendants)
            val stdout = streamDrainer.submit<BoundedOutput> { capture(startedProcess.inputStream) }
            val stderr = streamDrainer.submit<BoundedOutput> { capture(startedProcess.errorStream) }
            awaitCompletion(startedProcess, args, descendants) { interrupted = true }
            return collectOutput(startedProcess, args, stdout, stderr)
        } catch (error: IOException) {
            throw UnavailableException("pijul executable is unavailable: $binary", error)
        } finally {
            process?.let {
                snapshotDescendants(it, descendants)
                if (it.isAlive || descendants.any { descendant -> descendant.isAlive }) {
                    terminate(it, descendants)
                }
            }
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Waits for [process] within the configured timeout, terminating it and its
     * descendants when it overruns. [onInterrupted] lets the caller restore the
     * interrupt flag once the streams are closed.
     */
    private fun awaitCompletion(
        process: Process,
        args: List<String>,
        descendants: MutableSet<ProcessHandle>,
        onInterrupted: () -> Unit
    ) {
        val finished =
            try {
                process.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                terminate(process, descendants)
                onInterrupted()
                throw UnavailableException("pijul ${args.firstOrNull()} was interrupted", error)
            }
        if (!finished) {
            snapshotDescendants(process, descendants)
            terminate(process, descendants)
            throw UnavailableException("pijul ${args.firstOrNull()} timed out after $timeout")
        }
    }

    /** Joins the captured streams and maps a non-zero exit to a typed failure. */
    private fun collectOutput(
        process: Process,
        args: List<String>,
        stdout: Future<BoundedOutput>,
        stderr: Future<BoundedOutput>
    ): String {
        val out = awaitOutput(stdout)
        val err = awaitOutput(stderr)
        if (out.truncated || err.truncated) {
            throw UnavailableException("pijul ${args.firstOrNull()} output exceeded $maxOutputBytes bytes")
        }
        if (process.exitValue() != 0) throw mapError(args, process.exitValue(), err.text)
        return out.text
    }

    private fun awaitOutput(future: Future<BoundedOutput>): BoundedOutput =
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UnavailableException("pijul output capture was interrupted", error)
        } catch (error: Exception) {
            throw UnavailableException("pijul output capture failed", error)
        }

    private fun capture(stream: InputStream): BoundedOutput {
        val buffer = ByteArray(8_192)
        val output = ByteArrayOutputStream(minOf(maxOutputBytes, buffer.size))
        var total = 0
        var truncated = false
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total <= maxOutputBytes) {
                    output.write(buffer, 0, read)
                } else {
                    truncated = true
                }
            }
        }
        return BoundedOutput(String(output.toByteArray(), StandardCharsets.UTF_8), truncated)
    }

    private fun snapshotDescendants(process: Process, descendants: MutableSet<ProcessHandle>) {
        runCatching { process.descendants().toList() }.getOrDefault(emptyList()).forEach(descendants::add)
    }

    private fun terminate(process: Process, descendants: Collection<ProcessHandle>) {
        descendants.forEach { runCatching { it.destroy() } }
        runCatching { process.destroy() }
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        descendants.reversed().forEach { if (it.isAlive) runCatching { it.destroyForcibly() } }
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean)

    private fun mapError(args: List<String>, exit: Int, stderr: String): TansekiException {
        val command = args.firstOrNull()
        return when (PijulFailures.classify(stderr)) {
            PijulFailures.Failure.NOT_FOUND -> NotFoundException()
            PijulFailures.Failure.CONFLICT -> conflictFailure(stderr)
            PijulFailures.Failure.RATE_LIMITED -> RateLimitedException()
            null -> UnavailableException("pijul $command failed (exit $exit): ${stderr.trim()}")
        }
    }

    private fun conflictFailure(stderr: String): ConflictException =
        ConflictException(null, stderr.ifBlank { "pijul conflict" })

    private companion object {
        const val OPERATION_MARKER = "tanseki-operation-id:"

        /**
         * Stand-in for the change editor pijul would otherwise launch. `true`
         * accepts the prepared change unchanged and exits 0, so the record
         * completes unattended; it is a POSIX utility present wherever pijul runs.
         */
        const val NO_EDITOR = "true"
    }
}

/**
 * Classifies a pijul subprocess failure from its stderr.
 *
 * The phrases the pinned pijul release emits live here, in one place, and are
 * pinned by a test: a wording change then shows up as a failing test rather than
 * silently reclassifying an error (a conflict becoming an unretryable failure, or
 * a real failure becoming a retryable one). Anything unrecognised returns null and
 * is reported as [UnavailableException] rather than guessed at.
 */
internal object PijulFailures {
    private val NOT_FOUND = listOf("not found", "no such", "does not exist")
    private val CONFLICT = listOf("conflict", "unresolved")
    private val RATE_LIMITED = listOf("rate limit", "too many requests")

    fun classify(stderr: String): Failure? {
        val lower = stderr.lowercase()
        return when {
            NOT_FOUND.any { it in lower } -> Failure.NOT_FOUND
            CONFLICT.any { it in lower } -> Failure.CONFLICT
            RATE_LIMITED.any { it in lower } -> Failure.RATE_LIMITED
            else -> null
        }
    }

    enum class Failure { NOT_FOUND, CONFLICT, RATE_LIMITED }
}
