package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.file.FileContextStore
import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.FakePijulClient
import gokorei.tanseki.testkit.Fixtures
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
 * End-to-end read-back for the Obsidian validation (5XGFTBNP): notes that
 * arrived with Obsidian-shaped frontmatter — nested lists and maps, folded
 * scalars, inline `aliases`/`cssclasses`, and one duplicate-key block the
 * parser can only preserve verbatim — must be readable through the HTTP API.
 *
 * Serves the high-fidelity fixture (`vault-obsidian`) from a vault-profile
 * [FileContextStore], which is what a vault daemon reads: this closes the
 * loop on the parser-totality work. A 400 here for a nested property list
 * would mean that fix is incomplete.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObsidianVaultReadbackTest {
    private lateinit var vault: Path
    private lateinit var store: FileContextStore

    private val http: HttpClient = HttpClient.newHttpClient()
    private var port: Int = 0
    private var server: RunningApiServer? = null

    @BeforeAll
    fun startServer() {
        vault = Files.createTempDirectory("tanseki-obsidian-readback")
        Fixtures.copyObsidianTo(vault)
        store =
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

    private fun get(path: String) =
        http.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun fetch(id: String): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port/v1/documents:get"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"id":"$id"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the hub note reads back byte-identical with its inline lists intact`() {
        val response = fetch("notes/Index")
        assertEquals(200, response.statusCode(), response.body())

        val doc = json(response.body())
        assertEquals(Fixtures.readObsidian("notes/Index.md"), doc["content"]!!.jsonPrimitive.content)
        val frontmatter = doc["frontmatter"]!!.jsonObject
        assertEquals(
            listOf("Home", "Start here"),
            frontmatter["aliases"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("wide", "dashboard"),
            frontmatter["cssclasses"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `nested maps lists and folded scalars read back as structured json`() {
        val response = fetch("notes/Project Alpha")
        assertEquals(200, response.statusCode(), response.body())

        val frontmatter = json(response.body())["frontmatter"]!!.jsonObject
        assertEquals("org/alpha", frontmatter["project"]!!.jsonObject["repo"]!!.jsonPrimitive.content)
        assertEquals(2, frontmatter["tasks"]!!.jsonArray.size)
        assertTrue(
            frontmatter["description"]!!.jsonPrimitive.content.startsWith("A folded scalar spanning two lines"),
            frontmatter.toString()
        )
    }

    @Test
    fun `a duplicate-key block is readable with its content intact and no typed view`() {
        val response = fetch("notes/Duplicate Keys")
        assertEquals(200, response.statusCode(), response.body())

        val doc = json(response.body())
        val content = doc["content"]!!.jsonPrimitive.content
        assertTrue(content.contains("title: First Title") && content.contains("title: Second Title"))
        assertTrue(doc["frontmatter"]!!.jsonObject.isEmpty(), "refused block must not invent a typed view")
    }

    @Test
    fun `a unicode id round-trips through the json body`() {
        val response = fetch("notes/Glossário — café")
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(
            Fixtures.readObsidian("notes/Glossário — café.md"),
            json(response.body())["content"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `foreign filenames are reported and unknown ids are 404`() {
        assertEquals(
            listOf("notes/UPPER.MD", "notes/trailing space .md"),
            store.rejectedDocumentPaths().sorted()
        )
        assertEquals(404, fetch("notes/does-not-exist").statusCode())
        assertEquals(
            404,
            fetch("notes/UPPER").statusCode(),
            "notes/UPPER.MD is rejected by the scanner, so its folded id must 404 " +
                "on every filesystem instead of reading 200 where the OS folds case"
        )
    }
}
