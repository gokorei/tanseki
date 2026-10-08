package gokorei.tanseki.adapters.lucene

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.Consistency
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.Revision
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.StoreCapabilities
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import org.apache.lucene.store.ByteBuffersDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class LuceneLookupTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(
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
        updatedAt = now,
        frontmatter = Frontmatter(title = title, tags = tags, values = extra.mapValues { TextValue(it.value) })
    )

    @Test
    fun `text search returns matching documents with a snippet`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "the quick brown fox", title = "Fox"), emptyList())
            lookup.index(doc("b", "lazy dog sleeps"), emptyList())

            val hits = lookup.searchText("fox", Filters(), 10)

            assertEquals(1, hits.size)
            assertEquals(DocId("a"), hits.single().id)
            assertTrue(hits.single().snippet!!.contains("fox"))
        }
    }

    @Test
    fun `filters narrow by collection tag and frontmatter`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "tanseki knowledge", collection = "vault", tags = listOf("api")), emptyList())
            lookup.index(
                doc(
                    "b",
                    "tanseki knowledge",
                    collection = "alternate",
                    tags = listOf("decision"),
                    extra =
                        mapOf("status" to "draft")
                ),
                emptyList()
            )

            assertEquals(2, lookup.searchText("knowledge", Filters(), 10).size)
            assertEquals(1, lookup.searchText("knowledge", Filters(collections = setOf("vault")), 10).size)
            assertEquals(DocId("b"), lookup.searchText("knowledge", Filters(tags = setOf("decision")), 10).single().id)
            assertEquals(
                DocId("b"),
                lookup
                    .searchText(
                        "knowledge",
                        Filters.fromStrings(frontmatter = mapOf("status" to "draft")),
                        10
                    ).single()
                    .id
            )
        }
    }

    @Test
    fun `typed frontmatter filters distinguish numbers and booleans`() {
        LuceneLookup().use { lookup ->
            lookup.index(
                doc("number", "typed token").copy(
                    frontmatter =
                        Frontmatter(
                            values =
                                mapOf(
                                    "priority" to NumberValue("3"),
                                    "published" to BooleanValue(false)
                                )
                        )
                ),
                emptyList()
            )
            lookup.index(
                doc("boolean", "typed token").copy(
                    frontmatter =
                        Frontmatter(
                            values =
                                mapOf(
                                    "priority" to NumberValue("4"),
                                    "published" to BooleanValue(true)
                                )
                        )
                ),
                emptyList()
            )

            val number = Filters(frontmatter = mapOf("priority" to NumberValue("3")))
            val boolean = Filters(frontmatter = mapOf("published" to BooleanValue(true)))

            assertEquals(listOf(DocId("number")), lookup.searchText("typed", number, 10).map { it.id })
            assertEquals(listOf(DocId("boolean")), lookup.searchText("typed", boolean, 10).map { it.id })
        }
    }

    @Test
    fun `a filter value reaches the value it resolves to and only that one`() {
        // Both directions, because a one-directional assertion passes against a filter
        // that matches everything. `pr` is the number 42 in one document and the string
        // "42" in the other, so every unquoted filter has to miss one of them.
        //
        // The zero-padding row is the one that shows this is parsing and not substring
        // matching: `0042` shares every digit with `42` and must still miss, which is
        // exactly why a caller whose value is a string quotes it on the wire.
        LuceneLookup().use { lookup ->
            lookup.index(
                doc("n", "typed token").copy(
                    frontmatter =
                        frontmatterOf(
                            "pr" to NumberValue("42"),
                            "flag" to BooleanValue(true),
                            "ver" to TextValue("1.2.3"),
                            "padded" to TextValue("0042")
                        )
                ),
                emptyList()
            )
            lookup.index(
                doc("s", "typed token").copy(
                    frontmatter =
                        frontmatterOf(
                            "pr" to TextValue("42"),
                            "flag" to TextValue("true"),
                            "ver" to TextValue("1.2.3"),
                            "padded" to TextValue("0042")
                        )
                ),
                emptyList()
            )

            fun hitsFor(vararg filters: Pair<String, String>) =
                lookup
                    .searchText("", Filters.fromStrings(frontmatter = filters.toMap()), 10)
                    .map { it.id }

            assertEquals(listOf(DocId("n")), hitsFor("pr" to "42"), "a bare 42 is the number")
            assertEquals(emptyList<DocId>(), hitsFor("pr" to "7"), "a number that is not stored")
            assertEquals(listOf(DocId("s")), hitsFor("pr" to "\"42\""), "a quoted 42 is the string")
            assertEquals(emptyList<DocId>(), hitsFor("pr" to "0042"), "0042 is a string, not 42")
            assertEquals(
                listOf(DocId("n"), DocId("s")),
                hitsFor("padded" to "0042"),
                "and unquoted it reaches both documents that store the string 0042"
            )
            assertEquals(listOf(DocId("n")), hitsFor("flag" to "true"), "a bare true is the boolean")
            assertEquals(emptyList<DocId>(), hitsFor("flag" to "false"))
            assertEquals(listOf(DocId("s")), hitsFor("flag" to "\"true\""), "a quoted true is the string")
            assertEquals(listOf(DocId("n"), DocId("s")), hitsFor("ver" to "1.2.3"), "a version is text")
            assertEquals(listOf(DocId("n")), hitsFor("pr" to "42", "flag" to "true"))
        }
    }

    @Test
    fun `a filter value that cannot be resolved is refused rather than matched as nothing`() {
        // The point of refusing: an empty or unclosed value used to build a filter that
        // matched nothing, which reads as "no such document" instead of "you sent me a
        // broken parameter". It raises at the boundary the search is requested through.
        val empty =
            assertThrows(InvalidInputException::class.java) {
                Filters.fromStrings(frontmatter = mapOf("pr" to ""))
            }
        val unclosed =
            assertThrows(InvalidInputException::class.java) {
                Filters.fromStrings(frontmatter = mapOf("pr" to "\"42"))
            }

        assertEquals("frontmatter filter 'pr=' has no value; write \"\" for the empty string", empty.message)
        assertEquals(
            "frontmatter filter 'pr=\"42' has an unterminated quoted value; close the quote",
            unclosed.message
        )
    }

    private fun frontmatterOf(vararg values: Pair<String, FrontmatterValue>) =
        Frontmatter(values = values.toMap())

    @Test
    fun `a blank query with filters enumerates what matches the facets`() {
        // An equality filter has to be usable on its own. `fm=repo=org/repo` asked
        // in prose is "which documents have this property", and a filter that only
        // narrows a text query cannot answer it.
        //
        // Meilisearch already treats an empty `q` as match-all, so this is also
        // what removes a profile-dependent answer: the same request used to return
        // nothing on vault/library and matches on server.
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "alpha", extra = mapOf("repo" to "org/one")), emptyList())
            lookup.index(doc("b", "beta", extra = mapOf("repo" to "org/two")), emptyList())
            lookup.index(doc("c", "gamma"), emptyList())

            val filter = Filters(frontmatter = mapOf("repo" to TextValue("org/one")))

            assertEquals(listOf(DocId("a")), lookup.searchText("", filter, 10).map { it.id })
            assertEquals(listOf(DocId("a")), lookup.searchText("   ", filter, 10).map { it.id })
            // A typed filter narrows the same way, which is the payoff of the typed
            // frontmatter model: the comparison is expressible without a text query.
            lookup.index(
                doc("n", "numbered", extra = mapOf("pr" to "1")).copy(
                    frontmatter = Frontmatter(values = mapOf("pr" to NumberValue("1")))
                ),
                emptyList()
            )
            assertEquals(
                listOf(DocId("n")),
                lookup.searchText("", Filters(frontmatter = mapOf("pr" to NumberValue("1"))), 10).map { it.id }
            )
            // Combining both still works; the filter narrows the text match.
            assertEquals(listOf(DocId("a")), lookup.searchText("alpha", filter, 10).map { it.id })
        }
    }

    @Test
    fun `a blank query with no filter returns nothing rather than the whole index`() {
        // The asymmetry with the case above is deliberate. `fm` alone is a
        // question, and this is not; enumerating every document belongs to
        // `documents:list`, and a mistyped parameter must not look like a
        // successful empty result.
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "alpha"), emptyList())
            lookup.index(doc("b", "beta"), emptyList())

            assertEquals(emptyList<DocId>(), lookup.searchText("", Filters(), 10).map { it.id })
        }
    }

    @Test
    fun `a filter-only result is bounded by limit`() {
        // Enumerating is the one path where the result set is not narrowed by a
        // term, so `limit` is the only thing bounding it.
        LuceneLookup().use { lookup ->
            repeat(12) { index -> lookup.index(doc("d$index", "body $index", extra = mapOf("k" to "v")), emptyList()) }

            val filter = Filters(frontmatter = mapOf("k" to TextValue("v")))

            assertEquals(12, lookup.searchText("", filter, 100).size)
            assertEquals(3, lookup.searchText("", filter, 3).size)
        }
    }

    @Test
    fun `knn vector search returns the nearest chunk's document`() {
        LuceneLookup().use { lookup ->
            val a = doc("a", "alpha")
            val b = doc("b", "beta")
            lookup.index(a, emptyList())
            lookup.index(b, emptyList())
            lookup.upsertVector(a, 0, floatArrayOf(1f, 0f, 0f))
            lookup.upsertVector(b, 0, floatArrayOf(0f, 1f, 0f))

            val hits = lookup.searchVector(floatArrayOf(0.9f, 0.1f, 0f), Filters(), 5)

            assertEquals(DocId("a"), hits.first().id)
        }
    }

    @Test
    fun `vector search applies the same collection tag and frontmatter filters`() {
        LuceneLookup().use { lookup ->
            val allowed =
                doc(
                    "a",
                    "alpha",
                    collection = "vault",
                    tags = listOf("api"),
                    extra = mapOf("status" to "draft")
                )
            val denied =
                doc(
                    "b",
                    "beta",
                    collection = "alternate",
                    tags = listOf("decision"),
                    extra = mapOf("status" to "published")
                )
            lookup.index(allowed, emptyList())
            lookup.index(denied, emptyList())
            lookup.writeVectors(allowed, "fake", listOf(floatArrayOf(1f, 0f)))
            lookup.writeVectors(denied, "fake", listOf(floatArrayOf(1f, 0f)))

            val hits =
                lookup.searchVector(
                    floatArrayOf(1f, 0f),
                    Filters.fromStrings(
                        collections = setOf("vault"),
                        tags = setOf("api"),
                        frontmatter = mapOf("status" to "draft")
                    ),
                    5
                )

            assertEquals(listOf(DocId("a")), hits.map { it.id })
        }
    }

    @Test
    fun `reindexing replaces stale vectors and removal clears them`() {
        LuceneLookup().use { lookup ->
            val document = doc("a", "alpha")
            lookup.index(document, emptyList())
            lookup.writeVectors(document, "old", listOf(floatArrayOf(1f, 0f)))
            lookup.index(document.copy(content = "updated"), emptyList())

            assertTrue(lookup.searchVector(floatArrayOf(1f, 0f), Filters(), 5).isEmpty())

            lookup.writeVectors(document, "new", listOf(floatArrayOf(0f, 1f)))
            assertEquals(1, lookup.searchVector(floatArrayOf(1f, 0f), Filters(), 5).size)

            lookup.remove(DocId("a"))

            assertTrue(lookup.searchVector(floatArrayOf(0f, 1f), Filters(), 5).isEmpty())
        }
    }

    @Test
    fun `traverse follows outgoing edges to a depth`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
            lookup.index(doc("b", "b"), listOf(Edge(DocId("b"), DocId("c"), RelTypes.LinksTo)))
            lookup.index(doc("c", "c"), emptyList())

            assertEquals(listOf(DocId("b")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))
            assertEquals(listOf(DocId("b"), DocId("c")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 2))
        }
    }

    @Test
    fun `backlinks returns the sources of inbound edges`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "a"), listOf(Edge(DocId("a"), DocId("target"), RelTypes.LinksTo)))
            lookup.index(doc("b", "b"), listOf(Edge(DocId("b"), DocId("target"), RelTypes.LinksTo)))
            lookup.index(doc("target", "target"), emptyList())

            assertEquals(setOf(DocId("a"), DocId("b")), lookup.backlinks(DocId("target")).toSet())
        }
    }

    @Test
    fun `backlinks narrows by relation`() {
        LuceneLookup().use { lookup ->
            lookup.index(
                doc("a", "a"),
                listOf(
                    Edge(DocId("a"), DocId("target"), RelTypes.LinksTo),
                    Edge(DocId("a"), DocId("target"), RelTypes.References)
                )
            )
            lookup.index(doc("target", "target"), emptyList())

            assertEquals(listOf(DocId("a")), lookup.backlinks(DocId("target"), RelTypes.LinksTo))
            assertEquals(listOf(DocId("a")), lookup.backlinks(DocId("target"), RelTypes.References))
            assertEquals(setOf(DocId("a")), lookup.backlinks(DocId("target")).toSet())
        }
    }

    @Test
    fun `backlinks on a document with no inbound edges returns empty`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "a"), emptyList())

            assertTrue(lookup.backlinks(DocId("a")).isEmpty())
        }
    }

    @Test
    fun `backlinks on a fresh empty index returns empty`() {
        LuceneLookup().use { lookup ->
            assertTrue(lookup.backlinks(DocId("a")).isEmpty())
        }
    }

    @Test
    fun `removing a source drops it from the target's backlinks`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "a"), listOf(Edge(DocId("a"), DocId("target"), RelTypes.LinksTo)))
            lookup.index(doc("target", "target"), emptyList())
            assertEquals(listOf(DocId("a")), lookup.backlinks(DocId("target")))

            lookup.remove(DocId("a"))

            assertTrue(lookup.backlinks(DocId("target")).isEmpty())
        }
    }

    @Test
    fun `backlinks is the mirror of traverse`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
            lookup.index(doc("b", "b"), emptyList())

            // traverse yields destinations, backlinks yields sources, so the
            // mirror is a membership relation rather than list equality.
            for (target in lookup.traverse(DocId("a"), RelTypes.LinksTo, 1)) {
                assertTrue(lookup.backlinks(target).contains(DocId("a")))
            }
            for (source in lookup.backlinks(DocId("a"))) {
                assertTrue(lookup.traverse(source, RelTypes.LinksTo, 1).contains(DocId("a")))
            }
            assertTrue(lookup.backlinks(DocId("a")).isEmpty())
        }
    }

    @Test
    fun `hits carry path and title so a row needs no follow-up fetch`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("notes/one", "body", title = "First Note"), emptyList())

            val hit = lookup.searchText("body", Filters(), 5).single()

            assertEquals("notes/one.md", hit.path)
            assertEquals("First Note", hit.title)
        }
    }

    @Test
    fun `a hit deep in a long document windows the snippet onto the match`() {
        LuceneLookup().use { lookup ->
            val filler = "filler line\n".repeat(60)
            lookup.index(doc("notes/deep", "# Head\n\n$filler\nneedle in the haystack\n"), emptyList())

            val hit = lookup.searchText("needle", Filters(), 5).single()
            val snippet = hit.snippet!!

            assertTrue(snippet.contains("needle"), "snippet should contain the match, was: ${snippet.take(80)}")
        }
    }

    @Test
    fun `highlight offsets index correctly into the snippet`() {
        LuceneLookup().use { lookup ->
            val filler = "filler line\n".repeat(60)
            lookup.index(doc("notes/off", "$filler\nneedle here\n"), emptyList())

            val hit = lookup.searchText("needle", Filters(), 5).single()
            val snippet = hit.snippet!!

            assertTrue(hit.highlights.isNotEmpty(), "expected at least one highlight")
            hit.highlights.forEach { range ->
                assertTrue(range.first >= 0, "start out of bounds: $range")
                assertTrue(range.last < snippet.length, "end out of bounds: $range against ${snippet.length}")
                assertEquals("needle", snippet.substring(range.first, range.last + 1))
            }
        }
    }

    @Test
    fun `a short document is returned whole`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("notes/short", "a brief note about needle things"), emptyList())

            val hit = lookup.searchText("needle", Filters(), 5).single()

            assertEquals("a brief note about needle things", hit.snippet)
        }
    }

    @Test
    fun `a cjk query returns a snippet containing the match`() {
        LuceneLookup().use { lookup ->
            val filler = "filler line\n".repeat(60)
            lookup.index(doc("notes/cjk", "# Notes\n\n$filler\n会议记录 here\n"), emptyList())

            val hit = lookup.searchText("会议", Filters(), 5).single()
            val snippet = hit.snippet!!

            assertTrue(snippet.contains("会议"), "cjk snippet should contain the match, was: ${snippet.take(80)}")
            assertTrue(hit.highlights.isNotEmpty(), "cjk hit should carry highlights")
        }
    }

    @Test
    fun `a vector hit still produces a snippet`() {
        LuceneLookup().use { lookup ->
            val filler = "filler line\n".repeat(60)
            lookup.index(doc("notes/vec", "$filler\nsome body text\n"), emptyList())
            lookup.writeVectors(doc("notes/vec", "some body text"), "m", listOf(floatArrayOf(0f, 1f)))

            val hit = lookup.searchVector(floatArrayOf(1f, 0f), Filters(), 5).single()

            assertTrue(hit.snippet.orEmpty().isNotEmpty(), "vector hit should still carry a snippet")
            // A vector match has no query term, so there is nothing to highlight.
            assertTrue(hit.highlights.isEmpty())
        }
    }

    @Test
    fun `an empty search returns no hits`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "content"), emptyList())

            assertTrue(lookup.searchText("nothingmatchesthis", Filters(), 5).isEmpty())
        }
    }

    @Test
    fun `remove drops the document from all searches`() {
        LuceneLookup().use { lookup ->
            lookup.index(doc("a", "unique-token"), emptyList())
            assertEquals(setOf(DocId("a")), lookup.indexedIds())
            assertEquals(1, lookup.searchText("unique-token", Filters(), 10).size)

            lookup.remove(DocId("a"))

            assertTrue(lookup.searchText("unique-token", Filters(), 10).isEmpty())
        }
    }

    @Test
    fun `rebuild recreates text and edges from the context store`() {
        val store = FakeStore()
        store.put(doc("a", "hello world"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
        store.put(doc("b", "second"), emptyList())

        LuceneLookup().use { lookup ->
            lookup.rebuild(store)

            assertEquals(1, lookup.searchText("hello", Filters(), 10).size)
            assertEquals(listOf(DocId("b")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))
        }
    }

    @Test
    fun `search and traverse on a fresh empty index return empty`() {
        LuceneLookup().use { lookup ->
            assertTrue(lookup.searchText("anything", Filters(), 10).isEmpty())
            assertTrue(lookup.searchVector(floatArrayOf(1f, 0f, 0f), Filters(), 5).isEmpty())
            assertTrue(lookup.traverse(DocId("a"), RelTypes.LinksTo, 2).isEmpty())
        }
    }

    private class FakeStore : ContextStore {
        private val documents = LinkedHashMap<DocId, Document>()
        private val edges = mutableMapOf<DocId, List<Edge>>()

        fun put(doc: Document, edges: List<Edge>) {
            documents[doc.id] = doc
            this.edges[doc.id] = edges
        }

        override fun read(id: DocId): Document? = documents[id]

        override fun write(doc: Document, message: String, author: String, ifRevision: RevisionId?) =
            Revision(doc.id, RevisionId("r"), author, message, doc.contentHash, doc.updatedAt)

        override fun delete(id: DocId, message: String, author: String, ifRevision: RevisionId?) =
            throw UnsupportedOperationException()

        override fun list(collection: Collection?) =
            documents.values.map {
                DocRef(it.id, it.collection, it.path, it.contentHash, it.revision)
            }

        override fun upsertEdge(edge: Edge) = Unit

        override fun removeEdges(src: DocId, rel: RelType?) = Unit

        override fun neighbors(id: DocId, rel: RelType?) = edges[id].orEmpty()

        override fun history(id: DocId) = emptyList<Revision>()

        override fun putBlob(bytes: ByteArray) = BlobRef("h", bytes.size.toLong())

        override fun getBlob(ref: BlobRef) = ByteArray(0)

        override fun capabilities() = StoreCapabilities(true, false, false, Consistency.STRONG)
    }
}
