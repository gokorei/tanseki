package gokorei.tanseki.adapters.plain

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
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

/**
 * The property that justifies this adapter existing at all is the byte-identical one: opening a
 * foreign directory and reading everything in it must not create or modify anything inside it.
 */
class PlainDirectoryStoreTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(relative: String, content: String): Path {
        val file = tmp.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return file
    }

    private fun store(): PlainDirectoryStore = PlainDirectoryStore(tmp)

    private fun document(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId("rev-1"),
            updatedAt = Instant.fromEpochSeconds(1_700_000_000)
        )

    @Test
    fun `every markdown document is readable through the ContextStore port`() {
        write("notes/a.md", "---\ntitle: Alpha\n---\n# Alpha\n\nSee [[notes/b]].\n")
        write("notes/b.md", "# Beta\n\nPlain body.\n")

        val store = store()

        assertEquals(setOf(DocId("notes/a"), DocId("notes/b")), store.list().map { it.id }.toSet())
        assertEquals(setOf(DocId("notes/a"), DocId("notes/b")), store.listAll().map { it.id }.toSet())

        val doc = store.read(DocId("notes/a"))!!
        assertEquals("---\ntitle: Alpha\n---\n# Alpha\n\nSee [[notes/b]].\n", doc.content)
        assertEquals("notes/a.md", doc.path)
        assertEquals(Collection("vault"), doc.collection)
        assertEquals(BlobSupport.sha256(doc.content), doc.contentHash)
        assertEquals("Alpha", doc.frontmatter.title)
        assertFalse(doc.deleted)

        assertEquals(doc, store.readIncludingDeleted(DocId("notes/a")))
        assertNull(store.read(DocId("notes/missing")))
        assertEquals(
            listOf(DocId("notes/b"), DocId("notes/a")),
            store.readMany(listOf(DocId("notes/b"), DocId("notes/a"), DocId("notes/b"))).map { it.id }
        )
    }

    @Test
    fun `obsidian trash and tool state directories are excluded from the document set`() {
        write("kept.md", "# kept\n")
        write("nested/other.md", "# other\n")
        write(".obsidian/plugin.md", "# plugin state\n")
        write(".trash/deleted note.md", "# deleted\n")
        write(".tanseki/derived.md", "# derived\n")
        write(".pijul/store.md", "# store\n")
        write(".hidden/secret.md", "# secret\n")

        val store = store()

        assertEquals(
            listOf("kept.md", "nested/other.md"),
            store.list().map { it.path }.sorted()
        )
        assertEquals(store.list().map { it.id }, store.listAll().map { it.id })
    }

    @Test
    fun `a note with unsupported YAML returns a degraded frontmatter view instead of throwing`() {
        // Nesting past the codec's depth bound is rejected before the YAML engine even runs, so this
        // block can never become modellable: the read must degrade rather than throw.
        val deep =
            buildString {
                repeat(25) { level ->
                    append("  ".repeat(level)).appendLine("l$level:")
                }
                append("  ".repeat(25)).appendLine("leaf: 1")
            }
        write("broken.md", "---\n$deep---\n# Body\n")

        val doc = store().read(DocId("broken"))!!

        assertEquals("---\n$deep---\n# Body\n", doc.content)
        assertTrue(doc.frontmatter.values.isEmpty(), "unmodellable YAML must degrade to an empty view")
        assertEquals(deep.trimEnd(), doc.frontmatter.rawFrontmatter)
    }

    @Test
    fun `mutations throw naming the adapter instead of silently succeeding`() {
        val store = store()
        val doc = document("a", "hello")

        val writes =
            listOf<() -> Unit>(
                { store.write(doc, "m", "tester") },
                { store.delete(DocId("a"), "m", "tester") },
                { store.putBlob("hello".toByteArray()) },
                { store.getBlob(BlobRef("0".repeat(64), 5)) },
                { store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)) },
                { store.removeEdges(DocId("a")) },
                { store.replaceEdges(DocId("a"), emptyList()) },
                { store.clearEdges() },
                { store.appendHistory(DocId("a"), emptyList()) }
            )

        writes.forEach { operation ->
            val error = assertThrows(UnsupportedOperationException::class.java) { operation() }
            assertTrue(
                error.message.orEmpty().contains("PlainDirectoryStore"),
                "refusal must name the adapter: '${error.message}'"
            )
        }
    }

    @Test
    fun `capabilities honestly advertise no history and no patch graph`() {
        val store = store()

        val capabilities = store.capabilities()

        assertFalse(capabilities.supportsHistory)
        assertFalse(capabilities.supportsPatchGraph)
        assertFalse(capabilities.supportsTransactions)
        assertEquals(Consistency.READ_YOUR_WRITES, capabilities.consistency)
    }

    @Test
    fun `no history is invented`() {
        write("notes/a.md", "# A\n")

        val store = store()

        assertTrue(store.history(DocId("notes/a")).isEmpty())
        assertNull(store.historyOwner(DocId("notes/a")))
        assertTrue(store.neighbors(DocId("notes/a")).isEmpty())
    }

    @Test
    fun `a filename Tanseki cannot name is skipped and reported`() {
        write("good/one.md", "# one\n")
        write("notes/trailing space .md", "# bad\n")

        val store = store()

        assertEquals(listOf("good/one.md"), store.list().map { it.path })
        assertEquals(listOf("notes/trailing space .md"), store.rejectedDocumentPaths())
    }

    @Test
    fun `the source directory is byte-identical before and after a full read walk`() {
        write("notes/a.md", "---\ntitle: A\n---\n# A\n")
        write("notes/b.md", "# B\n\nSee [[notes/a]].\n")
        write("broken.md", "---\nkey: [unclosed\n---\n# broken\n")
        write(".obsidian/plugin.md", "# plugin\n")
        write(".trash/old.md", "# old\n")

        val before = snapshot(tmp)
        val store = store()

        val refs = store.listAll()
        refs.forEach { ref ->
            store.read(ref.id)
            store.readIncludingDeleted(ref.id)
            store.neighbors(ref.id)
            store.history(ref.id)
            store.historyOwner(ref.id)
        }
        store.readMany(refs.map { it.id })
        store.capabilities()

        val after = snapshot(tmp)

        assertEquals(before.keys, after.keys, "the walk must create no files")
        before.forEach { (relative, bytes) ->
            assertArrayEquals(bytes, after[relative], "the walk must modify no file: $relative")
        }
        assertFalse(Files.exists(tmp.resolve(".tanseki")), "no derived-state directory may be created")
        assertFalse(Files.exists(tmp.resolve(".pijul")), "no VCS directory may be created")
    }

    private fun snapshot(dir: Path): Map<String, ByteArray> =
        Files.walk(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .toList()
                .associate { dir.relativize(it).toString() to Files.readAllBytes(it) }
        }
}
