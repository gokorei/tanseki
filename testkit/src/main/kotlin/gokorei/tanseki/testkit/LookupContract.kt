package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Lookup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/** Shared [Lookup] conformance suite for text search, filters, traversal, rebuild. */
abstract class LookupContract {
    protected abstract fun newLookup(): Lookup

    protected open fun closeLookup(lookup: Lookup) = Unit

    private var lookupRef: Lookup? = null
    protected val lookup: Lookup get() = lookupRef!!

    @BeforeEach
    fun setUpContract() {
        lookupRef = newLookup()
    }

    @AfterEach
    fun tearDownContract() {
        lookupRef?.let(::closeLookup)
        lookupRef = null
    }

    protected fun document(
        id: String,
        content: String,
        collection: String = "vault",
        tags: List<String> = emptyList(),
        title: String? = null,
        extra: Map<String, String> = emptyMap()
    ) = Document(
        id = DocId(id),
        collection = Collection(collection),
        path = "$id.md",
        content = content,
        contentHash = "hash-$id",
        revision = RevisionId("rev"),
        updatedAt = Instant.fromEpochSeconds(1_700_000_000),
        frontmatter = Frontmatter(title = title, tags = tags, values = extra.mapValues { TextValue(it.value) })
    )

    @Test
    @Suppress("FunctionNaming")
    fun `text search finds the document`() {
        lookup.index(document("a", "the quick brown fox"), emptyList())
        lookup.index(document("b", "lazy dog"), emptyList())

        val hits = lookup.searchText("fox", Filters(), 10)
        assertEquals(listOf(DocId("a")), hits.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `filters narrow by collection and tag`() {
        lookup.index(document("a", "tanseki", collection = "vault", tags = listOf("api")), emptyList())
        lookup.index(document("b", "tanseki", collection = "alternate", tags = listOf("decision")), emptyList())

        assertEquals(2, lookup.searchText("tanseki", Filters(), 10).size)
        assertEquals(1, lookup.searchText("tanseki", Filters(collections = setOf("vault")), 10).size)
        assertEquals(DocId("b"), lookup.searchText("tanseki", Filters(tags = setOf("decision")), 10).single().id)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `filters narrow by frontmatter`() {
        lookup.index(document("a", "shared token", extra = mapOf("repo" to "org/repo")), emptyList())
        lookup.index(document("b", "shared token", extra = mapOf("repo" to "org/other")), emptyList())

        val hits = lookup.searchText("token", Filters.fromStrings(frontmatter = mapOf("repo" to "org/repo")), 10)
        assertEquals(listOf(DocId("a")), hits.map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `traverse follows outgoing edges`() {
        lookup.index(document("a", "a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
        lookup.index(document("b", "b"), listOf(Edge(DocId("b"), DocId("c"), RelTypes.LinksTo)))

        assertEquals(listOf(DocId("b")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `remove drops the document`() {
        lookup.index(document("a", "uniquetoken"), emptyList())
        assertEquals(setOf(DocId("a")), lookup.indexedIds())
        lookup.remove(DocId("a"))
        assertTrue(lookup.searchText("uniquetoken", Filters(), 10).isEmpty())
        assertEquals(emptySet<DocId>(), lookup.indexedIds())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `remove drops outgoing and incoming edges`() {
        lookup.index(document("a", "a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
        lookup.index(document("b", "b"), emptyList())

        lookup.remove(DocId("b"))

        assertTrue(lookup.traverse(DocId("a"), RelTypes.LinksTo, 1).isEmpty())
    }

    @Test
    @Suppress("FunctionNaming")
    fun `rebuild recreates the index from the context store`() {
        val store = InMemoryContextStore()
        val doc = document("a", "rebuildme")
        store.write(doc, "m", "tester")

        lookup.rebuild(store)

        assertEquals(listOf(DocId("a")), lookup.searchText("rebuildme", Filters(), 10).map { it.id })
    }
}
