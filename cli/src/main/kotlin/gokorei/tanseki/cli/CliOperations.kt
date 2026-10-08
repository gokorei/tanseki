package gokorei.tanseki.cli

import gokorei.tanseki.cli.transfer.DirectoryImporter
import gokorei.tanseki.cli.transfer.ModeTransfer
import gokorei.tanseki.cli.transfer.TransferReport
import gokorei.tanseki.composition.Composition
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.IpcClient
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.FullRebuildReport
import gokorei.tanseki.core.application.FullRebuilder
import gokorei.tanseki.core.application.Indexer
import gokorei.tanseki.core.application.RebuildProgress
import gokorei.tanseki.core.application.ReconcileReport
import gokorei.tanseki.core.application.Reconciler
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.VaultDirs
import gokorei.tanseki.core.ports.LogLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Operator operations backing the CLI commands. Kept separate from the Clikt
 * command classes so they are testable without argument parsing.
 */
object CliOperations {
    fun init(vault: Path): String {
        Files.createDirectories(VaultDirs.derived(vault))
        return "initialized vault at ${vault.toAbsolutePath()}"
    }

    /** Reconcile the Lookup from the Context Store; returns the delta counts. */
    fun reindex(config: TansekiConfig): ReconcileReport =
        Compositions.open(config).use { composition ->
            val reconciler =
                Reconciler(
                    composition.store,
                    composition.lookup,
                    indexerOf(composition)
                )
            reconciler.reconcile()
        }

    fun rebuild(
        config: TansekiConfig,
        onProgress: (RebuildProgress) -> Unit = {}
    ): FullRebuildReport =
        Compositions.open(config).use { composition ->
            FullRebuilder(
                composition.store,
                composition.lookup,
                indexerOf(composition)
            ).rebuild(onProgress)
        }

    /** The one Indexer wiring both reindex and rebuild share. */
    private fun indexerOf(composition: Composition): Indexer =
        Indexer(
            store = composition.store,
            lookup = composition.lookup,
            embedder = composition.embedder,
            vectorWriter = composition.vectorWriter
        )

    /**
     * Reports indexing lag and pending size. Queries a running daemon over IPC
     * when a socket is configured; otherwise reports the local composition.
     */
    fun status(config: TansekiConfig): String {
        val socket = config.socketPath.takeIf { config.profile == Profile.VAULT }
        if (socket != null && Files.exists(socket)) {
            val response = runCatching { IpcClient.request(socket, """{"op":"health"}""") }.getOrNull()
            val parsed = statusFromDaemonReply(response)
            if (parsed != null) return parsed
        }
        return Compositions
            .open(config)
            .use { composition ->
                val documents = runCatching { composition.store.list(null).size }.getOrDefault(0)
                // Read the real outbox rather than asserting a clean bill of
                // health: an operator with a full backlog must see it here.
                val backlog = runCatching { composition.store.projectionOperations()?.backlog() }.getOrNull()
                val pending = backlog?.pending ?: 0
                val stuck = backlog?.stuck ?: 0
                "local documents=$documents pendingSize=$pending stuck=$stuck indexingLag=0"
            }
    }

    /**
     * Formats a daemon health reply, or null when the reply is missing or not a
     * shape this understands. Parsed inside `runCatching`: a non-JSON or
     * unexpected-shaped reply must fall through to local status, not crash
     * `tanseki status`.
     */
    internal fun statusFromDaemonReply(response: String?): String? =
        response?.let { runCatching { parseDaemonHealth(it) }.getOrNull() }

    private fun parseDaemonHealth(response: String): String {
        val health = Json.parseToJsonElement(response).jsonObject
        val pending = health["pendingSize"]?.jsonPrimitive?.content ?: "0"
        val lag = health["indexingLag"]?.jsonPrimitive?.content ?: "0"
        return "daemon pendingSize=$pending indexingLag=$lag"
    }

    /** Copy a vault into a SQLite library. */
    fun export(source: TansekiConfig, targetLibrary: Path): TransferReport =
        Compositions.open(source).use { from ->
            Compositions.open(config(targetLibrary, Profile.LIBRARY)).use { to ->
                ModeTransfer().copy(from.store, to.store)
            }
        }

    /** Copy a SQLite library back into a vault. */
    fun importInto(library: Path, target: TansekiConfig): TransferReport {
        require(target.profile != Profile.DIRECTORY) {
            "directory profile is a read-only source and cannot be an import target"
        }
        return Compositions.open(config(library, Profile.LIBRARY)).use { from ->
            Compositions.open(target).use { to ->
                ModeTransfer().copy(from.store, to.store)
            }
        }
    }

    /**
     * Imports a plain directory of Markdown into a Tanseki store.
     *
     * The source is read-only and may hold Obsidian-legal filenames [DocId]
     * rejects (surrounding whitespace, embedded controls, non-lowercase
     * `.md`); those are stored under the [FilenameSanitizer][gokorei.tanseki.cli.transfer.FilenameSanitizer]
     * policy and every mapping surfaces in the returned [TransferReport].
     */
    fun importDirectory(sourceDir: Path, target: TansekiConfig): TransferReport =
        Compositions.open(target).use { to ->
            DirectoryImporter().copy(sourceDir, to.store)
        }

    /**
     * Imports a plain directory of Markdown into a SQLite library.
     *
     * The source is read-only and needs no Pijul repository: only `*.md` files
     * are read, and the directory is byte-identical afterwards. Filenames
     * [DocId] rejects (surrounding whitespace, embedded controls, non-lowercase
     * `.md`) are stored under the `FilenameSanitizer` policy, with every
     * mapping in the returned [TransferReport].
     *
     * With [dryRun] the run computes the same report and writes nothing: a
     * fresh target leaves no database behind, and an existing target keeps its
     * documents and their revisions. The lookup index is opened in a temporary
     * directory so even derived state beside the target is untouched.
     */
    fun importVault(
        sourceDir: Path,
        targetLibrary: Path,
        dryRun: Boolean = false,
        collection: Collection = Collection("vault")
    ): TransferReport =
        if (dryRun) {
            importVaultDryRun(sourceDir, targetLibrary, collection)
        } else {
            Compositions.open(config(targetLibrary, Profile.LIBRARY)).use { to ->
                DirectoryImporter(collection = collection).copy(sourceDir, to.store)
            }
        }

    /**
     * The dry-run half of [importVault]: the same report with no writes. A
     * fresh target leaves no database behind, and an existing target keeps its
     * documents and their revisions. The lookup index is opened in a temporary
     * directory so even derived state beside the target is untouched.
     */
    private fun importVaultDryRun(
        sourceDir: Path,
        targetLibrary: Path,
        collection: Collection
    ): TransferReport {
        val scratch = Files.createTempDirectory("tanseki-import-dryrun")
        try {
            if (Files.exists(targetLibrary)) return dryRunOnLibrary(sourceDir, targetLibrary, scratch, collection)
            return dryRunOnEmpty(sourceDir, scratch, collection)
        } finally {
            runCatching { scratch.toFile().deleteRecursively() }
        }
    }

    /**
     * Dry run against an existing library: its documents are read through a
     * scratch index so the run touches neither the library nor the derived
     * state beside it. SQLite reads do not modify the database file.
     */
    private fun dryRunOnLibrary(
        sourceDir: Path,
        targetLibrary: Path,
        scratch: Path,
        collection: Collection
    ): TransferReport {
        val config =
            TansekiConfig(
                profile = Profile.LIBRARY,
                path = targetLibrary,
                indexDir = scratch.resolve("index"),
                logLevel = LogLevel.INFO
            )
        Compositions.open(config).use { existing ->
            return DirectoryImporter(collection = collection).copy(sourceDir, existing.store, dryRun = true)
        }
    }

    /**
     * Dry run with no library to read: an empty scratch library stands in for
     * the database the real run would create, and is deleted with the rest of
     * the scratch directory.
     */
    private fun dryRunOnEmpty(sourceDir: Path, scratch: Path, collection: Collection): TransferReport {
        val config =
            TansekiConfig(
                profile = Profile.LIBRARY,
                path = scratch.resolve("library.db"),
                indexDir = scratch.resolve("index"),
                logLevel = LogLevel.INFO
            )
        Compositions.open(config).use { empty ->
            return DirectoryImporter(collection = collection).copy(sourceDir, empty.store, dryRun = true)
        }
    }

    private fun config(path: Path, profile: Profile): TansekiConfig =
        TansekiConfig(
            profile = profile,
            path = path,
            indexDir = indexDirFor(path),
            logLevel = LogLevel.INFO
        )

    private fun indexDirFor(path: Path): Path = path.resolveSibling("${path.fileName}.index")
}
