package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * Editing must not rewrite the properties a user wrote.
 *
 * `DocumentCommandService.upsert` parses the submitted Markdown and re-renders
 * it from the typed `Frontmatter`. That typed view is a lossy index of the
 * block: it sorts keys, turns inline YAML lists into quoted strings, and cannot
 * model shapes it does not recognise. Re-rendering from it therefore destroys
 * formatting the moment a user edits a note's body — silently, and with nothing
 * in the response to say so.
 *
 * These tests are the gate. They assert on the RAW TEXT between the `---`
 * fences, not on a parsed structure, because a parsed comparison would pass
 * under canonical re-rendering and prove nothing.
 */
class FrontmatterRoundTripTest {
    private val now = Instant.fromEpochSeconds(1_700_000_000)

    private class NoopLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: gokorei.tanseki.core.ports.ContextStore) = Unit
    }

    private fun service(): Pair<QueryFacade, DocumentCommandService> {
        val facade =
            QueryFacade(
                store = InMemoryContextStore { now },
                lookup = NoopLookup(),
                clock = Clock { now },
                io = Dispatchers.Unconfined
            )
        return facade to DocumentCommandService(facade, Clock { now })
    }

    /** The text between the `---` fences, or null when there is no block. */
    private fun frontmatterBlockOf(content: String): String? {
        val lines = content.split("\n")
        if (lines.isEmpty() || lines.first().trim() != "---") return null
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" } ?: return null
        if (end < 0) return null
        return lines.subList(1, end).joinToString("\n")
    }

    /**
     * Deliberately hostile fixture: non-alphabetical key order, an inline list,
     * and a nested map. Canonical re-rendering would reorder the keys and quote
     * the list, so this test fails loudly if that behaviour ever returns.
     */
    private val hostileFrontmatter =
        """
        title: Weekly Review
        aliases: [Project Alpha, PA]
        cssclasses: [wide]
        meta:
          owner: davy
        reviewers:
          - alice
          - bob
        """.trimIndent()

    @Test
    fun `a body-only edit preserves the frontmatter block byte for byte`() =
        runBlocking {
            val (facade, service) = service()
            val original = "---\n$hostileFrontmatter\n---\n\noriginal body\n"
            service.upsert(UpsertDocumentCommand(id = "note", content = original))

            val before = facade.get(DocId("note"))!!.content
            service.upsert(UpsertDocumentCommand(id = "note", content = before.replace("original body", "edited body")))

            val after = facade.get(DocId("note"))!!.content
            assertEquals(
                hostileFrontmatter,
                frontmatterBlockOf(after),
                "the frontmatter block was rewritten by a body-only edit"
            )
            assertTrue(after.contains("edited body"))
        }

    @Test
    fun `an inline yaml list is not rewritten as a quoted string`() =
        runBlocking {
            val (facade, service) = service()
            val original = "---\naliases: [Project Alpha, PA]\n---\nbody\n"
            service.upsert(UpsertDocumentCommand(id = "note", content = original))

            service.upsert(
                UpsertDocumentCommand(id = "note", content = "---\naliases: [Project Alpha, PA]\n---\nnew body\n")
            )

            val after = facade.get(DocId("note"))!!.content
            assertFalse(
                after.contains("aliases: \"["),
                "an inline list was re-rendered as a quoted string: ${frontmatterBlockOf(after)}"
            )
            assertTrue(after.contains("aliases: [Project Alpha, PA]"))
        }

    @Test
    fun `a frontmatter block the parser cannot model is preserved rather than deleted`() =
        runBlocking {
            val (facade, service) = service()
            // A nested list under a non-`tags` key is a shape the codec does not
            // model. Historically it threw, which made the note unreadable; the
            // fix must keep it readable AND keep the block intact through a save.
            val original =
                "---\nreviewers:\n  - alice\n  - bob\ntitle: Notes\n---\nbody\n"
            service.upsert(UpsertDocumentCommand(id = "note", content = original))

            val stored = facade.get(DocId("note"))
            assertTrue(stored != null, "a note with an unsupported block must remain readable")

            service.upsert(UpsertDocumentCommand(id = "note", content = stored!!.content.replace("body", "edited")))

            val after = frontmatterBlockOf(facade.get(DocId("note"))!!.content)
            assertEquals(
                "reviewers:\n  - alice\n  - bob\ntitle: Notes",
                after,
                "an unmodelled block was rewritten instead of preserved"
            )
        }

    @Test
    fun `a document with no frontmatter gains none`() =
        runBlocking {
            val (facade, service) = service()
            service.upsert(UpsertDocumentCommand(id = "note", content = "# Just a heading\n\nbody\n"))

            val content = facade.get(DocId("note"))!!.content
            assertNull(frontmatterBlockOf(content), "a note without frontmatter gained a block")
        }

    @Test
    fun `frontmatter supplied by a client is still canonical`() =
        runBlocking {
            val (facade, service) = service()
            service.upsert(
                UpsertDocumentCommand(
                    id = "note",
                    content = "body",
                    frontmatter =
                        gokorei.tanseki.core.domain
                            .Frontmatter(title = "From Client")
                )
            )

            val content = facade.get(DocId("note"))!!.content
            assertEquals("title: \"From Client\"", frontmatterBlockOf(content))
        }

    @Test
    fun `an explicit frontmatter override wins over the parsed block`() =
        runBlocking {
            val (facade, service) = service()
            service.upsert(
                UpsertDocumentCommand(
                    id = "note",
                    content = "---\ntitle: Original\n---\nbody\n",
                    // A client supplying frontmatter is asking for it to be
                    // rendered, so the parsed block must not win.
                    frontmatter =
                        gokorei.tanseki.core.domain
                            .Frontmatter(title = "Overridden")
                )
            )

            assertEquals("title: Overridden", frontmatterBlockOf(facade.get(DocId("note"))!!.content))
        }
}
