package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class DocumentCommandServiceTest {
    private val now = Instant.fromEpochSeconds(1_700_000_000)

    private class NoopLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private fun fixture(): Pair<QueryFacade, DocumentCommandService> {
        val store = InMemoryContextStore { now }
        val facade =
            QueryFacade(
                store = store,
                lookup = NoopLookup(),
                clock = Clock { now },
                io = Dispatchers.Unconfined
            )
        return facade to DocumentCommandService(facade, Clock { now })
    }

    @Test
    fun `create and update merge existing state and canonicalize bytes`() =
        runBlocking {
            val (facade, commands) = fixture()
            val created =
                commands.upsert(
                    UpsertDocumentCommand(
                        id = "notes/a",
                        content = "---\r\ntitle: input\r\n---\r\nbody\r\n",
                        collection = "docs",
                        path = "notes/a.md",
                        frontmatter = Frontmatter(title = "canonical", tags = listOf("x"))
                    )
                )
            val updated =
                commands.upsert(
                    UpsertDocumentCommand(
                        id = "notes/a",
                        content = "updated\r\n"
                    )
                )

            assertEquals("docs", created.document.collection.value)
            assertEquals("notes/a.md", created.document.path)
            assertEquals("---\ntitle: canonical\ntags: [x]\n---\nbody\n", created.document.content)
            assertEquals("updated\n", updated.document.content)
            assertEquals("docs", updated.document.collection.value)
            assertEquals("notes/a.md", updated.document.path)
            assertEquals(updated.document.contentHash, facade.get(DocId("notes/a"))?.contentHash)
            assertFalse(updated.created)
        }

    @Test
    fun `a path that does not derive from the id is rejected before writing`() {
        val (facade, commands) = fixture()

        assertThrows(InvalidInputException::class.java) {
            runBlocking { commands.upsert(UpsertDocumentCommand("notes/a", "body", path = "custom/a.md")) }
        }
        // An explicit derived path is accepted and stored as-is.
        val created =
            runBlocking { commands.upsert(UpsertDocumentCommand("notes/a", "body", path = "notes/a.md")) }

        assertEquals("notes/a.md", created.document.path)
        assertTrue(runBlocking { facade.list() }.map { it.id }.contains(DocId("notes/a")))
    }

    /**
     * Editing an imported note must not rewrite the author's properties.
     *
     * This is the path the raw-block work exists for. `upsert` parses the content
     * and re-renders it, so anything the typed view cannot represent — inline
     * lists, key order the author chose, quoting style — used to be normalized
     * away on every save. Tanseki does not write back to Obsidian, but import
     * makes Tanseki the tool people edit with, so the damage lands on the note they
     * just imported.
     *
     * No explicit `frontmatter` is passed, which is what a human editing a note
     * does. Passing one is the documented way to canonicalize, and is covered by
     * the test above.
     */
    @Test
    fun `an upsert without explicit frontmatter preserves the author's block byte for byte`() =
        runBlocking {
            val (_, commands) = fixture()
            val note =
                """
                ---
                zed: last
                aliases: [Project Alpha, PA]
                cssclasses: [wide]
                title:   spaced
                ---
                body
                """.trimIndent() + "\n"

            val result = commands.upsert(UpsertDocumentCommand(id = "notes/a", content = note))

            assertEquals(note, result.document.content)
        }

    @Test
    fun `re-saving an imported note twice is idempotent`() =
        runBlocking {
            val (_, commands) = fixture()
            val note = "---\naliases: [Project Alpha, PA]\n---\nbody\n"

            val first = commands.upsert(UpsertDocumentCommand(id = "notes/a", content = note))
            val second = commands.upsert(UpsertDocumentCommand(id = "notes/a", content = first.document.content))

            assertEquals(
                note,
                second.document.content,
                "a second save must not drift the block further"
            )
        }

    @Test
    fun `an explicit frontmatter still canonicalizes`() =
        runBlocking {
            val (_, commands) = fixture()
            val note = "---\naliases: [Project Alpha, PA]\n---\nbody\n"

            val result =
                commands.upsert(
                    UpsertDocumentCommand(
                        id = "notes/a",
                        content = note,
                        frontmatter = Frontmatter(title = "canonical")
                    )
                )

            assertEquals("---\ntitle: canonical\n---\nbody\n", result.document.content)
        }

    @Test
    fun `collection changes conflict`() {
        val (_, commands) = fixture()
        runBlocking {
            commands.upsert(UpsertDocumentCommand("notes/a", "body", collection = "one"))
        }

        assertThrows(ConflictException::class.java) {
            runBlocking { commands.upsert(UpsertDocumentCommand("notes/a", "other", collection = "two")) }
        }
    }

    @Test
    fun `invalid ids and paths fail before writing`() {
        val (facade, commands) = fixture()
        assertThrows(InvalidInputException::class.java) {
            runBlocking { commands.upsert(UpsertDocumentCommand("../a", "body")) }
        }
        assertThrows(InvalidInputException::class.java) {
            runBlocking { commands.upsert(UpsertDocumentCommand("notes/a", "body", path = "../a.md")) }
        }
        assertTrue(runBlocking { facade.list() }.isEmpty())
    }

    @Test
    fun `compare and swap is enforced by the command`() {
        val (_, commands) = fixture()
        val created = runBlocking { commands.upsert(UpsertDocumentCommand("notes/a", "one")) }

        assertThrows(ConflictException::class.java) {
            runBlocking {
                commands.upsert(UpsertDocumentCommand("notes/a", "two", ifRevision = "stale"))
            }
        }

        val updated =
            runBlocking {
                commands.upsert(
                    UpsertDocumentCommand(
                        "notes/a",
                        "two",
                        ifRevision = created.revision.revision.value
                    )
                )
            }
        assertFalse(updated.created)
    }
}
