package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.FakePijulClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A vault is foreign input.
 *
 * Obsidian creates `trailing .md` and names with embedded tabs without complaint,
 * and both are legal on macOS and Linux. Before this suite, one such file made
 * `FileContextStore`'s constructor throw, so the store never opened and the whole
 * vault read as empty — a single bad filename in a folder of thousands made the
 * user believe their notes were gone.
 */
class FileContextStoreForeignFilenameTest {
    private lateinit var root: Path
    private lateinit var store: FileContextStore

    private fun vault(vararg paths: String): FileContextStore {
        root = Files.createTempDirectory("tanseki-foreign-name")
        paths.forEach { relative ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, "# note\n")
        }
        store =
            FileContextStore(
                vault = VaultPath(root.toString()),
                pijul = FakePijulClient(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) }
            )
        return store
    }

    /** Names Obsidian creates freely that a strict [gokorei.tanseki.core.domain.DocId] refuses. */
    private val foreignNames =
        listOf(
            "notes/trailing space .md",
            "notes/tab\there.md",
            "notes/UPPER.MD"
        )

    /** Names that look hostile but are legal, and must keep working. */
    private val awkwardButLegalNames =
        listOf(
            "projects/Project Alpha.md",
            "notes/Meeting #1.md",
            "notes/100% done.md",
            "notes/C++ notes.md",
            "notes/[WIP] draft.md",
            "notes/notes on e\u0301moji \uD83C\uDF89.md",
            "a/b/c/d/e/f/g/deeply nested note.md"
        )

    @Test
    fun `one rejected filename does not hide the rest of the vault`() {
        vault("good/one.md", *foreignNames.toTypedArray())

        assertEquals(
            listOf("good/one.md"),
            store.list(null).map { it.path },
            "a file Tanseki cannot name must not delete every other document from the listing"
        )
    }

    @Test
    fun `the store opens at all`() {
        vault("good/one.md", *foreignNames.toTypedArray())

        assertTrue(Files.isDirectory(root))
        assertEquals(listOf("good/one.md"), store.list(null).map { it.path })
    }

    @Test
    fun `each rejected filename is reported rather than silently dropped`() {
        vault("good/one.md", *foreignNames.toTypedArray())

        assertEquals(
            foreignNames.sorted(),
            store.rejectedDocumentPaths().sorted(),
            "a skipped file has to be visible, or the vault is quietly lossy"
        )
    }

    @Test
    fun `legal but awkward names are all imported`() {
        vault(*awkwardButLegalNames.toTypedArray())

        assertEquals(awkwardButLegalNames.sorted(), store.list(null).map { it.path }.sorted())
        assertEquals(
            emptyList<String>(),
            store.rejectedDocumentPaths(),
            "none of these are un-importable, so none may be reported"
        )
    }

    @Test
    fun `list listPage and listAll agree on which files are importable`() {
        vault("good/one.md", "zz/two.md", *foreignNames.toTypedArray())

        val fromList =
            store
                .list(null)
                .map { it.id.value }
                .sorted()
        val fromPage =
            store
                .listPage(null, 50, 0, null)
                .items
                .map { it.id.value }
                .sorted()
        val fromCursor =
            store
                .listPageAfter(null, null, null, 50)
                .items
                .map { it.id.value }
                .sorted()
        val fromAll =
            store
                .listAll()
                .map { it.id.value }
                .sorted()

        assertEquals(listOf("good/one", "zz/two"), fromList)
        assertEquals(fromList, fromPage)
        assertEquals(fromList, fromCursor)
        assertEquals(fromList, fromAll)
    }

    @Test
    fun `the same bad name is reported once however many times the store is scanned`() {
        vault("good/one.md", *foreignNames.toTypedArray())

        repeat(3) {
            store.list(null)
            store.listPage(null, 50, 0, null)
            store.listAll()
        }

        assertEquals(foreignNames.size, store.rejectedDocumentPaths().size)
    }

    @Test
    fun `foreign tool directories are not walked as documents`() {
        vault(
            "note.md",
            "nested/other.md",
            // Deleted content in a trash directory must not read as live.
            ".trash/deleted note.md",
            // Generated and VCS working copies, not notes.
            ".tanseki/derived.md",
            ".pijul/store.md",
            ".obsidian/plugin.md"
        )

        assertEquals(
            listOf("nested/other.md", "note.md"),
            store
                .list(null)
                .map { it.path }
                .sorted()
        )
    }

    @Test
    fun `a directory named like a document is still traversed`() {
        // Guards the bookkeeping filter: Path.startsWith is component-wise, so a
        // directory whose name merely starts with a bookkeeping name is not one.
        // `.trash.md` and `notes.md` are ordinary folders to a prefix-string match
        // and must stay importable; `notes.mdx/` holds a plain `other.md` file.
        vault("notes.md/inner.md", "notes.mdx/other.md", ".trash.md/kept.md")

        assertEquals(
            listOf(".trash.md/kept.md", "notes.md/inner.md", "notes.mdx/other.md"),
            store
                .list(null)
                .map { it.path }
                .sorted()
        )
    }

    @Test
    fun `an extension-case file reads as absent exactly as the listing omits it`() {
        // On a case-insensitive filesystem notes/UPPER.md folds onto
        // notes/UPPER.MD and used to read 200 while list() omitted it; on a
        // case-sensitive one the same read was 404. Both must agree everywhere.
        vault("good/one.md", "notes/UPPER.MD")

        val id = DocId("notes/UPPER")
        assertTrue(store.list(null).none { it.id == id }, "list() must not admit the rejected name")
        assertNull(store.read(id), "read() must not fold onto the rejected file on any filesystem")
        assertTrue(store.readMany(listOf(id)).isEmpty())
        assertNull(store.historyOwner(id))
        assertTrue(store.rejectedDocumentPaths().contains("notes/UPPER.MD"))
    }

    @Test
    fun `a folded parent directory reads as absent while the exact spelling reads`() {
        vault("NOTES/lower.md")

        assertEquals(listOf(DocId("NOTES/lower")), store.list(null).map { it.id })
        assertNull(store.read(DocId("notes/lower")), "a case-folded directory must not serve another id")
        assertEquals("NOTES/lower.md", store.read(DocId("NOTES/lower"))?.path)
    }
}
