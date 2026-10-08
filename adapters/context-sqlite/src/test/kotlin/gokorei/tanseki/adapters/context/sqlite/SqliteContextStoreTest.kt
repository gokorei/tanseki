package gokorei.tanseki.adapters.context.sqlite

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.text.MarkdownParser
import gokorei.tanseki.core.text.MarkdownSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Instant

class SqliteContextStoreTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(
        id: String,
        content: String,
        revision: String = "rev-1",
        hash: String = "hash-$content",
        frontmatter: Frontmatter = Frontmatter(title = "T")
    ) = Document(
        id = DocId(id),
        collection = Collection("vault"),
        path = "$id.md",
        content = content,
        contentHash = hash,
        revision = RevisionId(revision),
        updatedAt = now,
        frontmatter = frontmatter
    )

    private fun open(): Pair<JdbcSqliteDriver, SqliteContextStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqliteSchema.configure(driver)
        SqliteSchema.create(driver)
        return driver to SqliteContextStore(driver, Clock { now })
    }

    /** [Future.get] wraps the store's failure, so the cause chain is searched. */
    private inline fun <reified T : Throwable> Result<*>.hasCause(): Boolean =
        generateSequence(exceptionOrNull()) { it.cause }.any { it is T }

    @Test
    fun `a cursor reaches a document past the old offset ceiling`() {
        val (driver, store) = open()
        driver.close()
        // MAX_PAGE_OFFSET is 10_000, so a document at 20_000 was previously
        // unreachable from the API. The cursor must still find it.
        val target = 20_001
        val driver2 = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SqliteSchema.configure(driver2)
        SqliteSchema.create(driver2)
        val big = SqliteContextStore(driver2, Clock { now })
        repeat(target) { index ->
            big.write(document("notes/%06d".format(index), "body"), "m", "tester")
        }

        val seen = mutableSetOf<DocId>()
        var after: DocId? = null
        var pages = 0
        while (pages++ < 5_000) {
            val page = big.listPageAfter(after = after, limit = 500)
            if (page.items.isEmpty()) break
            page.items.forEach { seen += it.id }
            after = page.items.last().id
            if (!page.hasMore) break
        }

        assertEquals(target, seen.size, "cursor walk did not cover the vault")
        assertTrue(seen.contains(DocId("notes/020000")), "the document past the offset ceiling is unreachable")
        assertEquals(target, big.listPageAfter(limit = 1).total)
        driver2.close()
    }

    @Test
    fun `readManyById returns more than a thousand ids without hitting the variable limit`() {
        val (driver, store) = open()
        try {
            val ids =
                (0 until 1_200).map { index ->
                    val id = DocId("bulk/$index")
                    store.write(document("bulk/$index", "body-$index"), "m", "tester")
                    id
                }

            val found = store.readManyById(ids)

            // SQLite is limited to 999 variables per statement, so a single
            // `IN ?` over all ids would fail; the read is chunked.
            assertEquals(1_200, found.size)
            assertEquals(ids, found.map { it.id })
        } finally {
            driver.close()
        }
    }

    @Test
    fun `restore is scoped to the document's own collection`() {
        val (driver, store) = open()
        try {
            store.write(
                document("alpha/note", "alpha body", revision = "rev-alpha")
                    .copy(collection = Collection("alpha")),
                "create",
                "tester"
            )
            store.write(
                document("beta/note", "beta body", revision = "rev-beta")
                    .copy(collection = Collection("beta")),
                "create",
                "tester"
            )
            store.delete(DocId("alpha/note"), "remove", "tester")

            // The live document in the other collection holds a different
            // derived path, so it must neither block the restore nor be
            // touched by it.
            store.restore(DocId("alpha/note"), "restore", "tester")

            assertEquals("alpha body", store.read(DocId("alpha/note"))!!.content)
            assertEquals("beta body", store.read(DocId("beta/note"))!!.content)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `two connections against one database serialize compare and swap writes`() {
        val file = Files.createTempFile("tanseki-cas-", ".db")
        val first = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}")
        val second = JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}")
        try {
            listOf(first, second).forEach {
                SqliteSchema.configure(it)
                SqliteSchema.ensure(it)
            }
            val writer = SqliteContextStore(first, Clock { now })
            val contender = SqliteContextStore(second, Clock { now })
            writer.write(document("cas", "initial", revision = "rev-1"), "create", "agent")
            val current = writer.read(DocId("cas"))!!.revision
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val writes =
                    listOf("two-a" to "rev-2", "two-b" to "rev-3").map { (content, revision) ->
                        executor.submit<Revision> {
                            start.await()
                            if (content == "two-a") {
                                writer.write(document("cas", content, revision), "edit", "agent", current)
                            } else {
                                contender.write(document("cas", content, revision), "edit", "agent", current)
                            }
                        }
                    }
                start.countDown()
                val outcomes = writes.map { runCatching { it.get(20, TimeUnit.SECONDS) } }

                assertEquals(
                    1,
                    outcomes.count { it.isSuccess },
                    outcomes.joinToString { it.exceptionOrNull()?.toString() ?: "ok" }
                )
                assertEquals(
                    1,
                    outcomes.count { it.hasCause<ConflictException>() },
                    outcomes.joinToString { it.exceptionOrNull()?.toString() ?: "ok" }
                )
                assertTrue(writer.read(DocId("cas"))!!.content in setOf("two-a", "two-b"))
                assertEquals(2, writer.history(DocId("cas")).size)
                assertEquals(
                    writer.read(DocId("cas"))!!.content,
                    contender.read(DocId("cas"))!!.content
                )
            } finally {
                executor.shutdownNow()
            }
        } finally {
            first.close()
            second.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `documents round-trip including frontmatter`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/a", "hello"), "add", "agent")
            val read = store.read(DocId("notes/a"))!!

            assertEquals("hello", read.content)
            assertEquals("hash-hello", read.contentHash)
            assertEquals(RevisionId("rev-1"), read.revision)
            assertEquals("T", read.frontmatter.title)
            assertEquals(1, store.list(Collection("vault")).size)
            assertTrue(store.capabilities().supportsTransactions)
            assertEquals(Consistency.STRONG, store.capabilities().consistency)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a verbatim-degraded frontmatter block survives a sqlite round trip`() {
        val (driver, store) = open()
        try {
            val markdown = "---\ntitle: First Title\ntitle: Second Title\n---\n\nbody\n"
            val parsed = MarkdownParser.parse(markdown)
            assertNotNull(parsed.frontmatterError, "the fixture must actually degrade, not parse")
            assertNull(parsed.frontmatter.title, "the typed view must be empty when the block is refused")
            store.write(
                document("notes/dup", markdown, revision = "rev-1", hash = "hash-dup")
                    .copy(frontmatter = parsed.frontmatter),
                "add",
                "agent"
            )

            val read = store.read(DocId("notes/dup"))!!
            assertEquals(markdown, read.content, "content must stay byte-identical")
            assertNull(read.frontmatter.title, "the typed view must stay empty for a degraded block")
            assertEquals(
                parsed.frontmatter.rawFrontmatter,
                read.frontmatter.rawFrontmatter,
                "the verbatim block was dropped by the sqlite write path"
            )
            assertTrue(read.frontmatter.rawFrontmatter!!.contains("title: Second Title"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `a body edit in library profile preserves the verbatim-degraded block`() {
        val (driver, store) = open()
        try {
            val markdown = "---\ntitle: First Title\ntitle: Second Title\n---\n\nbody\n"
            val parsed = MarkdownParser.parse(markdown)
            store.write(
                document("notes/dup", markdown, revision = "rev-1", hash = "hash-dup")
                    .copy(frontmatter = parsed.frontmatter),
                "add",
                "agent"
            )

            // A library-profile body edit re-renders through MarkdownSerializer,
            // exactly as DocumentCommandService.upsert does: the typed view is
            // empty, so only the preserved verbatim block keeps it alive.
            val read = store.read(DocId("notes/dup"))!!
            val submitted = read.content.replace("body", "edited body")
            val reparsed = MarkdownParser.parse(submitted)
            val rendered = MarkdownSerializer.render(reparsed.frontmatter, reparsed.body)
            store.write(
                read.copy(
                    content = rendered,
                    contentHash = "hash-dup-2",
                    revision = RevisionId("rev-2"),
                    frontmatter = reparsed.frontmatter
                ),
                "edit",
                "agent"
            )

            val after = store.read(DocId("notes/dup"))!!
            assertTrue(after.content.contains("edited body"))
            assertTrue(
                after.content.contains("title: First Title") && after.content.contains("title: Second Title"),
                "the body edit deleted the degraded block: ${after.content}"
            )
            assertEquals(
                parsed.frontmatter.rawFrontmatter,
                after.frontmatter.rawFrontmatter,
                "the verbatim block did not survive the body edit"
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename moves the document, its history and its edges`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/old", "body", revision = "rev-1"), "add", "agent")
            store.upsertEdge(Edge(DocId("notes/old"), DocId("other"), RelTypes.LinksTo))

            val renamed = store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent")

            assertNull(store.read(DocId("notes/old")))
            val moved = store.read(DocId("notes/new"))!!
            assertEquals("body", moved.content)
            assertEquals("notes/new.md", moved.path)
            assertEquals("hash-body", moved.contentHash)
            assertEquals(renamed.revision, moved.revision)
            // History follows the document: the original revision is still there
            // under the new id, and the move itself is recorded rather than being
            // a silent gap between two chains.
            val history = store.history(DocId("notes/new"))
            assertEquals(listOf("rev-1", renamed.revision.value), history.map { it.revision.value })
            assertTrue(history.all { it.docId == DocId("notes/new") })
            assertEquals(
                listOf(DocId("other")),
                store.neighbors(DocId("notes/new")).map { it.dst }
            )
            assertTrue(store.incomingNeighbors(DocId("other")).any { it.src == DocId("notes/new") })
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename re-points inbound wikilinks in the referring documents`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/target", "the target", revision = "rev-t"), "add", "agent")
            store.write(document("notes/source", "see [[target]] now", revision = "rev-s"), "add", "agent")
            store.upsertEdge(Edge(DocId("notes/source"), DocId("notes/target"), RelTypes.LinksTo))

            store.rename(DocId("notes/target"), DocId("folder/target"), "move", "agent")

            val source = store.read(DocId("notes/source"))!!
            assertEquals("see [[folder/target]] now", source.content)
            // The edge points at the new id without the store deriving it twice:
            // the stored edge was retargeted in the same transaction.
            assertEquals(
                listOf(DocId("folder/target")),
                store.neighbors(DocId("notes/source")).map { it.dst }
            )
            // The rewritten document got its own revision, because its content
            // changed and reusing the old id would hide that from history.
            assertEquals(
                listOf("rev-s", "relinked-rev-s"),
                store.history(DocId("notes/source")).map { it.revision.value }
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename leaves unrelated links alone`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/target", "the target", revision = "rev-t"), "add", "agent")
            store.write(document("notes/other", "unrelated", revision = "rev-o"), "add", "agent")
            store.write(
                document("notes/source", "[[other]] and [[target]]", revision = "rev-s"),
                "add",
                "agent"
            )
            store.upsertEdge(Edge(DocId("notes/source"), DocId("notes/target"), RelTypes.LinksTo))

            store.rename(DocId("notes/target"), DocId("folder/target"), "move", "agent")

            assertEquals("[[other]] and [[folder/target]]", store.read(DocId("notes/source"))!!.content)
            assertEquals("unrelated", store.read(DocId("notes/other"))!!.content)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename onto a live path is refused and changes nothing`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/old", "old body", revision = "rev-o"), "add", "agent")
            store.write(document("notes/taken", "holder body", revision = "rev-h"), "add", "agent")

            val failure =
                assertThrows(ConflictException::class.java) {
                    store.rename(DocId("notes/old"), DocId("notes/taken"), "move", "agent")
                }
            assertTrue(failure.message!!.contains("notes/taken"))

            // Nothing moved, and the holder is intact: the collision is refused
            // before any write, not detected halfway through.
            assertEquals("old body", store.read(DocId("notes/old"))!!.content)
            assertEquals("holder body", store.read(DocId("notes/taken"))!!.content)
            assertEquals(listOf("rev-o"), store.history(DocId("notes/old")).map { it.revision.value })
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename reclaims a path held only by a tombstone`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/taken", "trashed", revision = "rev-d"), "add", "agent")
            store.delete(DocId("notes/taken"), "remove", "agent")
            store.write(document("notes/old", "new arrival", revision = "rev-o"), "add", "agent")

            store.rename(DocId("notes/old"), DocId("notes/taken"), "move", "agent")

            assertEquals("new arrival", store.read(DocId("notes/taken"))!!.content)
            // The reclaimed document is live, not another tombstone: the row that
            // occupied the id was replaced, not revived.
            assertEquals(false, store.readIncludingDeleted(DocId("notes/taken"))!!.deleted)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename honours ifRevision and reports unknown ids`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/old", "body", revision = "rev-1"), "add", "agent")

            assertThrows(ConflictException::class.java) {
                store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent", RevisionId("rev-0"))
            }
            assertEquals("body", store.read(DocId("notes/old"))!!.content)

            assertThrows(NotFoundException::class.java) {
                store.rename(DocId("notes/missing"), DocId("notes/new"), "move", "agent")
            }

            store.rename(DocId("notes/old"), DocId("notes/new"), "move", "agent", RevisionId("rev-1"))
            assertEquals("body", store.read(DocId("notes/new"))!!.content)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `rename queues projections for the moved document and the relinked ones`() {
        val (driver, store) = open()
        try {
            store.write(document("notes/target", "the target", revision = "rev-t"), "add", "agent")
            store.write(document("notes/source", "see [[target]]", revision = "rev-s"), "add", "agent")
            store.upsertEdge(Edge(DocId("notes/source"), DocId("notes/target"), RelTypes.LinksTo))
            store.pending(100).forEach(store::complete)

            store.rename(DocId("notes/target"), DocId("folder/target"), "move", "agent")

            // Both the moved document and the document whose text was rewritten
            // need re-projecting; the lookup side is keyed by path.
            val queued = store.pending(100).map { it.documentId }.toSet()
            assertEquals(setOf(DocId("folder/target"), DocId("notes/source")), queued)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `writing the same content hash is idempotent`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "x"), "add", "agent")
            store.write(document("a", "x", revision = "rev-2"), "again", "agent")

            assertEquals(1, store.history(DocId("a")).size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `canonical write leaves a durable projection operation`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "content"), "m", "agent")
            val operation = store.pending(100).single()
            assertEquals(ProjectionKind.UPSERT, operation.kind)
            assertEquals("hash-content", operation.contentHash)

            store.complete(operation)
            assertTrue(store.pending(100).isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `ifRevision enforces compare and swap`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "x", revision = "rev-1"), "add", "agent")

            assertThrows(ConflictException::class.java) {
                store.write(
                    document("a", "y", revision = "rev-2", hash = "hash-y"),
                    "edit",
                    "agent",
                    ifRevision = RevisionId("wrong")
                )
            }

            // correct revision succeeds
            store.write(
                document("a", "y", revision = "rev-2", hash = "hash-y"),
                "edit",
                "agent",
                ifRevision = RevisionId("rev-1")
            )
            assertEquals(2, store.history(DocId("a")).size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `delete tombstones the document and keeps history`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "x"), "add", "agent")
            val deleted = store.delete(DocId("a"), "remove", "agent")

            assertNull(store.read(DocId("a")))
            assertTrue(store.list().isEmpty())
            assertEquals(deleted.revision, store.history(DocId("a")).last().revision)
            assertEquals(deleted.revision, store.historyOwner(DocId("a"))!!.revision)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `delete rolls back the tombstone and history when the outbox write fails`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "x"), "add", "agent")
            driver.execute(
                null,
                """
                CREATE TRIGGER reject_delete_outbox
                BEFORE INSERT ON projection_operations
                WHEN NEW.kind = 'DELETE'
                BEGIN
                    SELECT RAISE(ABORT, 'outbox failure');
                END
                """.trimIndent(),
                0
            ) {}

            assertThrows(Exception::class.java) {
                store.delete(DocId("a"), "remove", "agent")
            }

            assertEquals("x", store.read(DocId("a"))!!.content)
            assertEquals(1, store.history(DocId("a")).size)
            assertTrue(store.pending(10).none { it.kind == ProjectionKind.DELETE })
        } finally {
            driver.close()
        }
    }

    @Test
    fun `deleted paths cannot be claimed by another id`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "first", revision = "a-1"), "add", "agent")
            store.delete(DocId("a"), "remove", "agent")

            // Paths derive from ids, so no other id can take the tombstoned
            // path: the write is invalid input and the tombstone survives it.
            assertThrows(InvalidInputException::class.java) {
                store.write(
                    document("b", "second", revision = "b-1").copy(path = "a.md"),
                    "replace path",
                    "agent"
                )
            }

            assertNull(store.read(DocId("a")))
            assertNull(store.read(DocId("b")))
            assertEquals(2, store.history(DocId("a")).size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `deleted id can be recreated with compare and swap against its tombstone`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "first", revision = "rev-1"), "add", "agent")
            val deleted = store.delete(DocId("a"), "remove", "agent")

            store.write(
                document("a", "second", revision = "rev-2"),
                "recreate",
                "agent",
                ifRevision = deleted.revision
            )

            assertEquals("second", store.read(DocId("a"))!!.content)
            assertEquals(
                listOf(RevisionId("rev-1"), deleted.revision, RevisionId("rev-2")),
                store.history(DocId("a")).map { it.revision }
            )
        } finally {
            driver.close()
        }
    }

    @Test
    fun `write rejects deleted documents`() {
        val (driver, store) = open()
        try {
            assertThrows(InvalidInputException::class.java) {
                store.write(document("a", "x").copy(deleted = true), "add", "agent")
            }
            assertNull(store.read(DocId("a")))
            assertTrue(store.history(DocId("a")).isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `revision ids cannot be reused for different content`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "first", revision = "rev-1"), "add", "agent")

            assertThrows(ConflictException::class.java) {
                store.write(document("a", "second", revision = "rev-1"), "edit", "agent")
            }
            assertEquals("first", store.read(DocId("a"))!!.content)
            assertEquals(1, store.history(DocId("a")).size)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `collection ownership remains immutable across deletion`() {
        val (driver, store) = open()
        try {
            store.write(document("a", "first", revision = "rev-1"), "add", "agent")
            assertThrows(ConflictException::class.java) {
                store.write(
                    document("a", "second", revision = "rev-2").copy(collection = Collection("other")),
                    "move",
                    "agent"
                )
            }
            store.delete(DocId("a"), "remove", "agent")
            assertThrows(ConflictException::class.java) {
                store.write(
                    document("a", "recreated", revision = "rev-3").copy(collection = Collection("other")),
                    "recreate",
                    "agent"
                )
            }
            assertEquals(Collection("vault"), store.historyOwner(DocId("a"))!!.collection)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `edges upsert and neighbors filter by rel`() {
        val (driver, store) = open()
        try {
            store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo, mapOf("label" to "B")))
            store.upsertEdge(Edge(DocId("a"), DocId("c"), RelTypes.References))

            assertEquals(2, store.neighbors(DocId("a")).size)
            val links = store.neighbors(DocId("a"), RelType("links-to"))
            assertEquals(1, links.size)
            assertEquals(DocId("b"), links.single().dst)
            assertEquals("B", links.single().props["label"])
        } finally {
            driver.close()
        }
    }

    @Test
    fun `blobs are content addressed`() {
        val (driver, store) = open()
        try {
            val ref = store.putBlob("hello".toByteArray())
            assertEquals(5L, ref.size)
            assertArrayEquals("hello".toByteArray(), store.getBlob(ref))
            // same content -> same ref
            assertEquals(ref, store.putBlob("hello".toByteArray()))
        } finally {
            driver.close()
        }
    }

    @Test
    fun `blob reads reject unsupported references and corrupted bytes`() {
        val (driver, store) = open()
        try {
            val ref = store.putBlob("hello".toByteArray())
            assertThrows(InvalidInputException::class.java) {
                store.getBlob(ref.copy(algorithm = "sha512"))
            }
            driver.execute(
                null,
                "UPDATE blob_content SET bytes = X'626164' WHERE hash = '${ref.hash}'",
                0
            ) {}
            assertThrows(BlobCorruptionException::class.java) { store.getBlob(ref) }
        } finally {
            driver.close()
        }
    }

    /**
     * A write that loses the lock race must be retried, not reported as a
     * failure.
     *
     * The connections run with `busy_timeout = 0` on purpose: that removes the
     * driver's own wait, so a write attempted while another connection holds the
     * write lock fails immediately and only the store's retry can recover it.
     * Without the retry every path below fails with `SQLITE_BUSY` the moment the
     * lock is taken.
     */
    @Test
    fun `every write path survives a lost lock race`() {
        val file = Files.createTempFile("tanseki-busy-", ".db")
        val url = "jdbc:sqlite:${file.toAbsolutePath()}"
        val writer = JdbcSqliteDriver(url)
        val holder =
            DriverManager
                .getConnection(url)
                .apply { createStatement().use { it.execute("PRAGMA busy_timeout = 5000") } }
        try {
            SqliteSchema.configure(writer, busyTimeoutMillis = 0)
            SqliteSchema.ensure(writer)
            val store = SqliteContextStore(writer, Clock { now })
            store.write(document("busy/seed", "seed"), "create", "agent")

            val paths =
                listOf<Pair<String, (SqliteContextStore) -> Unit>>(
                    "replaceEdges" to
                        { target ->
                            target.replaceEdges(
                                DocId("busy/seed"),
                                listOf(Edge(DocId("busy/seed"), DocId("busy/other"), RelType("links-to")))
                            )
                        },
                    "importTransferState" to
                        { target ->
                            target.importTransferState(
                                gokorei.tanseki.core.ports
                                    .TransferState(document("busy/imported", "body"), emptyList())
                            )
                        },
                    "appendHistory" to
                        { target ->
                            target.appendHistory(
                                DocId("busy/seed"),
                                listOf(
                                    Revision(
                                        docId = DocId("busy/seed"),
                                        revision = RevisionId("rev-imported"),
                                        author = "agent",
                                        message = "imported",
                                        contentHash = "hash-imported",
                                        createdAt = now
                                    )
                                )
                            )
                        },
                    "putBlob" to { target -> target.putBlob("blob".toByteArray()) }
                )

            paths.forEach { (name, operation) ->
                holder.createStatement().use { it.execute("BEGIN EXCLUSIVE") }
                val executor = Executors.newSingleThreadExecutor()
                val started = CountDownLatch(1)
                val failure =
                    executor.submit<Throwable?> {
                        started.countDown()
                        runCatching { operation(store) }.exceptionOrNull()
                    }
                assertTrue(started.await(5, TimeUnit.SECONDS), "$name never started")
                // Long enough for the operation to hit the lock and enter its retry, and
                // short enough to be released inside the retry budget.
                Thread.sleep(200)
                holder.createStatement().use { it.execute("ROLLBACK") }
                val error = failure.get(20, TimeUnit.SECONDS)
                executor.shutdownNow()
                assertNull(error, "$name must recover from a busy lock, not fail: $error")
            }

            assertEquals(1, store.neighbors(DocId("busy/seed")).size)
            assertEquals("body", store.read(DocId("busy/imported"))?.content)
            assertEquals(2, store.history(DocId("busy/seed")).size)
        } finally {
            runCatching { holder.createStatement().use { it.execute("ROLLBACK") } }
            runCatching { holder.close() }
            writer.close()
            Files.deleteIfExists(file)
        }
    }
}
