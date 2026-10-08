package gokorei.tanseki.cli.transfer

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.adapters.context.sqlite.SqliteContextStore
import gokorei.tanseki.adapters.context.sqlite.SqliteSchema
import gokorei.tanseki.adapters.file.FileContextStore
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.TransferState
import gokorei.tanseki.testkit.FakePijulClient
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ModeTransferTest {
    @TempDir
    lateinit var tmp: Path

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun vaultStore(name: String): FileContextStore {
        val root = Files.createDirectories(tmp.resolve(name))
        return FileContextStore(
            vault = VaultPath(root.toString()),
            pijul = FakePijulClient(),
            clock = Clock { now }
        )
    }

    private fun sqliteStore(): SqliteContextStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqliteSchema.create(driver)
        return SqliteContextStore(driver, Clock { now })
    }

    private fun doc(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId("rev"),
            updatedAt = now
        )

    @Test
    fun `vault exports to sqlite and ids are preserved`() {
        val source = vaultStore("src")
        source.write(doc("notes/a", "alpha"), "m", "t")
        source.write(doc("notes/b", "beta"), "m", "t")
        source.upsertEdge(Edge(DocId("notes/a"), DocId("notes/b"), RelTypes.LinksTo))
        val target = sqliteStore()

        val report = ModeTransfer().copy(source, target)

        assertEquals(2, report.documentsImported)
        assertEquals(0, report.documentsSkipped)
        assertEquals(1, report.edgesImported)
        assertEquals(setOf(DocId("notes/a"), DocId("notes/b")), target.list().map { it.id }.toSet())
        assertEquals("alpha", target.read(DocId("notes/a"))!!.content)
        assertEquals(1, target.neighbors(DocId("notes/a")).size)
    }

    @Test
    fun `re-running the export is idempotent`() {
        val source = vaultStore("src")
        source.write(doc("a", "alpha"), "m", "t")
        val target = sqliteStore()
        val transfer = ModeTransfer()

        transfer.copy(source, target)
        val second = transfer.copy(source, target)

        assertEquals(0, second.documentsImported)
        assertEquals(1, second.documentsSkipped)
    }

    @Test
    fun `sqlite re-imports back to a vault preserving ids`() {
        val source = vaultStore("src")
        source.write(doc("notes/a", "alpha"), "m", "t")
        val library = sqliteStore()
        val back = vaultStore("back")

        ModeTransfer().copy(source, library)
        val report = ModeTransfer().copy(library, back)

        assertEquals(1, report.documentsImported)
        assertEquals(listOf(DocId("notes/a")), back.list().map { it.id })
        assertEquals("alpha", back.read(DocId("notes/a"))!!.content)
    }

    /**
     * A transfer is the command a user runs to move their whole vault. One
     * malformed note out of thousands must cost them that note, not the run.
     */
    @Test
    fun `a document that throws on read does not abort the run`() {
        val backing = InMemoryContextStore()
        backing.write(doc("good/a", "alpha"), "write", "tester")
        backing.write(doc("bad/b", "beta"), "write", "tester")
        backing.write(doc("good/c", "gamma"), "write", "tester")
        val source =
            object : ContextStore by backing {
                override fun readIncludingDeleted(id: DocId): Document? =
                    if (id == DocId("bad/b")) {
                        throw IllegalStateException("cannot parse")
                    } else {
                        backing.readIncludingDeleted(id)
                    }
            }
        val target = InMemoryContextStore()

        val report = ModeTransfer().copy(source, target)

        assertEquals(2, report.documentsImported, "the healthy documents must still arrive")
        assertEquals(
            listOf("good/a", "good/c"),
            target.list().map { it.id.value }.sorted()
        )
        assertEquals(listOf(DocId("bad/b")), report.unreadable)
    }

    @Test
    fun `a target write that throws for one id does not abort the run`() {
        val source = InMemoryContextStore()
        source.write(doc("a", "alpha"), "write", "tester")
        source.write(doc("b", "beta"), "write", "tester")
        val backing = InMemoryContextStore()
        val target =
            object : ContextStore by backing {
                override fun importTransferState(state: TransferState): Boolean =
                    if (state.document.id == DocId("b")) {
                        throw IllegalStateException("disk full")
                    } else {
                        backing.importTransferState(state)
                    }
            }

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.documentsImported)
        assertEquals(listOf("a"), backing.list().map { it.id.value })
        assertEquals(
            listOf(TransferFailure(DocId("b"), TransferStage.WRITE, "IllegalStateException")),
            report.failures
        )
        assertEquals(
            0,
            report.documentsStale,
            "a failed write is a fault, not the target refusing a stale copy"
        )
    }

    @Test
    fun `an edge pass that throws is contained`() {
        val source = InMemoryContextStore()
        source.write(doc("a", "alpha"), "write", "tester")
        source.write(doc("b", "beta"), "write", "tester")
        source.replaceEdges(DocId("a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
        val backing = InMemoryContextStore()
        val target =
            object : ContextStore by backing {
                override fun replaceEdges(
                    id: DocId,
                    edges: List<Edge>
                ) {
                    if (id == DocId("a")) {
                        throw IllegalStateException("edge store unavailable")
                    } else {
                        backing.replaceEdges(id, edges)
                    }
                }
            }

        val report = ModeTransfer().copy(source, target)

        assertEquals(2, report.documentsImported, "the documents still copy even if edges cannot")
        assertEquals(
            listOf(TransferFailure(DocId("a"), TransferStage.EDGES, "IllegalStateException")),
            report.failures
        )
    }

    @Test
    fun `an edge whose endpoint cannot be read is dropped and reported`() {
        val backing = InMemoryContextStore()
        backing.write(doc("a", "alpha"), "write", "tester")
        backing.upsertEdge(Edge(DocId("a"), DocId("missing"), RelTypes.LinksTo))
        val source =
            object : ContextStore by backing {
                override fun readIncludingDeleted(id: DocId): Document? =
                    if (id == DocId("missing")) {
                        throw IllegalStateException("cannot read endpoint")
                    } else {
                        backing.readIncludingDeleted(id)
                    }
            }
        val target = InMemoryContextStore()

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.documentsImported)
        // The source id must not be synthesized onto the target: that would leave an
        // edge addressing a document the target does not hold.
        assertTrue(target.neighbors(DocId("a")).isEmpty(), target.neighbors(DocId("a")).toString())
        assertTrue(
            report.failures.any { it.id == DocId("a") && it.stage == TransferStage.EDGES },
            report.failures.toString()
        )
    }

    @Test
    fun `a history import that throws still lets the document copy`() {
        val source = InMemoryContextStore()
        source.write(doc("a", "alpha"), "write", "tester")
        val backing = InMemoryContextStore()
        val target =
            object : ContextStore by backing {
                override fun appendHistory(
                    id: DocId,
                    revisions: List<Revision>
                ) {
                    if (id == DocId("a")) {
                        throw IllegalStateException("ledger locked")
                    } else {
                        backing.appendHistory(id, revisions)
                    }
                }
            }

        val report = ModeTransfer().copy(source, target)

        assertEquals(listOf("a"), backing.list().map { it.id.value })
        assertEquals(
            listOf(TransferFailure(DocId("a"), TransferStage.HISTORY, "IllegalStateException")),
            report.failures
        )
    }

    @Test
    fun `a throwing tombstone edge cleanup is contained`() {
        val source = InMemoryContextStore()
        source.write(doc("a", "alpha"), "write", "tester")
        source.delete(DocId("a"), "remove", "tester")
        val backing = InMemoryContextStore()
        val target =
            object : ContextStore by backing {
                override fun removeIncomingEdges(
                    dst: DocId,
                    rel: RelType?
                ): Unit = throw IllegalStateException("cannot prune")
            }

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.tombstonesImported)
        assertEquals(
            listOf(TransferFailure(DocId("a"), TransferStage.INCOMING_EDGES, "IllegalStateException")),
            report.failures
        )
    }

    @Test
    fun `a healthy transfer reports no failures`() {
        val source = InMemoryContextStore()
        source.write(doc("a", "alpha"), "write", "tester")
        source.write(doc("b", "beta"), "write", "tester")
        source.replaceEdges(DocId("a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))

        val report = ModeTransfer().copy(source, InMemoryContextStore())

        assertEquals(emptyList<TransferFailure>(), report.failures)
        assertEquals(emptyList<DocId>(), report.unreadable)
        assertEquals(emptyList<DocId>(), report.conflictingIds)
        assertEquals(2, report.documentsImported)
    }

    @Test
    fun `a read that throws is not reported as a write fault`() {
        val backing = InMemoryContextStore()
        backing.write(doc("bad/b", "beta"), "write", "tester")
        val source =
            object : ContextStore by backing {
                override fun readIncludingDeleted(id: DocId): Document? =
                    throw IllegalStateException("cannot parse")
            }

        val report = ModeTransfer().copy(source, InMemoryContextStore())

        assertEquals(listOf(DocId("bad/b")), report.unreadable)
        assertEquals(
            listOf(TransferFailure(DocId("bad/b"), TransferStage.READ, "IllegalStateException")),
            report.failures,
            "the remedy differs: fix the source, not the target"
        )
    }

    @Test
    fun `a throwing read is listed once`() {
        val backing = InMemoryContextStore()
        backing.write(doc("bad/b", "beta"), "write", "tester")
        val source =
            object : ContextStore by backing {
                override fun readIncludingDeleted(id: DocId): Document? =
                    throw IllegalStateException("cannot parse")
            }

        val report = ModeTransfer().copy(source, InMemoryContextStore())

        assertEquals(1, report.unreadable.size)
        assertEquals(1, report.failures.size)
    }

    @Test
    fun `transfer reports listed documents that cannot be read`() {
        val backing = InMemoryContextStore()
        backing.write(doc("unreadable", "content"), "write", "tester")
        val ref = backing.listAll().single()
        val source =
            object : ContextStore by backing {
                override fun listAll() = listOf(ref)

                override fun readIncludingDeleted(id: DocId): Document? = null
            }

        val report = ModeTransfer().copy(source, InMemoryContextStore())

        assertEquals(listOf(DocId("unreadable")), report.unreadable)
        assertEquals(0, report.documentsImported)
    }

    @Test
    fun `transfer preserves tombstones and their history`() {
        val source = InMemoryContextStore()
        val document = doc("deleted", "gone")
        source.write(document, "create", "tester")
        val deleted = source.delete(document.id, "delete", "tester")
        source.upsertEdge(Edge(document.id, DocId("target"), RelTypes.LinksTo))
        val target = sqliteStore()

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.tombstonesImported)
        assertEquals(null, target.read(document.id))
        assertEquals(deleted.revision, target.historyOwner(document.id)!!.revision)
        assertEquals(
            source.history(document.id).map { it.revision }.toSet(),
            target.history(document.id).map { it.revision }.toSet()
        )
        // `target` is not in the source, so the endpoint cannot be rewritten
        // without synthesizing a dangling id; the edge is dropped instead.
        assertTrue(target.neighbors(document.id).isEmpty(), target.neighbors(document.id).toString())
    }

    @Test
    fun `a tombstone drops edges that pointed at the deleted document`() {
        val source = InMemoryContextStore()
        source.write(doc("source", "See [[deleted]]"), "create", "tester")
        source.write(doc("deleted", "gone"), "create", "tester")
        source.upsertEdge(Edge(DocId("source"), DocId("deleted"), RelTypes.LinksTo))
        source.delete(DocId("deleted"), "delete", "tester")
        val target = sqliteStore()

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.tombstonesImported)
        assertTrue(target.neighbors(DocId("source")).isEmpty())
    }

    @Test
    fun `a stale source document never rolls back a newer target document`() {
        val source = InMemoryContextStore()
        val stale = doc("shared", "old content").copy(updatedAt = now.minus(60.seconds))
        source.write(stale, "old", "tester")
        val target = InMemoryContextStore()
        target.write(
            doc("shared", "new content").copy(updatedAt = now.plus(60.seconds)),
            "new",
            "tester"
        )

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.documentsStale)
        assertEquals(0, report.documentsImported)
        assertEquals("new content", target.read(DocId("shared"))!!.content)
    }

    @Test
    fun `a target that refuses the copy reports it as stale and keeps its edges`() {
        val source = InMemoryContextStore()
        source.write(doc("shared", "old content").copy(updatedAt = now.minus(60.seconds)), "old", "tester")
        source.upsertEdge(Edge(DocId("shared"), DocId("other"), RelTypes.LinksTo))
        val target =
            object : ContextStore by InMemoryContextStore() {
                override fun importTransferState(state: gokorei.tanseki.core.ports.TransferState): Boolean = false
            }
        target.write(
            doc("shared", "new content").copy(updatedAt = now.plus(60.seconds)),
            "new",
            "tester"
        )
        target.upsertEdge(Edge(DocId("shared"), DocId("target-edge"), RelTypes.LinksTo))

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.documentsStale)
        assertEquals(0, report.edgesImported)
        assertEquals("new content", target.read(DocId("shared"))!!.content)
        assertEquals(listOf(DocId("target-edge")), target.neighbors(DocId("shared")).map { it.dst })
    }

    @Test
    fun `two source documents that collapse onto one target id are reported as conflicts`() {
        // No current adapter can hold two ids under one path; this source models
        // data predating the id-derived path contract, which transfer must report
        // as conflicts rather than collapsing silently.
        val source =
            LegacySource(
                Document(
                    id = DocId("a"),
                    collection = Collection("vault"),
                    path = "custom/place.md",
                    content = "first",
                    contentHash = "hash-first",
                    revision = RevisionId("rev-first"),
                    updatedAt = now
                ),
                Document(
                    id = DocId("alias"),
                    collection = Collection("vault"),
                    path = "custom/place.md",
                    content = "second",
                    contentHash = "hash-second",
                    revision = RevisionId("rev-second"),
                    updatedAt = now
                )
            )
        val vault = vaultStore("conflicts")

        val report = ModeTransfer().copy(source, vault)

        assertEquals(listOf(DocId("alias")), report.conflictingIds)
        assertEquals(1, report.documentsImported)
        assertEquals("first", vault.read(DocId("custom/place"))!!.content)
    }

    @Test
    fun `a vault to library copy keeps edge endpoints addressable`() {
        val vault = vaultStore("src")
        vault.write(doc("notes/a", "See [[notes/b]]"), "m", "t")
        vault.write(doc("notes/b", "target"), "m", "t")
        vault.upsertEdge(Edge(DocId("notes/a"), DocId("notes/b"), RelTypes.LinksTo))
        val library = sqliteStore()

        val report = ModeTransfer().copy(vault, library)

        assertEquals(1, report.edgesImported)
        assertEquals(listOf(DocId("notes/b")), library.neighbors(DocId("notes/a")).map { it.dst })
        library.list().forEach { ref ->
            library.neighbors(ref.id).forEach { edge ->
                assertTrue(library.read(edge.dst) != null, "edge points at a document the target does not hold")
            }
        }
    }

    @Test
    fun `a stale tombstone never resurrects a newer target document`() {
        val source = InMemoryContextStore()
        val document = doc("shared", "old content")
        source.write(document, "old", "tester")
        source.delete(DocId("shared"), "delete", "tester")
        val target = InMemoryContextStore()
        target.write(
            doc("shared", "new content").copy(updatedAt = now.plus(60.seconds)),
            "new",
            "tester"
        )

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.tombstonesStale)
        assertEquals("new content", target.read(DocId("shared"))!!.content)
    }

    @Test
    fun `a stale tombstone does not strip a live target document's inbound edges`() {
        val source = InMemoryContextStore()
        source.write(doc("shared", "old content"), "old", "tester")
        source.delete(DocId("shared"), "delete", "tester")
        val target = InMemoryContextStore()
        target.write(
            doc("shared", "new content").copy(updatedAt = now.plus(60.seconds)),
            "new",
            "tester"
        )
        target.upsertEdge(Edge(DocId("referrer"), DocId("shared"), RelTypes.LinksTo))

        val report = ModeTransfer().copy(source, target)

        assertEquals(1, report.tombstonesStale)
        assertEquals("new content", target.read(DocId("shared"))!!.content)
        assertEquals(
            listOf(DocId("shared")),
            target.neighbors(DocId("referrer")).map { it.dst },
            "a stale tombstone must not strip edges pointing at the live target document"
        )
    }

    @Test
    fun `a tombstone the target refuses does not strip live target edges`() {
        val source = InMemoryContextStore()
        source.write(doc("shared", "gone"), "create", "tester")
        source.delete(DocId("shared"), "delete", "tester")
        val target =
            object : ContextStore by InMemoryContextStore() {
                override fun importTransferState(state: TransferState): Boolean = false
            }
        target.write(doc("shared", "live"), "new", "tester")
        target.upsertEdge(Edge(DocId("referrer"), DocId("shared"), RelTypes.LinksTo))

        val report = ModeTransfer().copy(source, target)

        assertEquals(0, report.tombstonesImported)
        assertEquals(
            listOf(DocId("shared")),
            target.neighbors(DocId("referrer")).map { it.dst },
            "a refused tombstone must not strip edges pointing at the live target document"
        )
    }

    @Test
    fun `a library document transfers into the vault at its derived path`() {
        // The path contract is id-derived on every profile, so a transfer
        // preserves the path by construction: the vault stores the document
        // under the same id and the same derived path it had in the library.
        val library = sqliteStore()
        library.write(
            Document(
                id = DocId("custom/place"),
                collection = Collection("vault"),
                path = "custom/place.md",
                content = "custom path body",
                contentHash = "hash-custom",
                revision = RevisionId("rev-custom"),
                updatedAt = now
            ),
            "m",
            "tester"
        )
        library.upsertEdge(Edge(DocId("custom/place"), DocId("b"), RelTypes.LinksTo))
        val vault = vaultStore("target")

        val report = ModeTransfer().copy(library, vault)

        assertEquals(1, report.documentsImported)
        assertEquals(listOf(DocId("custom/place")), vault.list().map { it.id })
        assertEquals("custom/place.md", vault.read(DocId("custom/place"))!!.path)
        assertEquals("custom path body", vault.read(DocId("custom/place"))!!.content)
        // `b` is not in the library, so its endpoint cannot be rewritten; the edge is
        // dropped rather than emitted against a source id the vault does not hold.
        assertTrue(vault.neighbors(DocId("custom/place")).isEmpty())
    }

    @Test
    fun `transfer reconciles current revision when content is unchanged`() {
        val source = InMemoryContextStore()
        val sourceDocument = doc("same", "same-content").copy(revision = RevisionId("source-revision"))
        source.importTransferState(
            gokorei.tanseki.core.ports.TransferState(
                sourceDocument,
                listOf(
                    gokorei.tanseki.core.domain.Revision(
                        sourceDocument.id,
                        sourceDocument.revision,
                        "tester",
                        "source",
                        sourceDocument.contentHash,
                        now,
                        content = sourceDocument.content
                    )
                )
            )
        )
        val target = sqliteStore()
        target.write(doc("same", "same-content").copy(revision = RevisionId("target-revision")), "target", "tester")

        ModeTransfer().copy(source, target)

        assertEquals(sourceDocument.revision, target.read(sourceDocument.id)!!.revision)
    }

    /**
     * A read-only source holding documents no current adapter can write: ids
     * sharing one path. Models data predating the id-derived path contract so
     * transfer's collision reporting stays covered without weakening a real
     * store's write path to plant it.
     */
    private class LegacySource(vararg docs: Document) : ContextStore {
        private val byId = docs.associateBy { it.id }

        override fun read(id: DocId): Document? = byId[id]?.takeUnless { it.deleted }

        override fun readIncludingDeleted(id: DocId): Document? = byId[id]

        override fun listAll(): List<DocRef> =
            byId.values
                .map { DocRef(it.id, it.collection, it.path, it.contentHash, it.revision, it.updatedAt) }
                .sortedBy { it.id.value }

        override fun list(collection: Collection?): List<DocRef> =
            listAll().filter {
                (collection == null || it.collection == collection) && byId.getValue(it.id).deleted.not()
            }

        override fun write(
            doc: Document,
            message: String,
            author: String,
            ifRevision: RevisionId?
        ): Revision = throw UnsupportedOperationException("legacy source is read-only")

        override fun delete(
            id: DocId,
            message: String,
            author: String,
            ifRevision: RevisionId?
        ): Revision = throw UnsupportedOperationException("legacy source is read-only")

        override fun upsertEdge(edge: Edge): Unit = throw UnsupportedOperationException("legacy source is read-only")

        override fun removeEdges(src: DocId, rel: RelType?) = Unit

        override fun neighbors(id: DocId, rel: RelType?): List<Edge> = emptyList()

        override fun history(id: DocId): List<Revision> = emptyList()

        override fun putBlob(bytes: ByteArray): BlobRef =
            throw UnsupportedOperationException("legacy source is read-only")

        override fun getBlob(ref: BlobRef): ByteArray =
            throw UnsupportedOperationException("legacy source is read-only")

        override fun capabilities(): StoreCapabilities =
            StoreCapabilities(
                supportsHistory = true,
                supportsPatchGraph = false,
                supportsTransactions = false,
                consistency = Consistency.READ_YOUR_WRITES
            )
    }
}
