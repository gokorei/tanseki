package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exports the code-first OpenAPI spec by booting the real `/v1` routes on a
 * random port and fetching `/openapi.json`. Used by the `:service:exportOpenApi`
 * and `:service:checkOpenApi` Gradle tasks (the latter guards against drift).
 *
 * The routes are the source of truth; no model handles are invoked, so a
 * throwaway in-memory store/lookup is sufficient. [withSpecServer] is shared
 * with `OpenApiContractTest`, so the spec that gets committed and the spec the
 * contract test asserts on come out of exactly the same code path.
 */
fun main(args: Array<String>) {
    val output = Path.of(args.firstOrNull() ?: "docs/openapi.json")
    withSpecServer { spec ->
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.writeString(output, spec)
        println("wrote $output")
    }
}

/** Boots the `/v1` routes, hands the raw `openapi.json` body to [block], then shuts down. */
internal fun withSpecServer(block: (String) -> Unit) {
    val facade =
        QueryFacade(
            store = InMemoryContextStore(),
            lookup = LuceneLookup(),
            clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
            io = Dispatchers.Unconfined
        )
    StoreApiServer.startEphemeral(facade).use { server -> block(fetchSpec(server.port)) }
}

/** The spec as a parsed object, for contract assertions that read it structurally. */
internal fun withSpecJson(block: (JsonObject) -> Unit) {
    withSpecServer { block(Json.parseToJsonElement(it).jsonObject) }
}

internal fun fetchSpec(port: Int): String {
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder(URI("http://localhost:$port/openapi.json")).GET().build()
    repeat(200) {
        val response = runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
        if (response != null && response.statusCode() == 200) return response.body()
        Thread.sleep(50)
    }
    error("openapi.json did not become available on port $port")
}
