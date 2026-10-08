package gokorei.tanseki.cli.bench

import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.core.application.FullRebuilder
import gokorei.tanseki.core.application.Indexer
import gokorei.tanseki.core.application.Reconciler
import gokorei.tanseki.core.ports.Filters
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.random.Random

/**
 * Synthetic large-vault benchmark: index build, search latency, reconcile.
 *
 * Generates [BenchConfig.docs] deterministic Markdown notes under a scratch
 * vault (never committed), builds the lookup with [FullRebuilder], measures
 * text-search latency percentiles, then measures a cold reconcile, a warm
 * (no-op) reconcile and an incremental reconcile on one retained [Reconciler].
 *
 * The fixtures deliberately contain no `[[wikilinks]]` and no `files:` refs:
 * every link would send [gokorei.tanseki.core.application.EdgeDeriver] back to
 * the store for resolution, which is real behaviour but would dominate the
 * timings and hide the build/search costs this harness exists to track. Tags
 * and author frontmatter still exercise edge derivation.
 *
 * Run with `./gradlew :cli:benchLargeVault -PbenchDocs=1000`. The report is
 * written under `build/reports/bench/`; copy the numbers into
 * `docs/scaling.md` by hand so the committed baseline stays deliberate.
 */
object VaultBench {
    private val topics =
        listOf(
            "granite",
            "harbor",
            "lantern",
            "meadow",
            "compass",
            "thicket",
            "harbor-light",
            "riverbed",
            "summit",
            "orchard",
            "tundra",
            "canyon",
            "willow",
            "ember",
            "glacier",
            "prairie",
            "fjord",
            "savanna",
            "monsoon",
            "dune",
            "atoll",
            "steppe",
            "aurora",
            "zephyr"
        )

    private val filler =
        listOf(
            "field",
            "notes",
            "survey",
            "sketch",
            "record",
            "ledger",
            "margin",
            "appendix",
            "folio",
            "passage",
            "chapter",
            "entry"
        )

    fun run(args: Array<String>) {
        val config = parseArgs(args)
        val vault = config.work.resolve("vault")
        val indexDir = config.work.resolve("index")
        if (Files.exists(config.work)) {
            Files.walk(config.work).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        vault.createDirectories()
        indexDir.createDirectories()

        var generatedBytes = 0L
        measureNanos { generatedBytes = generateVault(vault, config) }
        val avgBytes = generatedBytes / config.docs.toLong()

        Compositions.openLocal(vault, Profile.VAULT, indexDir).use { composition ->
            val indexer =
                Indexer(
                    store = composition.store,
                    lookup = composition.lookup,
                    vectorWriter = composition.vectorWriter
                )
            val buildNanos =
                measureNanos {
                    val report = FullRebuilder(composition.store, composition.lookup, indexer).rebuild()
                    check(report.successful) { "rebuild failed: ${report.failures}" }
                }
            val latencies = measureSearch(composition.lookup::searchText, config.queries)

            val reconciler = Reconciler(composition.store, composition.lookup, indexer)
            val cold = measureNanos { reconciler.reconcile() }
            val warm = measureNanos { reconciler.reconcile() }
            touchDocs(vault, count = 10)
            val incremental = measureNanos { reconciler.reconcile() }

            val report =
                renderReport(
                    config = config,
                    avgBytes = avgBytes,
                    buildNanos = buildNanos,
                    latencies = latencies,
                    coldNanos = cold,
                    warmNanos = warm,
                    incrementalNanos = incremental
                )
            println(report)
            val reportFile = Path.of(config.report)
            reportFile.parent?.let { Files.createDirectories(it) }
            Files.writeString(reportFile, report)
            println("report: ${reportFile.toAbsolutePath()}")
        }
    }

    internal data class BenchConfig(
        val docs: Int = 1000,
        val queries: Int = 30,
        val seed: Long = 7L,
        val work: Path = Path.of("build", "bench-vault"),
        val report: String = Path.of("build", "reports", "bench", "bench-large-vault.md").toString()
    )

    internal fun parseArgs(args: Array<String>): BenchConfig {
        var config = BenchConfig()
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--docs" -> config = config.copy(docs = args[index + 1].toInt())
                "--queries" -> config = config.copy(queries = args[index + 1].toInt())
                "--seed" -> config = config.copy(seed = args[index + 1].toLong())
                "--work" -> config = config.copy(work = Path.of(args[index + 1]))
                "--report" -> config = config.copy(report = args[index + 1])
                else -> error("unknown argument '${args[index]}'")
            }
            index += 2
        }
        require(config.docs > 0) { "--docs must be positive" }
        require(config.queries > 0) { "--queries must be positive" }
        return config
    }

    /**
     * Writes deterministic notes. Content is pure prose plus frontmatter so a
     * full index pass costs reads, parses and Lucene writes rather than graph
     * resolution (see the class KDoc for why links are excluded).
     */
    private fun generateVault(vault: Path, config: BenchConfig): Long {
        val random = Random(config.seed)
        val notes = vault.resolve("notes")
        Files.createDirectories(notes)
        var bytes = 0L
        repeat(config.docs) { ordinal ->
            val topic = topics[ordinal % topics.size]
            val name = "doc-%05d".format(ordinal)
            val body = renderDoc(random, ordinal, name, topic)
            val file = notes.resolve("$name.md")
            Files.writeString(file, body, StandardOpenOption.CREATE_NEW)
            bytes += body.toByteArray().size
        }
        return bytes
    }

    private fun renderDoc(random: Random, ordinal: Int, name: String, topic: String): String {
        val tag = "topic-$topic"
        val other = filler[random.nextInt(filler.size)]
        val author = "bench-author-${ordinal % 5}"
        val paragraphs =
            (1..4).joinToString("\n\n") { paragraph ->
                val words =
                    (1..60).joinToString(" ") {
                        when (random.nextInt(20)) {
                            0 -> topic
                            1 -> other
                            else -> filler[random.nextInt(filler.size)]
                        }
                    }
                "Paragraph $paragraph of $name. $words."
            }
        return "---\ntitle: Bench note $name about $topic\nauthor: $author\ntags: [bench, $tag]\n---\n\n" +
            "# $name\n\n$paragraphs\n"
    }

    private fun measureSearch(
        search: (String, Filters, Int) -> List<*>,
        queries: Int
    ): List<Double> {
        val terms = (topics.take(12) + listOf("notes", "bench", "zzz-no-such-term")).toList()
        repeat(3) { search(terms[it % terms.size], Filters(), 20) }
        return (0 until queries).map { ordinal ->
            val term = terms[ordinal % terms.size]
            measureNanos { search(term, Filters(), 20) } / 1_000_000.0
        }
    }

    private fun touchDocs(vault: Path, count: Int) {
        Files.list(vault.resolve("notes")).use { stream ->
            stream.sorted().limit(count.toLong()).forEach { file ->
                Files.writeString(file, "\n\nTouched with benchsignal.\n", StandardOpenOption.APPEND)
            }
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = (fraction * sorted.size).toInt().coerceAtLeast(1) - 1
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }

    private fun measureNanos(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    /** Locale-independent one-decimal formatting; the default locale may use `,` as the decimal separator. */
    private fun formatMs(value: Double): String = "%.1f".format(Locale.ROOT, value)

    private fun renderReport(
        config: BenchConfig,
        avgBytes: Long,
        buildNanos: Long,
        latencies: List<Double>,
        coldNanos: Long,
        warmNanos: Long,
        incrementalNanos: Long
    ): String {
        val sorted = latencies.sorted()
        val buildMs = buildNanos / 1_000_000.0
        val line =
            { label: String, ms: Double, extra: String ->
                "| $label | ${formatMs(ms)} | $extra |"
            }
        return buildString {
            appendLine("# Large-vault benchmark")
            appendLine()
            appendLine("- docs: ${config.docs} (avg $avgBytes bytes, seed ${config.seed})")
            appendLine("- queries: ${config.queries}")
            appendLine(
                "- host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
                    "${Runtime.getRuntime().availableProcessors()} cores, " +
                    "${System.getProperty("java.version")}"
            )
            appendLine()
            appendLine("| phase | ms | notes |")
            appendLine("| --- | --- | --- |")
            appendLine(
                line(
                    "index build (full rebuild)",
                    buildMs,
                    "${formatMs(config.docs / (buildMs / 1000.0))} docs/s"
                )
            )
            appendLine(line("search mean", sorted.average(), "over ${latencies.size} text queries, limit 20"))
            appendLine(line("search p50", percentile(sorted, 0.50), ""))
            appendLine(line("search p95", percentile(sorted, 0.95), ""))
            appendLine(line("search max", sorted.maxOrNull() ?: 0.0, ""))
            appendLine(line("reconcile cold", coldNanos / 1_000_000.0, "fresh reconciler, full pass"))
            appendLine(line("reconcile warm", warmNanos / 1_000_000.0, "same reconciler, no changes"))
            appendLine(
                line(
                    "reconcile incremental",
                    incrementalNanos / 1_000_000.0,
                    "same reconciler, 10 docs touched"
                )
            )
        }
    }
}

fun main(args: Array<String>) = VaultBench.run(args)
