package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.PijulClient
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.PijulStatus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Instant

/** Derived-state directory name; mirrors `VaultDirs.DERIVED` so a rename is one edit. */
private const val DERIVED = ".tanseki"

class FileContextStoreTest {
    @TempDir
    lateinit var vault: Path

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private class FakePijul(private val now: Instant) : PijulClient {
        private var counter = 0
        private var nextFailure: RecordFailure? = null
        private val pendingPaths = mutableSetOf<String>()
        val recordCalls = mutableListOf<List<String>>()
        private val patches = mutableMapOf<String, MutableList<PijulPatch>>()

        fun failNextRecord(failure: RecordFailure) {
            nextFailure = failure
        }

        override fun status(vault: VaultPath): PijulStatus =
            PijulStatus(
                clean = pendingPaths.isEmpty(),
                modified = pendingPaths.sorted()
            )

        override fun log(vault: VaultPath, docId: DocId?, limit: Int): List<PijulPatch> =
            docId?.let { patches["${it.value}.md"].orEmpty().take(limit) } ?: patches.values.flatten().take(limit)

        override fun diff(vault: VaultPath, path: String?) = ""

        override fun record(
            vault: VaultPath,
            message: String,
            author: String,
            paths: List<String>,
            operationId: String?
        ): PijulPatch {
            recordCalls += paths
            val failure = nextFailure
            nextFailure = null
            if (failure == RecordFailure.BEFORE_PERSIST) {
                pendingPaths += paths
                throw UnavailableException("injected failure before Pijul persisted the patch")
            }
            val patch =
                PijulPatch(
                    RevisionId("patch-${++counter}"),
                    author,
                    message,
                    now,
                    operationId = operationId
                )
            paths.forEach { patches.getOrPut(it) { mutableListOf() } += patch }
            pendingPaths -= paths.toSet()
            if (failure == RecordFailure.AFTER_PERSIST) {
                throw UnavailableException("injected failure after Pijul persisted the patch")
            }
            return patch
        }

        override fun apply(vault: VaultPath, patch: String) = Unit
    }

    private enum class RecordFailure {
        BEFORE_PERSIST,
        AFTER_PERSIST
    }

    /**
     * A pijul client whose record can be parked on a latch, so a test can hold a
     * write inside the subprocess and prove other operations do not queue behind
     * the store-wide lock.
     */
    private class GatedPijul(private val delegate: FakePijul) : PijulClient by delegate {
        @Volatile
        var gated = false

        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        override fun record(
            vault: gokorei.tanseki.core.domain.VaultPath,
            message: String,
            author: String,
            paths: List<String>,
            operationId: String?
        ): PijulPatch {
            if (gated) {
                entered.countDown()
                require(release.await(30, java.util.concurrent.TimeUnit.SECONDS))
            }
            return delegate.record(vault, message, author, paths, operationId)
        }
    }

    private fun store(pijul: PijulClient): FileContextStore =
        FileContextStore(
            vault = VaultPath(vault.toString()),
            pijul = pijul,
            clock = Clock { now }
        )

    private fun doc(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "",
            revision = RevisionId("rev"),
            updatedAt = now
        )

    private fun regularFiles(directory: Path): List<Path> =
        Files.walk(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .toList()
        }

    private fun legacyName(value: String): String = value.replace("/", "__").replace("\\", "__")

    @Test
    fun `rename moves the file and records the move against both paths`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/old", "the body"), "add", "agent")

        store.rename(DocId("notes/old"), DocId("folder/new"), "move", "agent")

        assertNull(store.read(DocId("notes/old")))
        assertEquals("the body", store.read(DocId("folder/new"))!!.content)
        assertFalse(Files.exists(vault.resolve("notes/old.md")))
        assertTrue(Files.exists(vault.resolve("folder/new.md")))
        // Both ends of the move go into one patch. Recording only the new path
        // would leave the DAG believing the document still lived at the old one.
        assertTrue(pijul.recordCalls.any { it.containsAll(listOf("notes/old.md", "folder/new.md")) })
    }

    @Test
    fun `history reads as one timeline across a rename`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/old", "first"), "add", "agent")
        store.write(doc("notes/old", "second"), "edit", "agent")
        val beforeRename = store.history(DocId("notes/old")).map { it.revision.value }

        store.rename(DocId("notes/old"), DocId("folder/new"), "move", "agent")
        val afterMove = store.history(DocId("folder/new")).map { it.revision.value }
        store.write(doc("folder/new", "third"), "edit again", "agent")
        val afterEdit = store.history(DocId("folder/new")).map { it.revision.value }

        // The pre-rename chain is still reachable after the move, which is the
        // whole point: the DAG is path-keyed and cannot merge two paths, so the
        // store composes them instead of pretending the past never happened.
        assertEquals(beforeRename, afterMove.take(beforeRename.size))
        assertTrue(afterMove.size > beforeRename.size)
        assertTrue(afterEdit.size > afterMove.size)
        // And it reads as one sequence rather than two: timestamps ascend.
        val times = store.history(DocId("folder/new")).map { it.createdAt }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `renaming twice still reaches the first chain`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("a", "one"), "add", "agent")
        val original = store.history(DocId("a")).map { it.revision.value }

        store.rename(DocId("a"), DocId("b"), "move", "agent")
        store.rename(DocId("b"), DocId("c"), "move again", "agent")

        // The earliest origin is kept, so both moves compose back to the start
        // rather than only to the most recent rename.
        val composed = store.history(DocId("c")).map { it.revision.value }
        assertTrue(composed.containsAll(original))
    }

    @Test
    fun `rename re-points inbound wikilinks`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/target", "the target"), "add", "agent")
        store.write(doc("notes/source", "see [[notes/target]] here"), "add", "agent")
        store.upsertEdge(Edge(DocId("notes/source"), DocId("notes/target"), RelTypes.LinksTo))

        store.rename(DocId("notes/target"), DocId("folder/target"), "move", "agent")

        assertEquals("see [[folder/target]] here", store.read(DocId("notes/source"))!!.content)
        assertEquals(
            listOf(DocId("folder/target")),
            store.neighbors(DocId("notes/source")).map { it.dst }
        )
        // The referring document got its own recorded revision rather than being
        // edited in place, so the change appears in its history.
        assertTrue(
            store.history(DocId("notes/source")).any { it.message.contains("relinked") },
            "expected a recorded relink revision"
        )
    }

    @Test
    fun `rename onto an existing document is refused and changes nothing`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/old", "old body"), "add", "agent")
        store.write(doc("notes/taken", "holder body"), "add", "agent")

        assertThrows(ConflictException::class.java) {
            store.rename(DocId("notes/old"), DocId("notes/taken"), "move", "agent")
        }

        assertEquals("old body", store.read(DocId("notes/old"))!!.content)
        assertEquals("holder body", store.read(DocId("notes/taken"))!!.content)
        assertTrue(Files.exists(vault.resolve("notes/old.md")))
    }

    @Test
    fun `rename honours ifRevision and reports unknown documents`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/old", "body"), "add", "agent")
        val current = store.read(DocId("notes/old"))!!.revision

        assertThrows(ConflictException::class.java) {
            store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent", RevisionId("patch-0"))
        }
        assertThrows(gokorei.tanseki.core.domain.NotFoundException::class.java) {
            store.rename(DocId("notes/missing"), DocId("notes/new"), "move", "agent")
        }
        assertThrows(InvalidInputException::class.java) {
            store.rename(DocId("notes/old"), DocId("notes/old"), "move", "agent")
        }

        store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent", current)
        assertEquals("body", store.read(DocId("notes/new"))!!.content)
    }

    @Test
    fun `a rename interrupted before recording is recovered on reopen`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/old", "body"), "add", "agent")
        // The patch fails after Pijul persisted it but before the store completed:
        // the file has moved and the pending record is left behind.
        pijul.failNextRecord(RecordFailure.AFTER_PERSIST)
        assertThrows(UnavailableException::class.java) {
            store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent")
        }
        assertTrue(Files.exists(vault.resolve("notes/new.md")))

        val reopened = store(pijul)
        val recovered = reopened.history(DocId("notes/new"))
        assertTrue(recovered.isNotEmpty(), "recovery should complete the rename")
        assertNull(reopened.read(DocId("notes/old")))
        assertEquals("body", reopened.read(DocId("notes/new"))!!.content)
    }

    @Test
    fun `rename moves the sidecars so the revision is not stranded`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("notes/old", "body"), "add", "agent")
        store.upsertEdge(Edge(DocId("notes/old"), DocId("other"), RelTypes.LinksTo))

        store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent")

        assertEquals(listOf(DocId("other")), store.neighbors(DocId("notes/new")).map { it.dst })
        assertTrue(store.neighbors(DocId("notes/old")).isEmpty())
        assertEquals(RevisionId(pijul.recordCalls.size.let { "patch-$it" }), store.read(DocId("notes/new"))!!.revision)
    }

    @Test
    fun `markdown files are canonical and round-trip losslessly`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        val content = "---\ntitle: A\ntags: [x]\n---\n# Heading\n\nBody with [[link]].\n"

        store.write(doc("notes/a", content), "add", "agent")
        val read = store.read(DocId("notes/a"))!!

        assertEquals(content, read.content)
        assertEquals("A", read.frontmatter.title)
        assertEquals(listOf("x"), read.frontmatter.tags)
        assertEquals(1, store.list(Collection("vault")).size)
        assertTrue(store.capabilities().supportsHistory)
        assertTrue(store.capabilities().supportsPatchGraph)
        assertEquals(Consistency.READ_YOUR_WRITES, store.capabilities().consistency)
    }

    @Test
    fun `file writes require the canonical id-derived markdown path`() {
        val store = store(FakePijul(now))
        val invalid = doc("a", "content").copy(path = "different.md")

        assertThrows(InvalidInputException::class.java) {
            store.write(invalid, "write", "agent")
        }
    }

    @Test
    fun `listAll includes deleted tombstones with their metadata`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content").copy(collection = Collection("archive")), "write", "agent")
        val deleted = store.delete(DocId("a"), "delete", "agent")

        val tombstone = store.listAll().single()

        assertEquals(DocId("a"), tombstone.id)
        assertEquals(Collection("archive"), tombstone.collection)
        assertEquals("a.md", tombstone.path)
        assertEquals(deleted.revision, tombstone.revision)
    }

    @Test
    fun `writing identical content is idempotent and does not record`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        val content = "same"

        store.write(doc("a", content), "add", "agent")
        store.write(doc("a", content), "again", "agent")

        assertEquals(1, pijul.recordCalls.size)
    }

    @Test
    fun `write retry repairs Pijul failure instead of hiding it behind content hash`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        val document = doc("a", "recoverable")
        pijul.failNextRecord(RecordFailure.BEFORE_PERSIST)

        assertThrows(UnavailableException::class.java) {
            store.write(document, "create", "agent")
        }

        assertEquals("recoverable", store.read(DocId("a"))!!.content)
        assertEquals(RevisionId("worktree"), store.read(DocId("a"))!!.revision)
        val revision = store.write(document, "retry", "agent")

        assertEquals(RevisionId("patch-1"), revision.revision)
        assertEquals(2, pijul.recordCalls.size)
    }

    @Test
    fun `write startup recovery adopts a Pijul patch persisted before the failure`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        pijul.failNextRecord(RecordFailure.AFTER_PERSIST)

        assertThrows(UnavailableException::class.java) {
            store.write(doc("a", "committed"), "create", "agent")
        }

        val restarted = store(pijul)

        assertEquals("committed", restarted.read(DocId("a"))!!.content)
        assertEquals(RevisionId("patch-1"), restarted.read(DocId("a"))!!.revision)
        assertEquals(1, pijul.recordCalls.size)
    }

    @Test
    fun `delete startup recovery completes a destructive change after Pijul fails`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("a", "delete me"), "create", "agent")
        store.upsertEdge(Edge(DocId("a"), DocId("target"), RelTypes.LinksTo))
        pijul.failNextRecord(RecordFailure.BEFORE_PERSIST)

        assertThrows(UnavailableException::class.java) {
            store.delete(DocId("a"), "delete", "agent")
        }
        assertFalse(Files.exists(vault.resolve("a.md")))

        val restarted = store(pijul)

        assertNull(restarted.read(DocId("a")))
        assertEquals("delete", restarted.history(DocId("a")).last().message)
        assertEquals(Collection("vault"), restarted.historyOwner(DocId("a"))!!.collection)
        assertTrue(restarted.neighbors(DocId("a")).isEmpty())
        assertEquals(3, pijul.recordCalls.size)
    }

    @Test
    fun `delete startup recovery adopts a Pijul patch persisted before the failure`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("a", "delete me"), "create", "agent")
        pijul.failNextRecord(RecordFailure.AFTER_PERSIST)

        assertThrows(UnavailableException::class.java) {
            store.delete(DocId("a"), "delete", "agent")
        }

        val restarted = store(pijul)

        assertNull(restarted.read(DocId("a")))
        assertEquals("delete", restarted.history(DocId("a")).last().message)
        assertEquals(
            restarted.history(DocId("a")).last().revision,
            restarted.historyOwner(DocId("a"))!!.revision
        )
        assertEquals(2, pijul.recordCalls.size)
    }

    @Test
    fun `ifRevision enforces compare and swap`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("a", "one"), "add", "agent")
        val current = store.read(DocId("a"))!!.revision

        assertThrows(ConflictException::class.java) {
            store.write(doc("a", "two"), "edit", "agent", ifRevision = RevisionId("wrong"))
        }
        store.write(doc("a", "two"), "edit", "agent", ifRevision = current)
        assertEquals("two", store.read(DocId("a"))!!.content)
    }

    @Test
    fun `history comes from the pijul patch dag`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("a", "one"), "first", "agent")
        store.write(doc("a", "two"), "second", "agent")

        val history = store.history(DocId("a"))

        assertEquals(2, history.size)
        assertEquals("patch-2", history.last().revision.value)
        assertEquals("second", history.last().message)
    }

    @Test
    fun `history is not truncated to the client default limit`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        repeat(150) { index ->
            pijul.record(VaultPath(vault.toString()), "revision-$index", "agent", listOf("a.md"))
        }

        assertEquals(150, store.history(DocId("a")).size)
        val first = store.historyPage(DocId("a"), limit = 100)
        val second = store.historyPage(DocId("a"), limit = 100, offset = 100)
        assertEquals(150, first.total)
        assertEquals(150, (first.items + second.items).size)
    }

    @Test
    fun `delete removes the file and retains history plus owner metadata`() {
        val pijul = FakePijul(now)
        val store = store(pijul)
        store.write(doc("a", "one"), "add", "agent")

        val deleted = store.delete(DocId("a"), "bye", "agent")

        assertNull(store.read(DocId("a")))
        assertFalse(Files.exists(vault.resolve("a.md")))
        assertEquals(deleted.revision, store.history(DocId("a")).last().revision)
        assertEquals(Collection("vault"), store.historyOwner(DocId("a"))!!.collection)
        assertEquals(deleted.revision, store.pending(1).single().revision)
        assertEquals(ProjectionKind.DELETE, store.pending(1).single().kind)
    }

    @Test
    fun `edges are stored and filtered by rel`() {
        val store = store(FakePijul(now))
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo, mapOf("label" to "B")))
        store.upsertEdge(Edge(DocId("a"), DocId("c"), RelTypes.References))

        assertEquals(2, store.neighbors(DocId("a")).size)
        val links = store.neighbors(DocId("a"), RelTypes.LinksTo)
        assertEquals(1, links.size)
        assertEquals(DocId("b"), links.single().dst)
        assertEquals("B", links.single().props["label"])
    }

    @Test
    fun `collection is persisted and scopes reads and listings`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "alpha").copy(collection = Collection("alternate")), "m", "agent")

        assertEquals(Collection("alternate"), store.read(DocId("a"))!!.collection)
        assertEquals(listOf(DocId("a")), store.list(Collection("alternate")).map { it.id })
        assertTrue(store.list(Collection("vault")).isEmpty())
    }

    @Test
    fun `documents without a collection tag default to vault`() {
        val store = store(FakePijul(now))
        store.write(doc("b", "beta"), "m", "agent")
        assertEquals(Collection("vault"), store.read(DocId("b"))!!.collection)
    }

    @Test
    fun `blobs are content addressed under the vault`() {
        val store = store(FakePijul(now))
        val ref = store.putBlob("hello".toByteArray())

        assertArrayEquals("hello".toByteArray(), store.getBlob(ref))
        assertTrue(Files.exists(vault.resolve("$DERIVED/blobs/sha256/${ref.hash}")))
    }

    @Test
    fun `canonical write leaves a durable projection operation`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")

        val operation = store.pending(100).single()
        assertEquals(ProjectionKind.UPSERT, operation.kind)
        assertEquals(store.read(DocId("a"))!!.contentHash, operation.contentHash)

        store.complete(operation)
        assertTrue(store.pending(100).isEmpty())
    }

    @Test
    fun `document paths cannot escape through symlinks`() {
        val outside = Files.createTempDirectory("tanseki-outside-")
        val outsideFile = outside.resolve("secret.md")
        Files.writeString(outsideFile, "outside")
        Files.createSymbolicLink(vault.resolve("linked.md"), outsideFile)
        val store = store(FakePijul(now))

        try {
            assertThrows(InvalidInputException::class.java) { store.read(DocId("linked")) }
            assertThrows(InvalidInputException::class.java) { store.write(doc("linked", "changed"), "edit", "agent") }
            assertThrows(InvalidInputException::class.java) { store.delete(DocId("linked"), "delete", "agent") }
            assertEquals("outside", Files.readString(outsideFile))
        } finally {
            Files.deleteIfExists(vault.resolve("linked.md"))
            Files.deleteIfExists(outsideFile)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `listing exposes a content derived change token that survives an unchanged mtime`() {
        val store = store(FakePijul(now))
        val file = Files.createDirectories(vault.resolve("notes")).resolve("a.md")
        Files.writeString(file, "alpha")
        val before = store.list().single()
        val mtime = Files.getLastModifiedTime(file)

        Files.writeString(file, "bravo")
        Files.setLastModifiedTime(file, mtime)
        val after = store.list().single()

        assertEquals(before.revision, after.revision)
        assertTrue(before.changeToken != after.changeToken)
    }

    @Test
    fun `the change token also moves when only the size changes`() {
        val store = store(FakePijul(now))
        val file = Files.createDirectories(vault.resolve("notes")).resolve("a.md")
        Files.writeString(file, "alpha")
        val before = store.list().single().changeToken

        Files.writeString(file, "alpha plus more")
        val after = store.list().single().changeToken

        assertTrue(before != after)
    }

    @Test
    fun `listing excludes symlinked markdown files`() {
        val outside = Files.createTempDirectory("tanseki-outside-")
        val outsideFile = outside.resolve("secret.md")
        Files.writeString(outsideFile, "outside")
        val store = store(FakePijul(now))
        try {
            Files.createSymbolicLink(vault.resolve("linked.md"), outsideFile)
            Files.createDirectories(vault.resolve("notes"))
            Files.writeString(vault.resolve("notes/real.md"), "real")

            assertEquals(listOf(DocId("notes/real")), store.list().map { it.id })
            assertEquals(1, store.listPage(limit = 10).total)
            assertEquals(listOf(DocId("notes/real")), store.listAll().map { it.id })
        } finally {
            Files.deleteIfExists(vault.resolve("linked.md"))
            Files.deleteIfExists(outsideFile)
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `a tombstone keeps the deleted content and hash consistent`() {
        val store = store(FakePijul(now))
        val content = "delete me"
        store.write(doc("a", content), "create", "agent")
        val written = store.read(DocId("a"))!!

        store.delete(DocId("a"), "delete", "agent")
        val tombstone = store.readIncludingDeleted(DocId("a"))!!

        assertTrue(tombstone.deleted)
        assertEquals(content, tombstone.content)
        assertEquals(written.contentHash, tombstone.contentHash)
        assertEquals(store.write(doc("a", content), "recreate", "agent").contentHash, tombstone.contentHash)
    }

    @Test
    fun `an undecodable projection file is reported instead of silently dropped`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")
        val projections = Files.createDirectories(vault.resolve("$DERIVED/projections"))
        Files.writeString(projections.resolve("v2_corrupt"), "not-an-operation")

        val backlog = store.projectionOperations().backlog()

        assertEquals(1, backlog.corrupt)
        assertEquals(1, backlog.pending)
        assertEquals(1, store.pending(10).size)
    }

    @Test
    fun `a completed projection is not reported as corrupt`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")

        val operation = store.pending(10).single()
        store.complete(operation)
        val backlog = store.projectionOperations().backlog()

        assertEquals(0, backlog.corrupt)
        assertEquals(0, backlog.pending)
    }

    @Test
    fun `an outbox file with undecodable bytes is surfaced and can be quarantined`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")
        val projections = Files.createDirectories(vault.resolve("$DERIVED/projections"))
        Files.write(
            projections.resolve("v2_undecodable"),
            byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte())
        )
        val operations = store.projectionOperations()

        val backlog = operations.backlog()

        assertEquals(1, backlog.corrupt)
        assertEquals(1, backlog.pending)
        assertEquals(1, operations.pending(10).size)

        assertEquals(1, operations.quarantineCorrupt())

        assertEquals(0, operations.backlog().corrupt)
        assertEquals(1, operations.pending(10).size)
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/corrupt-projections")).size)
    }

    @Test
    fun `quarantining an empty outbox is a no-op`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")

        assertEquals(0, store.projectionOperations().quarantineCorrupt())
        assertEquals(1, store.pending(10).size)
    }

    @Test
    fun `a corrupt tombstone is quarantined instead of failing a listing`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")
        store.delete(DocId("a"), "gone", "agent")
        val tombstones = Files.createDirectories(vault.resolve("$DERIVED/tombstones"))
        // A derivable id with content that is not a valid tombstone: parsing reaches
        // readTombstone and throws, which must be contained per file.
        Files.writeString(tombstones.resolve("corrupt"), "garbage\tx\ta.md")

        val listed = store.listAll()

        // The healthy tombstone still lists, and the corrupt record is moved aside.
        assertTrue(listed.any { it.id == DocId("a") }, listed.toString())
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/corrupt-tombstones")).size)
    }

    @Test
    fun `a corrupt pending history file is quarantined instead of failing construction`() {
        // Construction walks `.tanseki/history` and decodes every record, so an
        // unguarded decode failure there turns one damaged sidecar into a vault that
        // cannot be opened at all.
        val history = Files.createDirectories(vault.resolve("$DERIVED/history"))
        Files.writeString(history.resolve("corrupt"), "not-a-pending-history")

        val store = store(FakePijul(now))

        // The store opened, and the damaged record left the recovery path.
        assertEquals(0, store.history(DocId("notes/absent")).size)
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/corrupt-history")).size)
    }

    @Test
    fun `concurrent writes to distinct documents all land without losing content`() {
        val store = store(FakePijul(now))
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(4)
        try {
            val futures =
                (1..40).map { index ->
                    executor.submit {
                        store.write(doc("notes/cw-$index", "body-$index"), "m", "agent")
                    }
                }
            futures.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        repeat(40) { index ->
            assertEquals("body-${index + 1}", store.read(DocId("notes/cw-${index + 1}"))!!.content)
        }
        val listed = store.list()
        assertEquals(40, listed.size)
        assertEquals(40, listed.map { it.id }.distinct().size)
    }

    @Test
    fun `a listing skips a document file an external editor deleted`() {
        val store = store(FakePijul(now))
        val file = Files.createDirectories(vault.resolve("notes")).resolve("a.md")
        Files.writeString(file, "body")
        store.write(doc("notes/a", "body"), "m", "agent")

        // Removed behind the store's back, like an external editor deleting a note.
        Files.delete(file)

        // A listing neither throws nor invents the vanished document.
        assertTrue(store.list().isEmpty(), store.list().toString())
        assertNull(store.read(DocId("notes/a")))
    }

    @Test
    fun `reads and listings proceed while another path's pijul record is in flight`() {
        val pijul = GatedPijul(FakePijul(now))
        val store = store(pijul)
        store.write(doc("notes/b", "beta"), "m", "agent")
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            pijul.gated = true
            val writer =
                executor.submit {
                    store.write(doc("notes/a", "alpha"), "m", "agent")
                }
            assertTrue(pijul.entered.await(20, java.util.concurrent.TimeUnit.SECONDS))

            // The recording writer holds only notes/a's per-path lock, not the
            // whole-store monitor: another path's read and the store-wide listing
            // (still monitor-guarded) must not queue behind the subprocess.
            val start = System.nanoTime()
            assertEquals("beta", store.read(DocId("notes/b"))!!.content)
            assertTrue(store.list().any { it.id == DocId("notes/b") }, store.list().toString())
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(
                elapsedMs < 1_500,
                "reads behind an in-flight pijul record took ${elapsedMs}ms"
            )

            pijul.release.countDown()
            writer.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        assertEquals("alpha", store.read(DocId("notes/a"))!!.content)
    }

    @Test
    fun `completing operations while the outbox is read never reports a phantom entry`() {
        val store = store(FakePijul(now))
        repeat(12) { index -> store.write(doc("doc-$index", "content-$index"), "m", "agent") }
        val operations = store.projectionOperations()
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val completing =
                executor.submit {
                    var guard = 0
                    while (guard++ < 12) {
                        operations.pending(100).forEach(operations::complete)
                    }
                }
            val reading =
                executor.submit {
                    repeat(200) {
                        operations.pending(100)
                        operations.backlog()
                    }
                }
            completing.get(30, java.util.concurrent.TimeUnit.SECONDS)
            reading.get(30, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertEquals(0, operations.backlog().pending)
        assertEquals(0, operations.backlog().corrupt)
    }

    @Test
    fun `an empty document tombstone keeps the digest of its empty content`() {
        val store = store(FakePijul(now))
        store.write(doc("a", ""), "create", "agent")
        val written = store.read(DocId("a"))!!

        store.delete(DocId("a"), "delete", "agent")
        val tombstone = store.readIncludingDeleted(DocId("a"))!!

        assertEquals("", tombstone.content)
        assertEquals(written.contentHash, tombstone.contentHash)
        assertEquals(written.contentHash, store.listAll().single().contentHash)
    }

    @Test
    fun `failed projection deliveries are recorded durably`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")
        val operation = store.pending(10).single()
        val operations = store.projectionOperations()

        operations.recordFailure(operation.copy(attempts = 1))

        assertEquals(1, store.pending(10).single().attempts)
        assertEquals(1, operations.backlog().stuck)
    }

    @Test
    fun `a dead-lettered operation stays in the outbox but leaves the delivery path`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "content"), "m", "agent")
        val operation = store.pending(10).single()
        val operations = store.projectionOperations()
        operations.recordFailure(operation)

        operations.deadLetter(store.pending(10).single())

        val backlog = operations.backlog()
        assertEquals(0, backlog.pending)
        assertEquals(0, backlog.stuck)
        assertEquals(1, backlog.deadLettered)
        assertEquals(listOf(operation.id), operations.deadLettered().map { it.id })
        assertEquals(1, store(FakePijul(now)).projectionOperations().backlog().deadLettered)
    }

    @Test
    fun `replaceEdges swaps the outgoing edge set without leaving stale edges`() {
        val store = store(FakePijul(now))
        store.upsertEdge(Edge(DocId("a"), DocId("stale"), RelTypes.References))
        store.upsertEdge(Edge(DocId("other"), DocId("a"), RelTypes.LinksTo))

        store.replaceEdges(
            DocId("a"),
            listOf(
                Edge(DocId("a"), DocId("b"), RelTypes.LinksTo),
                Edge(DocId("a"), DocId("b"), RelTypes.LinksTo, mapOf("label" to "dup"))
            )
        )

        assertEquals(listOf(DocId("b")), store.neighbors(DocId("a")).map { it.dst })
        assertEquals(1, store.neighbors(DocId("other")).size)

        store.replaceEdges(DocId("a"), emptyList())

        assertTrue(store.neighbors(DocId("a")).isEmpty())
        assertEquals(1, store.neighbors(DocId("other")).size)
    }

    @Test
    fun `edge writes stay visible under concurrent readers`() {
        val store = store(FakePijul(now))
        val sources = (1..8).map { DocId("src-$it") }
        val executor =
            java.util.concurrent.Executors
                .newFixedThreadPool(4)
        try {
            val futures =
                sources.map { source ->
                    executor.submit {
                        repeat(10) { index -> store.upsertEdge(Edge(source, DocId("dst-$index"), RelTypes.LinksTo)) }
                    }
                }
            futures.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(sources.associateWith { 10 }, sources.associateWith { store.neighbors(it).size })
        sources.forEach { source ->
            assertEquals(
                store.neighbors(source).map { it.dst.value },
                store.neighbors(source).map { it.dst.value }.sorted()
            )
        }
    }

    @Test
    fun `batch reads return documents in request order`() {
        val store = store(FakePijul(now))
        store.write(doc("a", "alpha"), "m", "agent")
        store.write(doc("b", "beta"), "m", "agent")

        assertEquals(
            listOf(DocId("b"), DocId("a")),
            store.readMany(listOf(DocId("b"), DocId("a"), DocId("b"))).map { it.id }
        )
    }

    @Test
    fun `metadata encoding separates ids that share legacy names`() {
        val store = store(FakePijul(now))
        val ids = listOf("a/b", "a__b", "目录/नोट", "目录__नोट")

        ids.forEachIndexed { index, id ->
            store.write(
                doc(id, "content-$index").copy(collection = Collection("collection-$index")),
                "add",
                "agent"
            )
            store.upsertEdge(
                Edge(DocId(id), DocId("target-$index"), RelTypes.LinksTo, mapOf("id" to id))
            )
        }

        ids.forEachIndexed { index, id ->
            val document = store.read(DocId(id))!!
            assertEquals(Collection("collection-$index"), document.collection)
            assertEquals("patch-${index + 1}", document.revision.value)
            assertEquals(DocId("target-$index"), store.neighbors(DocId(id)).single().dst)
        }
        assertEquals(ids.size, regularFiles(vault.resolve("$DERIVED/revisions")).toSet().size)
        assertEquals(ids.size, regularFiles(vault.resolve("$DERIVED/collections")).toSet().size)
        assertEquals(ids.size, regularFiles(vault.resolve("$DERIVED/edges")).toSet().size)
        assertEquals(ids.toSet(), store.pending(ids.size).map { it.documentId.value }.toSet())
    }

    @Test
    fun `tombstone metadata encoding separates ids that share legacy names`() {
        val store = store(FakePijul(now))
        val ids = listOf("deleted/a", "deleted__a", "目录/नोट", "目录__नोट")

        ids.forEachIndexed { index, id ->
            val collection = "collection-$index"
            store.write(doc(id, "content-$index").copy(collection = Collection(collection)), "add", "agent")
            store.delete(DocId(id), "gone", "agent")
            assertEquals(Collection(collection), store.historyOwner(DocId(id))!!.collection)
        }

        assertEquals(ids.size, regularFiles(vault.resolve("$DERIVED/tombstones")).toSet().size)
    }

    @Test
    fun `long ids use nested metadata paths`() {
        val id = "section/".repeat(40) + "leaf"
        val store = store(FakePijul(now))

        store.write(doc(id, "long"), "add", "agent")

        val revisions = regularFiles(vault.resolve("$DERIVED/revisions")).single()
        assertEquals("long", store.read(DocId(id))!!.content)
        assertTrue(revisions.parent != vault.resolve("$DERIVED/revisions"))
    }

    @Test
    fun `legacy metadata migrates eagerly without changing its values`() {
        val id = "legacy/a"
        val legacy = legacyName(id)
        Files.createDirectories(vault.resolve("legacy"))
        Files.writeString(vault.resolve("legacy/a.md"), "legacy")
        listOf("revisions", "edges", "collections", "projections").forEach {
            Files.createDirectories(vault.resolve("$DERIVED/$it"))
        }
        Files.writeString(vault.resolve("$DERIVED/revisions/$legacy"), "legacy-revision")
        Files.writeString(vault.resolve("$DERIVED/edges/$legacy"), "links-to\ttarget\tlabel=old")
        Files.writeString(vault.resolve("$DERIVED/collections/$legacy"), "legacy-collection")
        val operation =
            ProjectionOperation(
                id = "legacy/a|UPSERT|legacy-hash",
                documentId = DocId(id),
                revision = RevisionId("legacy-revision"),
                contentHash = "legacy-hash",
                kind = ProjectionKind.UPSERT,
                edgeVersion = "legacy-revision",
                projectionVersion = "legacy-revision",
                createdAt = now
            )
        Files.writeString(
            vault.resolve("$DERIVED/projections/legacy__a|UPSERT|legacy-hash"),
            listOf(
                operation.id,
                operation.documentId.value,
                operation.revision.value,
                operation.contentHash,
                operation.kind.name,
                operation.edgeVersion,
                operation.projectionVersion,
                operation.createdAt.toEpochMilliseconds().toString()
            ).joinToString("\t")
        )

        val store = store(FakePijul(now))

        assertEquals(RevisionId("legacy-revision"), store.read(DocId(id))!!.revision)
        assertEquals(Collection("legacy-collection"), store.read(DocId(id))!!.collection)
        assertEquals(DocId("target"), store.neighbors(DocId(id)).single().dst)
        assertEquals(operation, store.pending(1).single())
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/revisions")).size)
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/edges")).size)
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/collections")).size)
        assertEquals(1, regularFiles(vault.resolve("$DERIVED/projections")).size)

        store.complete(operation)
        assertTrue(store.pending(1).isEmpty())
    }

    @Test
    fun `legacy metadata migrates lazily when it appears after initialization`() {
        val store = store(FakePijul(now))
        val id = "lazy/a"
        val legacy = legacyName(id)
        Files.createDirectories(vault.resolve("lazy"))
        Files.writeString(vault.resolve("lazy/a.md"), "lazy")
        listOf("revisions", "edges", "collections", "projections").forEach {
            Files.createDirectories(vault.resolve("$DERIVED/$it"))
        }
        Files.writeString(vault.resolve("$DERIVED/revisions/$legacy"), "lazy-revision")
        Files.writeString(vault.resolve("$DERIVED/edges/$legacy"), "links-to\ttarget\tlabel=lazy")
        Files.writeString(vault.resolve("$DERIVED/collections/$legacy"), "lazy-collection")
        val operation =
            ProjectionOperation(
                id = "lazy/a|UPSERT|lazy-hash",
                documentId = DocId(id),
                revision = RevisionId("lazy-revision"),
                contentHash = "lazy-hash",
                kind = ProjectionKind.UPSERT,
                edgeVersion = "lazy-revision",
                projectionVersion = "lazy-revision",
                createdAt = now
            )
        Files.writeString(
            vault.resolve("$DERIVED/projections/lazy__a|UPSERT|lazy-hash"),
            listOf(
                operation.id,
                operation.documentId.value,
                operation.revision.value,
                operation.contentHash,
                operation.kind.name,
                operation.edgeVersion,
                operation.projectionVersion,
                operation.createdAt.toEpochMilliseconds().toString()
            ).joinToString("\t")
        )

        assertEquals(RevisionId("lazy-revision"), store.read(DocId(id))!!.revision)
        assertEquals(Collection("lazy-collection"), store.read(DocId(id))!!.collection)
        assertEquals(DocId("target"), store.neighbors(DocId(id)).single().dst)
        assertEquals(operation, store.pending(1).single())
        assertTrue(regularFiles(vault.resolve("$DERIVED/revisions")).any { it.fileName.toString() != legacy })
        assertTrue(regularFiles(vault.resolve("$DERIVED/projections")).any { it.fileName.toString() != legacy })
    }
}
