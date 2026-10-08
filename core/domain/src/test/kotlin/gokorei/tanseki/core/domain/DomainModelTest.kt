package gokorei.tanseki.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class DomainModelTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String = "notes/a") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = "hello",
            contentHash = "sha256:abc",
            revision = RevisionId("rev-1"),
            updatedAt = now
        )

    @Test
    fun `value types validate and compare structurally`() {
        assertEquals(DocId("a"), DocId("a"))
        assertNotEquals(DocId("a"), DocId("b"))
        assertEquals(RevisionId("r"), RevisionId("r"))
        assertEquals(Collection("vault"), Collection("vault"))
        assertEquals(RelType("links-to"), RelType("links-to"))
        assertEquals("a", DocId("a").toString())
        assertThrows(IllegalArgumentException::class.java) { DocId("") }
        listOf(" /a", "a/", "a//b", "../a", "a/../b", "./a", "a/.", "a\\b", "a.md", "a\u0000b").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java, { DocId(invalid) }, invalid)
        }
        assertThrows(IllegalArgumentException::class.java) { RevisionId(" ") }
        assertThrows(IllegalArgumentException::class.java) { Collection("") }
        assertThrows(IllegalArgumentException::class.java) { RelType("") }
        assertThrows(IllegalArgumentException::class.java) { VaultPath(" ") }
    }

    @Test
    fun `the relationship vocabulary is closed and parseable`() {
        assertEquals(
            listOf("links-to", "references", "embeds", "mentions"),
            RelTypes.All.map { it.value }
        )
        RelTypes.All.forEach { rel ->
            assertEquals(rel, RelTypes.parse(rel.value), "'${rel.value}' must parse to itself")
        }
        // Frontmatter keys are edge sources, not relationship types.
        listOf("files", "repo", "pr", "jira", "author", "").forEach { key ->
            assertEquals(null, RelTypes.parse(key), "'$key' is not a relationship type")
        }
    }

    @Test
    fun `document defaults are stable`() {
        val document = doc()
        assertEquals(null, document.frontmatter.title)
        assertTrue(document.frontmatter.tags.isEmpty())
        assertFalse(document.deleted)
        assertEquals(RevisionId("rev-1"), document.revision)
    }

    @Test
    fun `revision carries normalized history`() {
        val revision =
            Revision(
                docId = DocId("notes/a"),
                revision = RevisionId("rev-2"),
                author = "agent",
                message = "edit",
                contentHash = "sha256:def",
                createdAt = now,
                deps = listOf(RevisionId("rev-1"))
            )
        assertEquals(listOf(RevisionId("rev-1")), revision.deps)
        assertEquals("agent", revision.author)
    }

    @Test
    fun `edge and docref are structural`() {
        assertEquals(
            Edge(DocId("a"), DocId("b"), RelType("links-to")),
            Edge(DocId("a"), DocId("b"), RelType("links-to"))
        )
        assertEquals(
            DocRef(DocId("a"), Collection("vault"), "a.md"),
            DocRef(DocId("a"), Collection("vault"), "a.md")
        )
    }

    @Test
    fun `blob identity is content-addressed`() {
        val ref = BlobRef(hash = "abc", size = 3)
        val a = Blob(ref, "abc".toByteArray())
        val b = Blob(ref, "abc".toByteArray())
        val c = Blob(ref, "xyz".toByteArray())

        assertEquals(a, b)
        assertTrue(a.hasSameContent(b))
        assertNotEquals(a, c)
        assertFalse(a.hasSameContent(c))
        assertEquals(3L, a.size)
    }

    @Test
    fun `embedding validates dimension and compares`() {
        val e = Embedding(DocId("a"), chunk = 0, model = "m", dim = 3, vector = floatArrayOf(1f, 2f, 3f))
        val f = Embedding(DocId("a"), chunk = 0, model = "m", dim = 3, vector = floatArrayOf(1f, 2f, 3f))
        val g = Embedding(DocId("a"), chunk = 0, model = "m", dim = 3, vector = floatArrayOf(1f, 2f, 4f))

        assertEquals(e, f)
        assertNotEquals(e, g)
        assertThrows(IllegalArgumentException::class.java) {
            Embedding(DocId("a"), chunk = 0, model = "m", dim = 3, vector = floatArrayOf(1f))
        }
    }

    @Test
    fun `capabilities and consistency are exposed`() {
        val caps = StoreCapabilities(true, true, false, Consistency.STRONG)
        assertEquals(Consistency.STRONG, caps.consistency)
        assertEquals(3, Consistency.entries.size)
    }

    @Test
    fun `error taxonomy is sealed and typed`() {
        val errors: List<TansekiException> =
            listOf(
                NotFoundException(DocId("a")),
                ConflictException(DocId("a"), "stale revision"),
                InvalidInputException("bad"),
                UnavailableException("down"),
                RateLimitedException(250)
            )
        assertEquals(5, errors.size)
        assertTrue(errors[0].message!!.contains("a"))
        assertTrue(errors[1].message!!.contains("stale"))
        assertEquals("bad", errors[2].message)
        assertEquals(250L, (errors[4] as RateLimitedException).retryAfterMillis)
    }
}
