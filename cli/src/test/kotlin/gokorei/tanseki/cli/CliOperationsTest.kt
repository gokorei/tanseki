package gokorei.tanseki.cli

import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.RebuildPhase
import gokorei.tanseki.core.application.RebuildProgress
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

class CliOperationsTest {
    @TempDir
    lateinit var tmp: Path

    private fun libraryConfig(name: String) =
        TansekiConfig(
            profile = Profile.LIBRARY,
            path = tmp.resolve("$name.db"),
            indexDir = tmp.resolve("$name.index"),
            logLevel = LogLevel.INFO
        )

    private fun document(id: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = "content-$id",
            contentHash = "hash-$id",
            revision = RevisionId("rev-$id"),
            updatedAt = Instant.fromEpochSeconds(1_700_000_000)
        )

    private fun write(config: TansekiConfig, id: String) {
        Compositions.openLocal(config.path, config.profile, config.indexDir).use { composition ->
            composition.store.write(document(id), "m", "tester")
        }
    }

    @Test
    fun `init creates the vault bookkeeping directory`() {
        CliOperations.init(tmp)
        assertTrue(Files.exists(tmp.resolve(".tanseki")))
    }

    @Test
    fun `reindex reports deltas and status exposes lag and pending size`() {
        val config = libraryConfig("lib")
        write(config, "a")

        val report = CliOperations.reindex(config)
        assertTrue(report.added >= 1)

        val status = CliOperations.status(config)
        assertTrue(status.contains("pendingSize="), status)
        assertTrue(status.contains("indexingLag="), status)
    }

    @Test
    fun `status reports the local projection backlog rather than a clean bill of health`() {
        val config = libraryConfig("backlog")
        // A write leaves a projection operation pending until something drains it.
        write(config, "a")

        val status = CliOperations.status(config)

        assertTrue(Regex("pendingSize=[1-9]").containsMatchIn(status), status)
    }

    @Test
    fun `status falls back to local when the daemon reply is malformed`() {
        assertNull(CliOperations.statusFromDaemonReply("not-json"))
        assertNull(CliOperations.statusFromDaemonReply("[1,2]"))
        assertEquals(
            "daemon pendingSize=2 indexingLag=3",
            CliOperations.statusFromDaemonReply("""{"pendingSize":2,"indexingLag":3}""")
        )
    }

    @Test
    fun `full rebuild reports progress and restores search and traversal`() {
        val config = libraryConfig("rebuild")
        Compositions.openLocal(config.path, config.profile, config.indexDir).use { composition ->
            composition.store.write(
                document("a").copy(content = "rebuildtoken [[b]]", contentHash = "hash-a-link"),
                "m",
                "tester"
            )
            composition.store.write(document("b"), "m", "tester")
        }
        val progress = mutableListOf<RebuildProgress>()

        val report = CliOperations.rebuild(config, progress::add)
        val secondReport = CliOperations.rebuild(config)

        assertTrue(report.successful)
        assertTrue(secondReport.successful)
        assertTrue(progress.any { it.phase == RebuildPhase.INDEXING })
        Compositions.openLocal(config.path, config.profile, config.indexDir).use { composition ->
            assertEquals(listOf(DocId("a")), composition.lookup.searchText("rebuildtoken", Filters(), 10).map { it.id })
            assertEquals(listOf(DocId("b")), composition.lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))
        }
    }

    @Test
    fun `export then import round-trips ids`() {
        val source = libraryConfig("source")
        write(source, "a")
        val targetLibrary = tmp.resolve("target.db")

        val export = CliOperations.export(source, targetLibrary)
        assertEquals(1, export.documentsImported)

        val back = libraryConfig("back")
        val importReport = CliOperations.importInto(targetLibrary, back)
        assertEquals(1, importReport.documentsImported)

        Compositions.openLocal(back.path, back.profile, back.indexDir).use { composition ->
            assertEquals(listOf(DocId("a")), composition.store.list().map { it.id })
        }
    }

    @Test
    fun `export is idempotent on re-run`() {
        val source = libraryConfig("source")
        write(source, "a")
        val targetLibrary = tmp.resolve("target.db")

        CliOperations.export(source, targetLibrary)
        val second = CliOperations.export(source, targetLibrary)
        assertEquals(0, second.documentsImported)
        assertEquals(1, second.documentsSkipped)
    }
}
