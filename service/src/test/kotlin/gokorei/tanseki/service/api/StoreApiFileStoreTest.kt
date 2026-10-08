package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.file.FileContextStore
import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.FakePijulClient
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Regression for BHGSJGM4: collection scoping must work end-to-end over the HTTP
 * API against the **vault-mode FileStore** (the InMemory-backed StoreApiTest
 * could not catch it, since that store already honored the collection).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreApiFileStoreTest {
    private lateinit var vault: Path

    private val http: HttpClient = HttpClient.newHttpClient()
    private var port: Int = 0
    private var server: RunningApiServer? = null

    @BeforeAll
    fun startServer() {
        vault = Files.createTempDirectory("tanseki-store-api-filestore")
        val store =
            FileContextStore(
                vault = VaultPath(vault.toString()),
                pijul = FakePijulClient(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) }
            )
        val facade =
            QueryFacade(
                store = store,
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade)
        server = running
        port = running.port
        var ready = false
        repeat(100) {
            if (!ready) {
                runCatching { if (get("/v1/health").statusCode() == 200) ready = true }
                if (!ready) Thread.sleep(50)
            }
        }
        check(ready) { "server did not become ready" }
    }

    @AfterAll
    fun stopServer() {
        server?.close()
        if (::vault.isInitialized && Files.exists(vault)) {
            Files.walk(vault).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun send(method: String, path: String, body: String? = null): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = send("GET", path)

    private fun post(path: String, body: String) = send("POST", path, body)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `non-default collection round-trips across get list search delete`() {
        val id = "alternate/org/repo/pr-1/e1"
        val upsert =
            post(
                "/v1/documents:upsert",
                """{"id":"$id","collection":"alternate","content":"separate collection","frontmatter":{"repo":"org/repo"}}"""
            )
        assertEquals(200, upsert.statusCode())
        assertEquals("alternate", json(upsert.body())["collection"]!!.jsonPrimitive.content)

        val fetched = post("/v1/documents:get", """{"id":"$id","collection":"alternate"}""")
        assertEquals(200, fetched.statusCode())
        assertEquals("alternate", json(fetched.body())["collection"]!!.jsonPrimitive.content)

        assertEquals(404, post("/v1/documents:get", """{"id":"$id","collection":"vault"}""").statusCode())

        val listing = json(get("/v1/documents?collection=alternate").body())
        assertTrue(
            listing["documents"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id },
            listing.toString()
        )

        val search = json(get("/v1/search?q=separate&collection=alternate").body())
        assertTrue(
            search["hits"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id },
            search.toString()
        )

        val deleted = post("/v1/documents:delete", """{"id":"$id","collection":"alternate"}""")
        assertEquals(200, deleted.statusCode())
        assertEquals(404, post("/v1/documents:get", """{"id":"$id","collection":"alternate"}""").statusCode())
        val history =
            json(post("/v1/documents:history", """{"id":"$id","collection":"alternate"}""").body())
        assertEquals("2", history["total"]!!.jsonPrimitive.content)
        assertEquals(
            json(deleted.body())["revision"],
            history["revisions"]!!.jsonArray.last().jsonObject["revision"]
        )
    }
}
