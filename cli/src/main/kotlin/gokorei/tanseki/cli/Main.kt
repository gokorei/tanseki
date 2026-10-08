package gokorei.tanseki.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import gokorei.tanseki.cli.transfer.TransferReport
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.VaultDirs
import java.nio.file.Path

/** Shared `--path` / `--profile` / `--index-dir` options. */
abstract class StoreCommand : CliktCommand() {
    private val environment by lazy { TansekiConfig.fromEnv() }
    private val path by
        option("--path", "-p", help = "Vault or database path")
            .path()
            .default(environment.path)
    private val profileName by
        option("--profile", help = "vault | library | server | directory")
            .default(environment.profile.name.lowercase())
    private val indexDir by option("--index-dir", help = "Lookup index directory").path()

    protected fun config(): TansekiConfig =
        environment.copy(
            profile = TansekiConfig.profileFromName(profileName),
            path = path,
            indexDir =
                indexDir
                    ?: if (path == environment.path) environment.indexDir else VaultDirs.derived(path).resolve("index")
        )
}

class InitCommand : CliktCommand() {
    private val path by option("--path", "-p", help = "Vault path").path().default(Path.of("."))

    override fun run() {
        echo(CliOperations.init(path))
    }
}

class IndexCommand : ReconcileCommand("indexed")

class ReindexCommand : ReconcileCommand("reindexed")

/**
 * The reconcile command; `index` and `reindex` are the same operation and differ
 * only in the verb echoed.
 */
abstract class ReconcileCommand(private val verb: String) : StoreCommand() {
    override fun run() {
        val report = CliOperations.reindex(config())
        echo(
            "$verb added=${report.added} updated=${report.updated} removed=${report.removed} unchanged=${report.unchanged}"
        )
    }
}

class RebuildCommand : StoreCommand() {
    override fun run() {
        val report =
            CliOperations.rebuild(config()) { progress ->
                val id = progress.documentId?.let { " $it" }.orEmpty()
                echo("rebuild ${progress.phase.name.lowercase()} ${progress.processed}/${progress.total}$id")
            }
        val summary =
            "rebuilt documents=${report.totalDocuments} indexed=${report.indexedDocuments} " +
                "readFailures=${report.readFailures} indexFailures=${report.indexFailures} " +
                "outboxCompleted=${report.projectionCompleted} outboxFailed=${report.projectionFailed}"
        if (!report.successful) {
            echo("rebuild failed $summary failures=${report.failures.joinToString("; ")}")
            error("full lookup rebuild failed")
        }
        echo(summary)
    }
}

class StatusCommand : StoreCommand() {
    override fun run() {
        echo(CliOperations.status(config()))
    }
}

class ExportCommand : StoreCommand() {
    private val to by option("--to", help = "Target SQLite library path").path().required()

    override fun run() {
        echo(transferSummary("exported", CliOperations.export(config(), to)))
    }
}

class ImportCommand : StoreCommand() {
    private val from by option("--from", help = "Source SQLite library path").path().required()

    override fun run() {
        echo(transferSummary("imported", CliOperations.importInto(from, config())))
    }
}

/**
 * One-shot import of a plain Markdown directory (for example an Obsidian
 * vault) into a SQLite library. Deliberately outside [StoreCommand]: the
 * source is a foreign directory, not a Tanseki profile, so `--profile` and
 * `--path` do not apply, and there is no Pijul prerequisite.
 */
class ImportVaultCommand : CliktCommand(name = "import-vault") {
    private val from by option("--from", help = "Source vault directory").path().required()
    private val to by option("--to", help = "Target SQLite library path").path().required()
    private val dryRun by option("--dry-run", help = "Report what would happen without writing").flag()
    private val collection by option("--collection", help = "Target collection").default("vault")

    override fun run() {
        val report = CliOperations.importVault(from, to, dryRun, Collection(collection))
        echo(importVaultSummary(if (dryRun) "would import" else "imported", report))
        report.renames.forEach { rename ->
            echo("${rename.sourcePath} -> ${rename.targetId.value}")
        }
        if (report.isLossy) {
            error(
                "import-vault lossy: unreadable=${report.unreadable.size} " +
                    "renamed=${report.renames.size} " +
                    "conflicting=${report.conflictingIds.size + report.sanitizeCollisions.size} " +
                    "failed=${report.failures.size}"
            )
        }
    }
}

/** Import-vault summary in the same terse `key=value` style as export/import. */
private fun importVaultSummary(verb: String, report: TransferReport): String =
    "$verb documents=${report.documentsImported} skipped=${report.documentsSkipped} " +
        "edges=${report.edgesImported} revisions=${report.revisionsImported} " +
        "unreadable=${report.unreadable.size} renamed=${report.renames.size} " +
        "conflicting=${report.conflictingIds.size + report.sanitizeCollisions.size} " +
        "unresolvedLinks=${report.unresolvedLinks}" +
        report.diagnostics()

/** Shared export/import summary, differing only in the leading verb. */
private fun transferSummary(verb: String, report: TransferReport): String =
    "$verb documents=${report.documentsImported} skipped=${report.documentsSkipped} " +
        "edges=${report.edgesImported} revisions=${report.revisionsImported}" +
        report.diagnostics()

/**
 * What the run could not do, appended to the summary only when something went
 * wrong.
 *
 * A silent zero-hit transfer and a complete one print the same line without this.
 * The counters in [TransferReport] were already accurate but never reached a
 * terminal, so an operator watching "exported documents=0" had no way to learn it
 * was 0 *out of 4000 readable notes* rather than 0 out of an empty vault.
 */
private fun TransferReport.diagnostics(): String =
    buildString {
        if (unreadable.isNotEmpty()) {
            append(" unreadable=${unreadable.size}[${unreadable.joinToString { it.value }}]")
        }
        if (failures.isNotEmpty()) {
            append(
                " failed=${failures.size}[" +
                    failures.joinToString { "${it.stage.label}:${it.id.value}:${it.error}" } + "]"
            )
        }
        if (conflictingIds.isNotEmpty()) {
            append(" conflicting=${conflictingIds.size}[${conflictingIds.joinToString { it.value }}]")
        }
        if (renames.isNotEmpty()) {
            append(" sanitized=${renames.size}[${renames.joinToString { "${it.sourcePath}->${it.targetId.value}" }}]")
        }
        if (sanitizeCollisions.isNotEmpty()) {
            append(
                " sanitizeConflicts=${sanitizeCollisions.size}[" +
                    sanitizeCollisions.joinToString { "${it.targetId.value}<-${it.sources.joinToString()}" } + "]"
            )
        }
        if (wikilinkInvalidations.isNotEmpty()) {
            append(
                " brokenLinks=${wikilinkInvalidations.size}[" +
                    wikilinkInvalidations.joinToString {
                        "${it.referrerSource}:[[${it.linkTarget}]]->${it.newTargetId.value}"
                    } +
                    "]"
            )
        }
    }

class TansekiCommand : CliktCommand() {
    override fun run() = Unit
}

fun main(args: Array<String>) {
    TansekiCommand()
        .subcommands(
            InitCommand(),
            IndexCommand(),
            ReindexCommand(),
            RebuildCommand(),
            StatusCommand(),
            ExportCommand(),
            ImportCommand(),
            ImportVaultCommand()
        ).main(args)
}
