package gokorei.tanseki.service

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.service.api.StoreApiServer
import gokorei.tanseki.service.mcp.LocalMcpStore
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CanonicalizationParityTest {
    private val apiKey = "parity-key"
    private val client = HttpClient.newHttpClient()
    private val json = Json

    @Test
    fun `create update and canonical bytes match HTTP and local MCP`() {
        withHarness { harness ->
            val frontmatter =
                mapOf(
                    "title" to JsonPrimitive("canonical"),
                    "tags" to buildJsonArray { add("x") }
                )
            val content = "---\r\ntitle: input\r\n---\r\nbody\r\n"
            val httpCreate = httpUpsert(harness, "notes/a", content, "docs", "notes/a.md", frontmatter)
            val localCreate =
                harness.local.upsert("notes/a", content, "docs", "notes/a.md", frontmatter, null, null, null)

            assertEquals(200, httpCreate.statusCode())
            assertEquals("true", responseValue(httpCreate, "created"))
            assertEquals("true", localCreate["created"]!!.jsonPrimitive.content)
            assertEquals(
                "---\ntitle: canonical\ntags: [x]\n---\nbody\n",
                runBlocking { harness.httpFacade.get(DocId("notes/a")) }!!.content
            )
            assertEquals("notes/a.md", runBlocking { harness.httpFacade.get(DocId("notes/a")) }!!.path)
            assertDocumentParity(harness, "notes/a")

            val httpUpdate = httpUpsert(harness, "notes/a", "updated\r\n", null, null, null)
            val localUpdate = harness.local.upsert("notes/a", "updated\r\n", null, null, null, null, null, null)
            assertEquals(200, httpUpdate.statusCode())
            assertEquals("false", responseValue(httpUpdate, "created"))
            assertEquals("false", localUpdate["created"]!!.jsonPrimitive.content)
            assertDocumentParity(harness, "notes/a")
        }
    }

    @Test
    fun `collection changes conflict identically`() {
        withHarness { harness ->
            assertEquals(200, httpUpsert(harness, "notes/a", "one", "one", null, null).statusCode())
            harness.local.upsert("notes/a", "one", "one", null, null, null, null, null)

            val httpConflict = httpUpsert(harness, "notes/a", "two", "two", null, null)
            assertEquals(409, httpConflict.statusCode())
            assertThrows(ConflictException::class.java) {
                harness.local.upsert("notes/a", "two", "two", null, null, null, null, null)
            }
            assertDocumentParity(harness, "notes/a")
        }
    }

    @Test
    fun `invalid ids and paths fail identically`() {
        withHarness { harness ->
            assertEquals(400, httpUpsert(harness, "../../outside", "body", null, null, null).statusCode())
            assertThrows(InvalidInputException::class.java) {
                harness.local.upsert("../../outside", "body", null, null, null, null, null, null)
            }
            assertEquals(400, httpUpsert(harness, "notes/a", "body", null, "../a.md", null).statusCode())
            assertThrows(InvalidInputException::class.java) {
                harness.local.upsert("notes/a", "body", null, "../a.md", null, null, null, null)
            }
            // Well-formed but not id-derived: the vault cannot store it, so the
            // seams reject it rather than recording per-profile metadata.
            assertEquals(400, httpUpsert(harness, "notes/a", "body", null, "custom/a.md", null).statusCode())
            assertThrows(InvalidInputException::class.java) {
                harness.local.upsert("notes/a", "body", null, "custom/a.md", null, null, null, null)
            }
        }
    }

    @Test
    fun `CAS succeeds with the current revision and rejects stale revisions`() {
        withHarness { harness ->
            httpUpsert(harness, "notes/a", "one", null, null, null)
            harness.local.upsert("notes/a", "one", null, null, null, null, null, null)
            val httpRevision = runBlocking { harness.httpFacade.get(DocId("notes/a")) }!!.revision.value
            val localRevision = runBlocking { harness.localFacade.get(DocId("notes/a")) }!!.revision.value

            assertEquals(409, httpUpsert(harness, "notes/a", "two", null, null, null, "stale").statusCode())
            assertThrows(ConflictException::class.java) {
                harness.local.upsert("notes/a", "two", null, null, null, null, null, "stale")
            }
            assertEquals(200, httpUpsert(harness, "notes/a", "two", null, null, null, httpRevision).statusCode())
            val localUpdated = harness.local.upsert("notes/a", "two", null, null, null, null, null, localRevision)
            assertEquals("false", localUpdated["created"]!!.jsonPrimitive.content)
            assertDocumentParity(harness, "notes/a")
        }
    }

    private fun responseValue(response: HttpResponse<String>, key: String): String {
        val responseObject = json.parseToJsonElement(response.body()).jsonObject
        return responseObject[key]!!.jsonPrimitive.content
    }

    private fun assertDocumentParity(harness: Harness, id: String) {
        val httpDocument = runBlocking { harness.httpFacade.get(DocId(id)) }
        val localDocument = runBlocking { harness.localFacade.get(DocId(id)) }
        assertNotNull(httpDocument)
        assertNotNull(localDocument)
        val http = httpDocument!!
        val local = localDocument!!
        assertEquals(http.copy(updatedAt = local.updatedAt), local)
    }

    private fun <T> withHarness(block: (Harness) -> T): T {
        val now = kotlin.time.Instant.fromEpochSeconds(1_700_000_000)
        val httpFacade = facade(now)
        val localFacade = facade(now)
        StoreApiServer.startEphemeral(httpFacade, apiKey).use { server ->
            var ready = false
            repeat(100) {
                if (!ready) {
                    ready = health(server.port)
                    if (!ready) Thread.sleep(20)
                }
            }
            check(ready)
            return block(
                Harness(
                    port = server.port,
                    httpFacade = httpFacade,
                    localFacade = localFacade,
                    local = LocalMcpStore(localFacade)
                )
            )
        }
    }

    private fun facade(now: kotlin.time.Instant): QueryFacade =
        QueryFacade(
            store = InMemoryContextStore { now },
            lookup = LuceneLookup(),
            clock = Clock { now },
            io = Dispatchers.Unconfined
        )

    private fun health(port: Int): Boolean =
        runCatching {
            client
                .send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:$port/v1/health"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                ).statusCode() == 200
        }.getOrDefault(false)

    private fun httpUpsert(
        harness: Harness,
        id: String,
        content: String,
        collection: String?,
        path: String?,
        frontmatter: Map<String, JsonElement>?,
        ifRevision: String? = null
    ): HttpResponse<String> {
        val payload =
            buildJsonObject {
                put("id", id)
                put("content", content)
                collection?.let { put("collection", it) }
                path?.let { put("path", it) }
                frontmatter?.let { put("frontmatter", JsonObject(it)) }
                ifRevision?.let { put("ifRevision", it) }
            }
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:${harness.port}/v1/documents:upsert"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private data class Harness(
        val port: Int,
        val httpFacade: QueryFacade,
        val localFacade: QueryFacade,
        val local: LocalMcpStore
    )
}
