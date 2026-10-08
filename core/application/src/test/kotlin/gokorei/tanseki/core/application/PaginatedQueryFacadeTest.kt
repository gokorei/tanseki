package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class PaginatedQueryFacadeTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(
        id: String,
        content: String = "content of $id",
        collection: String = "vault"
    ) = Document(
        id = DocId(id),
        collection = Collection(collection),
        path = "$id.md",
        content = content,
        contentHash = "hash-$id-$content",
        revision = RevisionId("rev-$id"),
        updatedAt = now
    )

    private class ScoredLookup(private val documents: List<Document>) : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> =
            documents
                .filter { it.content.contains(q, ignoreCase = true) }
                .sortedBy { it.id.value }
                .take(limit)
                .mapIndexed { rank, document -> Hit(document.id, (documents.size - rank).toDouble()) }

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private class FailingLookup : Lookup {
        var failNextIndex = true

        override fun index(doc: Document, edges: List<Edge>) {
            if (failNextIndex) {
                failNextIndex = false
                error("lookup unavailable")
            }
        }

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private fun facade(
        store: InMemoryContextStore,
        lookup: Lookup,
        overlay: PendingOverlay = PendingOverlay()
    ) =
        QueryFacade(
            store = store,
            lookup = lookup,
            clock = Clock { now },
            io = Dispatchers.Unconfined,
            logger = TansekiLogger.Noop,
            overlay = overlay
        )

    @Test
    fun `list pages are stable, non overlapping and report the exact total`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..7).forEach { index -> store.write(doc("page/$index"), "m", "tester") }
            val facade = facade(store, ScoredLookup(emptyList()))

            val pages = (0..2).map { offset -> facade.listPage(limit = 3, offset = offset * 3) }

            assertEquals(
                (1..7).map { "page/$it" },
                pages.flatMap { page -> page.items.map { it.id.value } }
            )
            assertTrue(pages[0].hasMore)
            assertTrue(pages[1].hasMore)
            assertEquals(false, pages[2].hasMore)
            pages.forEach { page -> assertEquals(7, page.total) }
        }

    @Test
    fun `multi collection listing merges and paginates globally`() =
        runBlocking {
            val store = InMemoryContextStore()
            store.write(doc("a/1", collection = "one"), "m", "tester")
            store.write(doc("b/1", collection = "two"), "m", "tester")
            store.write(doc("c/1", collection = "two"), "m", "tester")
            val facade = facade(store, ScoredLookup(emptyList()))

            val page =
                facade.listPage(
                    collections = setOf(Collection("one"), Collection("two")),
                    limit = 10,
                    offset = 0
                )

            assertEquals(listOf("a/1", "b/1", "c/1"), page.items.map { it.id.value })
            assertEquals(3, page.total)
        }

    @Test
    fun `a cursor listing walks the whole set across pages`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..9).forEach { index -> store.write(doc("page/$index"), "m", "tester") }
            val facade = facade(store, ScoredLookup(emptyList()))

            val seen = mutableListOf<String>()
            var after: DocId? = null
            var exhausted = false
            repeat(20) {
                val page = facade.listPageAfter(after = after, limit = 4)
                if (page.items.isEmpty()) return@repeat
                seen += page.items.map { ref -> ref.id.value }
                after = page.items.last().id
                if (!page.hasMore) {
                    exhausted = true
                    return@repeat
                }
            }

            assertTrue(exhausted, "cursor listing never reported the last page")
            assertEquals((1..9).map { "page/$it" }, seen)
        }

    @Test
    fun `a cursor listing spans several collections in id order`() =
        runBlocking {
            val store = InMemoryContextStore()
            store.write(doc("a/2", collection = "one"), "m", "tester")
            store.write(doc("b/1", collection = "two"), "m", "tester")
            store.write(doc("c/1", collection = "two"), "m", "tester")
            val facade = facade(store, ScoredLookup(emptyList()))

            val first =
                facade.listPageAfter(
                    collections = setOf(Collection("one"), Collection("two")),
                    limit = 2
                )
            val second =
                facade.listPageAfter(
                    collections = setOf(Collection("one"), Collection("two")),
                    after = first.items.last().id,
                    limit = 2
                )

            assertEquals(listOf("a/2", "b/1"), first.items.map { it.id.value })
            assertEquals(listOf("c/1"), second.items.map { it.id.value })
            assertFalse(second.hasMore)
        }

    @Test
    fun `a cursor listing narrows by path prefix`() =
        runBlocking {
            val store = InMemoryContextStore()
            store.write(doc("tree/notes/a"), "m", "tester")
            store.write(doc("tree/notes/deep/b"), "m", "tester")
            store.write(doc("tree/other/c"), "m", "tester")
            val facade = facade(store, ScoredLookup(emptyList()))

            val page = facade.listPageAfter(pathPrefix = "tree/notes/", limit = 10)

            assertEquals(listOf("tree/notes/a", "tree/notes/deep/b"), page.items.map { it.id.value })
            assertEquals(2, page.total)
        }

    @Test
    fun `a cursor listing includes a pending document inside the window`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..4).forEach { index -> store.write(doc("page/$index"), "m", "tester") }
            // A lookup that never projects, so writes stay in the overlay.
            val facade = facade(store, FailingLookup())

            facade.write(doc("page/2-pending"), "m", "tester")

            val page = facade.listPageAfter(limit = 10)

            assertTrue(
                page.items.map { it.id.value }.contains("page/2-pending"),
                "a read-your-writes document must appear in a cursor listing"
            )
        }

    @Test
    fun `a pending document beyond the window is not counted twice in the total`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..5).forEach { index -> store.write(doc("page/$index"), "m", "tester") }
            val lookup = FailingLookup()
            val facade = facade(store, lookup)

            facade.write(doc("zz-pending"), "m", "tester")

            val page = facade.listPage(limit = 2, offset = 0)

            assertEquals(listOf("page/1", "page/2"), page.items.map { it.id.value })
            assertEquals(6, page.total)
            assertTrue(page.hasMore)
        }

    @Test
    fun `a pending delete leaves the listing and the total in step`() =
        runBlocking {
            val store = InMemoryContextStore()
            (1..3).forEach { index -> store.write(doc("page/$index"), "m", "tester") }
            val lookup = FailingLookup()
            val facade = facade(store, lookup)

            facade.delete(DocId("page/2"), "gone", "tester")

            val page = facade.listPage(limit = 10, offset = 0)

            assertEquals(listOf("page/1", "page/3"), page.items.map { it.id.value })
            assertEquals(2, page.total)
            assertEquals(false, page.hasMore)
        }

    @Test
    fun `a pending delete in one collection does not shrink another collection total`() =
        runBlocking {
            val store = InMemoryContextStore()
            store.write(doc("a/1", collection = "one"), "m", "tester")
            store.write(doc("b/1", collection = "two"), "m", "tester")
            val lookup = FailingLookup()
            val facade = facade(store, lookup)

            facade.delete(DocId("a/1"), "gone", "tester")

            val page = facade.listPage(collections = setOf(Collection("one"), Collection("two")), limit = 10)

            assertEquals(listOf("b/1"), page.items.map { it.id.value })
            assertEquals(1, page.total)
        }

    @Test
    fun `a batch read returns each document once in the requested order`() =
        runBlocking {
            val store = InMemoryContextStore()
            store.write(doc("order/c"), "m", "tester")
            store.write(doc("order/a"), "m", "tester")
            val facade = facade(store, ScoredLookup(emptyList()))

            val read =
                facade.getMany(
                    listOf(
                        DocId("order/c"),
                        DocId("order/a"),
                        DocId("order/c"),
                        DocId("order/missing")
                    )
                )

            assertEquals(listOf(DocId("order/c"), DocId("order/a")), read.map { it.id })
        }

    @Test
    fun `page bounds are enforced against the request limits`(): Unit =
        runBlocking {
            val facade = facade(InMemoryContextStore(), ScoredLookup(emptyList()))

            assertThrows(InvalidInputException::class.java) { runBlocking { facade.listPage(limit = 0, offset = 0) } }
            assertThrows(InvalidInputException::class.java) {
                runBlocking { facade.listPage(limit = 10_000, offset = 0) }
            }
            assertThrows(InvalidInputException::class.java) { runBlocking { facade.listPage(limit = 5, offset = -1) } }
            assertThrows(InvalidInputException::class.java) {
                runBlocking { facade.searchTextPage("q", limit = 0, offset = 0) }
            }
            assertThrows(InvalidInputException::class.java) {
                runBlocking { facade.searchHybridPage("q", limit = 5, offset = -1) }
            }
        }

    @Test
    fun `search pages walk the full ranking and return one extra hit for has more`() =
        runBlocking {
            val store = InMemoryContextStore()
            val documents = (1..5).map { doc("s/$it", "searchable $it") }
            documents.forEach { store.write(it, "m", "tester") }
            val facade = facade(store, ScoredLookup(documents))

            val first = facade.searchTextPage("searchable", limit = 2, offset = 0)
            val second = facade.searchTextPage("searchable", limit = 2, offset = 2)
            val third = facade.searchTextPage("searchable", limit = 2, offset = 4)

            assertEquals(3, first.size)
            assertEquals(3, second.size)
            assertEquals(1, third.size)
            val seen = (first + second + third).map { it.id }.distinct()
            assertEquals(5, seen.size)
        }

    @Test
    fun `history pages are ordered and bounded`() =
        runBlocking {
            val store = InMemoryContextStore()
            repeat(3) { index -> store.write(doc("h/a", "content $index"), "m", "tester") }
            val facade = facade(store, ScoredLookup(emptyList()))

            val page = facade.historyPage(DocId("h/a"), limit = 2, offset = 0)

            assertEquals(2, page.items.size)
            assertEquals(3, page.total)
            assertTrue(page.hasMore)
            assertEquals(
                store.history(DocId("h/a")).map { it.revision.value },
                (0..2)
                    .flatMap { offset ->
                        facade.historyPage(DocId("h/a"), 2, offset * 2).items
                    }.map { it.revision.value }
            )
        }
}
