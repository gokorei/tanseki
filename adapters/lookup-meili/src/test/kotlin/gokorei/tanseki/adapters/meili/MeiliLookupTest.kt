package gokorei.tanseki.adapters.meili

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.RetryPolicy
import gokorei.tanseki.testkit.InMemoryContextStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Adapter-specific behaviour: settings, edge sync on re-index, and rebuild. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "TANSEKI_RUN_MEILI_INTEGRATION", matches = "true")
class MeiliLookupIntegrationTest {
    private val baseUrl get() = "http://${meili.host}:${meili.getMappedPort(7700)}"

    private fun lookup(): Pair<String, MeiliLookup> {
        val index = "tanseki-${UUID.randomUUID().toString().take(8)}"
        return index to MeiliLookup(baseUrl = baseUrl, apiKey = "test-key", indexName = index)
    }

    private fun document(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$id",
            revision = RevisionId("rev"),
            updatedAt = Instant.fromEpochSeconds(1_700_000_000)
        )

    @Test
    fun `only searchable attributes are matched`() {
        val (_, lookup) = lookup()
        lookup.index(document("notes/secret", "banana"), emptyList())

        // `docId`/`path` are not searchable attributes.
        assertTrue(lookup.searchText("secret", Filters(), 10).isEmpty())
        assertEquals(listOf(DocId("notes/secret")), lookup.searchText("banana", Filters(), 10).map { it.id })
        lookup.close()
    }

    @Test
    fun `edges stay in sync when a document is re-indexed`() {
        val (_, lookup) = lookup()
        lookup.index(document("a", "a"), listOf(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo)))
        assertEquals(listOf(DocId("b")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))

        lookup.index(document("a", "a2"), listOf(Edge(DocId("a"), DocId("c"), RelTypes.LinksTo)))

        assertEquals(listOf(DocId("c")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))
        lookup.close()
    }

    @Test
    fun `rebuild recreates documents and edges from the store`() {
        val (_, lookup) = lookup()
        val store = InMemoryContextStore()
        store.write(document("a", "rebuildme"), "m", "tester")
        store.write(document("b", "b"), "m", "tester")
        store.upsertEdge(Edge(DocId("a"), DocId("b"), RelTypes.LinksTo))

        lookup.rebuild(store)

        assertEquals(listOf(DocId("a")), lookup.searchText("rebuildme", Filters(), 10).map { it.id })
        assertEquals(listOf(DocId("b")), lookup.traverse(DocId("a"), RelTypes.LinksTo, 1))
        lookup.close()
    }

    @Test
    fun `a blank query with filters enumerates what matches the facets`() {
        // This is the behaviour Lucene was brought into line with. Asserted here so
        // the two adapters cannot drift again: the same `fm`-only request returned
        // nothing on vault/library and matches on server, so the answer depended on
        // the profile.
        val (_, lookup) = lookup()
        lookup.index(
            document("a", "alpha").copy(frontmatter = Frontmatter(values = mapOf("repo" to TextValue("org/one")))),
            emptyList()
        )
        lookup.index(
            document("b", "beta").copy(frontmatter = Frontmatter(values = mapOf("repo" to TextValue("org/two")))),
            emptyList()
        )
        lookup.index(document("c", "gamma"), emptyList())

        val filter = Filters(frontmatter = mapOf("repo" to TextValue("org/one")))

        assertEquals(listOf(DocId("a")), lookup.searchText("", filter, 10).map { it.id })
        // Blank with no filter still means "no question asked", matching Lucene.
        assertEquals(emptyList<DocId>(), lookup.searchText("", Filters(), 10).map { it.id })
        // And the filter still narrows a text query.
        assertEquals(listOf(DocId("a")), lookup.searchText("alpha", filter, 10).map { it.id })
        lookup.close()
    }

    @Test
    fun `a typed filter reaches the value it resolves to on this adapter too`() {
        // The Lucene assertion of the same rows lives in `LuceneLookupTest` and runs
        // everywhere. This is the Meilisearch half against a real container: `fm_keys`
        // is one string equality rather than a term per field, so a resolution that
        // named a term Lucene would match is not automatically one this adapter matches.
        val (_, lookup) = lookup()
        lookup.index(
            document("notes/number", "typed token").copy(
                frontmatter = Frontmatter(values = mapOf("pr" to NumberValue("42"), "flag" to BooleanValue(true)))
            ),
            emptyList()
        )
        lookup.index(
            document("notes/string", "typed token").copy(
                frontmatter = Frontmatter(values = mapOf("pr" to TextValue("42")))
            ),
            emptyList()
        )

        assertEquals(
            listOf(DocId("notes/number")),
            lookup.searchText("", Filters.fromStrings(frontmatter = mapOf("pr" to "42")), 10).map { it.id }
        )
        assertEquals(
            emptyList<DocId>(),
            lookup.searchText("", Filters.fromStrings(frontmatter = mapOf("pr" to "7")), 10).map { it.id }
        )
        assertEquals(
            listOf(DocId("notes/string")),
            lookup.searchText("", Filters.fromStrings(frontmatter = mapOf("pr" to "\"42\"")), 10).map { it.id }
        )
        assertEquals(
            listOf(DocId("notes/number")),
            lookup.searchText("", Filters.fromStrings(frontmatter = mapOf("flag" to "true")), 10).map { it.id }
        )
        lookup.close()
    }

    @Test
    fun `index settings are applied`() =
        runBlocking {
            val (index, lookup) = lookup()
            lookup.index(document("a", "hello"), emptyList())

            val client = HttpClient(CIO)
            try {
                val body =
                    client
                        .get("$baseUrl/indexes/$index/settings") {
                            header("Authorization", "Bearer test-key")
                        }.bodyAsText()
                val settings = Json.parseToJsonElement(body).jsonObject

                assertEquals(
                    listOf("words", "typo", "proximity", "attribute", "sort", "exactness"),
                    settings["rankingRules"]!!.jsonArray.map { it.jsonPrimitive.content }
                )
                assertTrue(
                    settings["searchableAttributes"]!!
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .containsAll(listOf("title", "content", "tags"))
                )
                assertTrue(
                    settings["filterableAttributes"]!!
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .containsAll(listOf("collection", "tags", "fm_keys"))
                )
            } finally {
                client.close()
                lookup.close()
            }
        }

    companion object {
        @Container
        @JvmStatic
        val meili: GenericContainer<*> =
            GenericContainer(
                "getmeili/meilisearch:v1.15.2@sha256:fe500cf9cca05cb9f027981583f28eccf17d35d94499c1f8b7b844e7418152fc"
            ).withExposedPorts(7700)
                .withEnv("MEILI_MASTER_KEY", "test-key")
                .withEnv("MEILI_NO_ANALYTICS", "true")
                .waitingFor(Wait.forHttp("/health").forPort(7700).forStatusCode(200))
    }
}

class MeiliLookupFailureTest {
    @Test
    fun `non-2xx response surfaces a typed failure without retry`() {
        val client = fakeClient { _, _, _ -> response(400, "bad request") }

        val error = assertThrows(InvalidInputException::class.java) { search(client) }

        assertTrue(error.message.orEmpty().contains("returned 400"))
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `safe GET retries a retryable status within the bound`() {
        var documentChecks = 0
        val client =
            fakeClient { method, path, _ ->
                when {
                    method == HttpMethod.Get && path == "/indexes/tanseki" -> {
                        documentChecks++
                        if (documentChecks < 3) response(503, "unavailable") else response(200)
                    }

                    method == HttpMethod.Patch && path == "/indexes/tanseki/settings" -> {
                        task(1)
                    }

                    path == "/tasks/1" -> {
                        succeeded()
                    }

                    method == HttpMethod.Get && path == "/indexes/tanseki_edges" -> {
                        response(200)
                    }

                    method == HttpMethod.Patch && path == "/indexes/tanseki_edges/settings" -> {
                        task(2)
                    }

                    path == "/tasks/2" -> {
                        succeeded()
                    }

                    method == HttpMethod.Post && path == "/indexes/tanseki/search" -> {
                        response(200, "{\"hits\":[]}")
                    }

                    else -> {
                        error("Unexpected request: $method $path")
                    }
                }
            }

        search(client)

        assertEquals(3, documentChecks)
    }

    @Test
    fun `safe search retries a retryable status within the bound`() {
        var searches = 0
        val client =
            fakeClient { method, path, _ ->
                when {
                    method == HttpMethod.Get && path == "/indexes/tanseki" -> {
                        response(200)
                    }

                    method == HttpMethod.Get && path == "/indexes/tanseki_edges" -> {
                        response(200)
                    }

                    method == HttpMethod.Patch && path == "/indexes/tanseki/settings" -> {
                        task(1)
                    }

                    path == "/tasks/1" -> {
                        succeeded()
                    }

                    method == HttpMethod.Patch && path == "/indexes/tanseki_edges/settings" -> {
                        task(2)
                    }

                    path == "/tasks/2" -> {
                        succeeded()
                    }

                    method == HttpMethod.Post && path == "/indexes/tanseki/search" -> {
                        searches++
                        if (searches < 3) response(503, "unavailable") else response(200, "{\"hits\":[]}")
                    }

                    else -> {
                        error("Unexpected request: $method $path")
                    }
                }
            }

        search(client)

        assertEquals(3, searches)
    }

    @Test
    fun `unsafe operation does not retry a retryable status`() {
        val client =
            fakeClient { method, path, _ ->
                when {
                    method == HttpMethod.Get && path == "/indexes/tanseki" -> response(200)
                    method == HttpMethod.Get && path == "/indexes/tanseki_edges" -> response(200)
                    method == HttpMethod.Patch && path == "/indexes/tanseki/settings" -> response(503, "unavailable")
                    else -> error("Unexpected request: $method $path")
                }
            }

        assertThrows(UnavailableException::class.java) { search(client) }

        assertEquals(1, client.requests.count { it.method == HttpMethod.Patch })
    }

    @Test
    fun `cancellation is not retried`() {
        val client = fakeClient { _, _, _ -> throw CancellationException("cancelled") }

        assertThrows(CancellationException::class.java) { search(client) }

        assertEquals(1, client.requests.size)
    }

    @Test
    fun `missing task id surfaces a typed failure`() {
        val client = clientFailingAtFirstTask(taskAck = response(202, "{}"))

        val error = assertThrows(UnavailableException::class.java) { search(client) }

        assertTrue(error.message.orEmpty().contains("no taskUid"))
    }

    @Test
    fun `failed task surfaces a typed failure`() {
        val client =
            clientFailingAtFirstTask { uid ->
                response(200, "{\"uid\":$uid,\"status\":\"failed\",\"error\":{\"message\":\"rejected\"}}")
            }

        val error = assertThrows(UnavailableException::class.java) { search(client) }

        assertTrue(error.message.orEmpty().contains("task 1 failed"))
        assertTrue(error.message.orEmpty().contains("rejected"))
    }

    @Test
    fun `task timeout surfaces a typed failure after the bounded polls`() {
        val client = clientFailingAtFirstTask { response(200, "{\"uid\":1,\"status\":\"processing\"}") }

        val error = assertThrows(UnavailableException::class.java) { search(client) }

        assertTrue(error.message.orEmpty().contains("timed out after 2 polls"))
        assertEquals(2, client.requests.count { it.path == "/tasks/1" })
    }

    @Test
    fun `http timeout surfaces a typed failure after bounded safe retries`() {
        val failures =
            listOf(
                HttpRequestTimeoutException("request timed out", 1L, null),
                ConnectTimeoutException("connect timed out", null),
                SocketTimeoutException("socket timed out")
            )

        failures.forEach { failure ->
            val client = fakeClient { _, _, _ -> throw failure }

            val error = assertThrows(UnavailableException::class.java) { search(client) }

            assertTrue(error.message.orEmpty().contains("timed out"))
            assertEquals(3, client.requests.size)
        }
    }

    private fun search(client: FakeMeiliHttpClient) =
        MeiliLookup
            .withHttpClient(
                client = client,
                httpRetryPolicy = deterministicRetryPolicy(),
                taskPollAttempts = 2,
                taskPollInterval = Duration.ZERO
            ).searchText("term", Filters(), 10)

    private fun deterministicRetryPolicy(): RetryPolicy =
        RetryPolicy(
            maxAttempts = 3,
            baseDelay = 1.milliseconds,
            maxDelay = 1.milliseconds,
            jitter = 0.0
        )

    private fun clientFailingAtFirstTask(
        taskAck: MeiliHttpResponse = task(1),
        taskResponse: (Long) -> MeiliHttpResponse = { succeeded() }
    ): FakeMeiliHttpClient =
        fakeClient { method, path, _ ->
            when {
                method == HttpMethod.Get && path == "/indexes/tanseki" -> response(200)
                method == HttpMethod.Get && path == "/indexes/tanseki_edges" -> response(200)
                method == HttpMethod.Patch && path == "/indexes/tanseki/settings" -> taskAck
                path == "/tasks/1" -> taskResponse(1)
                else -> error("Unexpected request: $method $path")
            }
        }

    private fun fakeClient(
        handler: suspend (HttpMethod, String, String?) -> MeiliHttpResponse
    ) = FakeMeiliHttpClient(handler)

    private fun response(
        statusCode: Int,
        body: String = ""
    ) = MeiliHttpResponse(statusCode, body)

    private fun task(uid: Long) = response(202, "{\"taskUid\":$uid}")

    private fun succeeded() = response(200, "{\"status\":\"succeeded\"}")
}

private data class RecordedMeiliRequest(
    val method: HttpMethod,
    val path: String,
    val body: String?
)

private class FakeMeiliHttpClient(
    private val handler: suspend (HttpMethod, String, String?) -> MeiliHttpResponse
) : MeiliHttpClient {
    val requests = mutableListOf<RecordedMeiliRequest>()

    override suspend fun request(
        method: HttpMethod,
        path: String,
        body: String?
    ): MeiliHttpResponse {
        requests += RecordedMeiliRequest(method, path, body)
        return handler(method, path, body)
    }
}

/**
 * The `fm_` filter tokens, asserted without a Meilisearch.
 *
 * The rule under test is that a list is filterable by one of its elements. A
 * document whose `files` holds three paths has to answer a filter naming one of
 * them, which it cannot do if the only token is the whole list — reproducing that
 * token means reproducing the list, and no filter anyone writes looks like that.
 */
class FrontmatterFilterKeysTest {
    private fun tokens(frontmatter: Frontmatter): List<String> =
        MeiliLookup.frontmatterKeys(frontmatter).map { it.jsonPrimitive.content }

    @Test
    fun `a scalar contributes one token`() {
        val tokens = tokens(Frontmatter(values = mapOf("repo" to TextValue("org/repo"))))

        assertEquals(1, tokens.size)
        assertEquals("repo=s8:org/repo", tokens.single())
    }

    @Test
    fun `a sequence contributes the whole list and each of its elements`() {
        val frontmatter =
            Frontmatter(
                values =
                    mapOf(
                        "files" to
                            SequenceValue(listOf(TextValue("a.py"), TextValue("b.py")))
                    )
            )

        val tokens = tokens(frontmatter)

        assertEquals(3, tokens.size, tokens.toString())
        // The whole list, so a filter can still name it exactly...
        assertTrue(tokens.any { it.contains("l2:") }, tokens.toString())
        // ...and each element, so a filter can name one path.
        assertTrue("files=s4:a.py" in tokens, tokens.toString())
        assertTrue("files=s4:b.py" in tokens, tokens.toString())
    }

    @Test
    fun `a nested map contributes its scalar leaves`() {
        val frontmatter =
            Frontmatter(values = mapOf("meta" to MappingValue(mapOf("owner" to TextValue("me")))))

        val tokens = tokens(frontmatter)

        assertEquals(2, tokens.size, tokens.toString())
        assertTrue("meta=s2:me" in tokens, tokens.toString())
    }

    @Test
    fun `a document with no frontmatter values contributes nothing`() {
        assertEquals(emptyList<String>(), tokens(Frontmatter()))
    }

    /**
     * A resolved filter value names a token the document was indexed under.
     *
     * Meilisearch filters differently from Lucene — one `fm_keys = "key=term"` equality
     * instead of a `TermQuery` per field — but both compare the same `encode()` output, so
     * the two agree exactly when the resolved value names a token the document carries.
     * Asserted here without a Meilisearch so the agreement is checked on a laptop: a
     * change that resolved values differently on this path would return nothing on one
     * profile and everything on the other, and the container suite is the last place that
     * is worth finding out.
     */
    @Test
    fun `a filter resolved from text names the token the document carries`() {
        val stored =
            Frontmatter(
                values =
                    mapOf(
                        "pr" to NumberValue("42"),
                        "flag" to BooleanValue(true),
                        "repo" to TextValue("org/repo")
                    )
            )
        val tokens = tokens(stored).toSet()

        listOf("pr" to "42", "flag" to "true", "repo" to "org/repo").forEach { (key, raw) ->
            val resolved = Filters.fromStrings(frontmatter = mapOf(key to raw)).frontmatter.getValue(key)
            assertTrue("$key=${resolved.encode()}" in tokens, "$key=$raw -> ${resolved.encode()} is not indexed")
        }

        // The other half, and the one that matters: a value that resolves to something the
        // document does not carry must not name a token of it, or the filter matches the
        // wrong document. A quoted 42 is the string, and this document holds the number.
        listOf("pr" to "\"42\"", "flag" to "false", "pr" to "0042", "flag" to "TRUE").forEach { (key, raw) ->
            val resolved = Filters.fromStrings(frontmatter = mapOf(key to raw)).frontmatter.getValue(key)
            assertFalse("$key=${resolved.encode()}" in tokens, "$key=$raw -> ${resolved.encode()} is indexed")
        }
        assertEquals(
            "s2:42",
            Filters
                .fromStrings(frontmatter = mapOf("pr" to "\"42\""))
                .frontmatter
                .getValue("pr")
                .encode()
        )
    }
}
