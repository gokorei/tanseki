package gokorei.tanseki.service.api

import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A credential scoped to several collections searches them together, and `offset`
 * is a position in that *global* ranking. Fetching each collection with the
 * caller's offset skips that many hits in every collection, so a page past the
 * first silently drops globally-ranked documents; this pins the merged paging.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchPaginationTest {
    private val key = "scoped-search-key"
    private val http: HttpClient = HttpClient.newHttpClient()
    private var port = 0
    private var server: RunningApiServer? = null

    /** A hit and the collection it belongs to, since [Hit] itself carries no collection. */
    private data class FakeHit(val id: DocId, val collection: String, val score: Double)

    private val corpus =
        listOf(
            FakeHit(DocId("a1"), "alpha", 100.0),
            FakeHit(DocId("b1"), "beta", 90.0),
            FakeHit(DocId("c1"), "gamma", 80.0),
            FakeHit(DocId("a2"), "alpha", 70.0),
            FakeHit(DocId("b2"), "beta", 60.0),
            FakeHit(DocId("c2"), "gamma", 50.0),
            FakeHit(DocId("a3"), "alpha", 40.0),
            FakeHit(DocId("b3"), "beta", 30.0),
            FakeHit(DocId("c3"), "gamma", 20.0)
        )

    private class RankedLookup(
        private val corpus: List<FakeHit>
    ) : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> =
            corpus
                .filter { filters.collections.isEmpty() || it.collection in filters.collections }
                .sortedWith(compareByDescending<FakeHit> { it.score }.thenBy { it.id.value })
                .take(limit)
                .map { Hit(it.id, it.score) }

        override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> = emptyList()

        override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    @BeforeAll
    fun startServer() {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = RankedLookup(corpus),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val registry =
            CredentialRegistry.of(
                listOf(ApiCredential(key, "scoped", setOf("alpha", "beta", "gamma")))
            )
        val running =
            StoreApiServer.startEphemeral(
                facade = facade,
                authPolicy = ApiAuthPolicy.forRegistry(registry)
            )
        server = running
        port = running.port
    }

    @AfterAll
    fun stopServer() {
        server?.close()
    }

    @Test
    fun `a later page over several collections does not skip globally-ranked hits`() {
        val page = search(offset = 3, limit = 3)

        assertEquals(listOf("a2", "b2", "c2"), page.ids)
        assertEquals(page.ids.distinct(), page.ids, "a merged page must not return a document twice")
    }

    @Test
    fun `hasMore reflects the merged result set not per-collection windows`() {
        val page = search(offset = 3, limit = 3)

        // Nine hits in total and the window starts at three, so six remain.
        assertEquals(true, page.hasMore)
    }

    @Test
    fun `the first page is the top of the merged ranking`() {
        val page = search(offset = 0, limit = 3)

        assertEquals(listOf("a1", "b1", "c1"), page.ids)
    }

    private data class Page(val ids: List<String>, val hasMore: Boolean)

    private fun search(offset: Int, limit: Int): Page {
        val request =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/search?q=x&offset=$offset&limit=$limit"))
                .timeout(Duration.ofSeconds(5))
                .header(HEADER_API_KEY, key)
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        val parsed = Json.parseToJsonElement(response.body()).jsonObject
        val ids = parsed["hits"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val hasMore = parsed["hasMore"]!!.jsonPrimitive.content.toBoolean()
        return Page(ids, hasMore)
    }
}
