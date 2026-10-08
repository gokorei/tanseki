package gokorei.tanseki.service.api

import gokorei.tanseki.composition.Composition
import gokorei.tanseki.composition.Compositions
import gokorei.tanseki.composition.Profile
import gokorei.tanseki.composition.TansekiConfig
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * Full `/v1` lifecycle against the **server profile**: Postgres Context Store +
 * Meilisearch Lookup, composed through [Compositions]. Skipped (not failed) when
 * Docker is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerProfileE2eTest {
    private val apiKey = "server-key"
    private val http: HttpClient = HttpClient.newHttpClient()
    private var port = 0
    private var server: RunningApiServer? = null
    private var composition: Composition? = null
    private var postgres: PostgreSQLContainer<*>? = null
    private var meili: GenericContainer<*>? = null

    @BeforeAll
    fun start() {
        assumeTrue(dockerAvailable(), "Docker is required for the Postgres + Meilisearch server profile")

        val pg =
            PostgreSQLContainer(
                DockerImageName
                    .parse("postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea")
                    .asCompatibleSubstituteFor("postgres")
            ).withDatabaseName("tanseki")
                .withUsername("tanseki")
                .withPassword("tanseki")
                .waitingFor(Wait.forListeningPort())
        pg.start()
        postgres = pg

        val search =
            GenericContainer(
                "getmeili/meilisearch:v1.15.2@sha256:fe500cf9cca05cb9f027981583f28eccf17d35d94499c1f8b7b844e7418152fc"
            ).withExposedPorts(7700)
                .withEnv("MEILI_MASTER_KEY", "meili-key")
                .withEnv("MEILI_NO_ANALYTICS", "true")
                .waitingFor(Wait.forHttp("/health").forPort(7700).forStatusCode(200))
        search.start()
        meili = search

        val config =
            TansekiConfig(
                profile = Profile.SERVER,
                path = Path.of("."),
                indexDir = Path.of("."),
                logLevel = LogLevel.INFO,
                postgresJdbcUrl = pg.jdbcUrl,
                postgresUser = pg.username,
                postgresPassword = pg.password,
                meiliUrl = "http://${search.host}:${search.getMappedPort(7700)}",
                meiliApiKey = "meili-key"
            )
        val composed = Compositions.open(config)
        composition = composed

        val facade =
            QueryFacade(
                store = composed.store,
                lookup = composed.lookup,
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade, apiKey)
        server = running
        port = running.port
        var ready = false
        repeat(100) {
            if (!ready) {
                runCatching { if (send("GET", "/v1/health", authenticated = false).statusCode() == 200) ready = true }
                if (!ready) Thread.sleep(50)
            }
        }
        check(ready) { "server did not become ready" }
    }

    @AfterAll
    fun stop() {
        runCatching { server?.close() }
        runCatching { composition?.close() }
        runCatching { postgres?.stop() }
        runCatching { meili?.stop() }
    }

    @Test
    fun `upsert get search traverse and delete over the server profile`() {
        val target = """{"id":"sp/b","content":"target note","frontmatter":{"repo":"org/repo"}}"""
        val source = """{"id":"sp/a","content":"links to [[sp/b]]"}"""
        assertEquals(200, upsert(target).statusCode())
        assertEquals(200, upsert(source).statusCode())

        val fetched = json(post("/v1/documents:get", """{"id":"sp/a"}""").body())
        assertTrue(fetched["content"]!!.jsonPrimitive.content.contains("[[sp/b]]"))

        val search = json(get("/v1/search?q=target&limit=5").body())
        assertTrue(search["hits"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "sp/b" })

        val filtered = json(get("/v1/search?q=target&fm=repo=org/repo").body())
        assertTrue(filtered["hits"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "sp/b" })

        val nothing = json(get("/v1/search?q=target&fm=repo=org/other").body())
        assertTrue(nothing["hits"]!!.jsonArray.isEmpty())

        val traversed = json(post("/v1/documents:traverse", """{"id":"sp/a","rel":"links-to","depth":2}""").body())
        assertTrue(traversed["ids"]!!.jsonArray.any { it.jsonPrimitive.content == "sp/b" })

        assertEquals(200, post("/v1/documents:delete", """{"id":"sp/b"}""").statusCode())
        assertEquals(404, post("/v1/documents:get", """{"id":"sp/b"}""").statusCode())
        val afterDelete = json(get("/v1/search?q=target&limit=5").body())
        assertFalse(afterDelete["hits"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "sp/b" })
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        authenticated: Boolean = true
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
        if (authenticated) builder.header("X-API-Key", apiKey)
        builder.method(
            method,
            body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        )
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(path: String) = send("GET", path)

    private fun post(path: String, body: String) = send("POST", path, body)

    private fun upsert(body: String) = post("/v1/documents:upsert", body)

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun dockerAvailable(): Boolean =
        runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false)
}
