package gokorei.tanseki.sdk.generated

import kotlinx.coroutines.Dispatchers
import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.service.api.StoreApiServer
import gokorei.tanseki.testkit.InMemoryContextStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.openapitools.client.apis.DocumentsApi
import org.openapitools.client.apis.HealthApi
import org.openapitools.client.apis.SearchApi
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.ClientException
import org.openapitools.client.models.BacklinksRequest
import org.openapitools.client.models.DeleteDocumentRequest
import org.openapitools.client.models.GetDocumentRequest
import org.openapitools.client.models.HistoryRequest
import org.openapitools.client.models.RestoreDocumentRequest
import org.openapitools.client.models.RenameDocumentRequest
import org.openapitools.client.models.TraverseRel
import org.openapitools.client.models.TraverseRequest
import org.openapitools.client.models.UpsertDocumentRequest

class GeneratedClientSmokeTest {
    @Test
    fun `generated client authenticates and completes the document lifecycle against StoreApiServer`() {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val server = StoreApiServer.startEphemeral(facade, "secret")
        val basePath = server.baseUrl
        val health = HealthApi(basePath)
        val documents = DocumentsApi(basePath)
        val search = SearchApi(basePath)
        val targetId = "generated/target"
        val sourceId = "generated/source"
        try {
            var ready = false
            repeat(100) {
                if (!ready) {
                    runCatching { health.v1HealthGet() }.onSuccess { ready = true }
                    if (!ready) Thread.sleep(50)
                }
            }
            check(ready) { "server did not become ready" }

            assertEquals("ok", health.v1HealthGet().status)

            ApiClient.apiKey["X-API-Key"] = "invalid"
            val invalid = assertThrows(ClientException::class.java) { documents.v1DocumentsGet() }
            assertEquals(401, invalid.statusCode)

            ApiClient.apiKey["X-API-Key"] = "secret"
            assertEquals(0, documents.v1DocumentsGet().total)

            val target =
                documents.v1DocumentsUpsertPost(
                    UpsertDocumentRequest(
                        id = targetId,
                        content = "target note for generated client search",
                        collection = "vault",
                        message = "create target",
                        author = "integration"
                    )
                )
            assertTrue(target.created)

            val source =
                documents.v1DocumentsUpsertPost(
                    UpsertDocumentRequest(
                        id = sourceId,
                        content = "source note links to [[$targetId]]",
                        collection = "vault",
                        frontmatter = mapOf("kind" to "integration"),
                        message = "create source",
                        author = "integration"
                    )
                )
            assertTrue(source.created)

            val fetched = documents.v1DocumentsGetPost(GetDocumentRequest(sourceId))
            assertEquals(sourceId, fetched.id)
            assertTrue(fetched.content.contains(targetId))

            val listed = documents.v1DocumentsGet()
            assertTrue(listed.documents.any { it.id == sourceId })

            val history = documents.v1DocumentsHistoryPost(HistoryRequest(sourceId))
            assertEquals(1, history.total)

            val hits = search.v1SearchGet(q = "target note")
            assertTrue(hits.hits.any { it.id == targetId })

            val traversed = documents.v1DocumentsTraversePost(TraverseRequest(sourceId, rel = TraverseRel.linksMinusTo, depth = 1))
            assertTrue(traversed.ids.contains(targetId))

            val updated =
                documents.v1DocumentsUpsertPost(
                    UpsertDocumentRequest(
                        id = sourceId,
                        content = "updated source note",
                        collection = "vault",
                        frontmatter = mapOf("kind" to "integration"),
                        message = "update source",
                        author = "integration"
                    )
                )
            assertFalse(updated.created)

            val deleted = documents.v1DocumentsDeletePost(DeleteDocumentRequest(sourceId, collection = "vault"))
            assertEquals(sourceId, deleted.id)
            val missing =
                assertThrows(ClientException::class.java) {
                    documents.v1DocumentsGetPost(GetDocumentRequest(sourceId))
                }
            assertEquals(404, missing.statusCode)
        } finally {
            ApiClient.apiKey.clear()
            server.close()
        }
    }

    /**
     * The second half of the surface, added after the first test was written:
     * backlinks, trash-and-restore, and search hits that carry something to render.
     *
     * These are three separate additions and they are tested together on purpose.
     * Each one is a case where the generated client and the daemon can disagree
     * without either noticing -- a renamed field still deserializes to null, a
     * route that moves still 404s at runtime rather than at build time, and the
     * spec can describe a field the server never populates. A smoke test that only
     * calls the endpoints and ignores the response cannot see any of that, so every
     * assertion below is on a returned value rather than on the absence of a throw.
     */
    @Test
    fun `generated client reaches backlinks, restore, and renderable search hits`() {
        val facade =
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            )
        val server = StoreApiServer.startEphemeral(facade, "secret")
        val documents = DocumentsApi(server.baseUrl)
        val search = SearchApi(server.baseUrl)
        val targetId = "generated/backlink-target"
        val sourceId = "generated/backlink-source"
        val deletedId = "generated/trashed"
        try {
            var ready = false
            repeat(100) {
                if (!ready) {
                    runCatching { HealthApi(server.baseUrl).v1HealthGet() }.onSuccess { ready = true }
                    if (!ready) Thread.sleep(50)
                }
            }
            check(ready) { "server did not become ready" }

            ApiClient.apiKey["X-API-Key"] = "secret"

            documents.v1DocumentsUpsertPost(
                UpsertDocumentRequest(
                    id = targetId,
                    content = "# Renderable\n\nthe target note body",
                    collection = "vault",
                    message = "create target",
                    author = "integration"
                )
            )
            documents.v1DocumentsUpsertPost(
                UpsertDocumentRequest(
                    id = sourceId,
                    content = "the source note links to [[$targetId]]",
                    collection = "vault",
                    message = "create source",
                    author = "integration"
                )
            )

            // Backlinks are the inverse of traverse: the existing test proves a
            // forward hop resolves, this proves the reverse lookup finds the
            // referrer. A server that derived edges but never indexed the reverse
            // would answer an empty list rather than an error.
            val backlinks = documents.v1DocumentsBacklinksPost(BacklinksRequest(id = targetId))
            assertTrue(
                backlinks.ids.contains(sourceId),
                "expected $sourceId among backlinks of $targetId, got ${backlinks.ids}"
            )

            // A hit has to be renderable, not merely present: snippet, path and
            // title are what a client draws. Nulls here are the failure mode, since
            // every field is nullable in the generated model.
            val hits = search.v1SearchGet(q = "target note body")
            val hit = hits.hits.firstOrNull { it.id == targetId }
            check(hit != null) { "expected a hit for $targetId in ${hits.hits.map { it.id }}" }
            assertNotNull(hit.snippet, "a hit must carry a snippet to render")
            assertNotNull(hit.path, "a hit must carry a path to render")
            assertFalse(hit.snippet.isNullOrBlank(), "a snippet that is blank cannot render")

            // Highlights are offsets into the snippet, and only exist when the
            // snippet had to be windowed onto the match (`LuceneLookup` emits
            // `windowed?.highlights`, so a match already near the top has none).
            // So this asserts the invariant that holds either way -- offsets that
            // address real characters of the snippet it claims to index -- rather
            // than that highlights are present, which would be a claim about
            // windowing rather than about the client.
            hit.highlights.forEach { span ->
                val text = hit.snippet.orEmpty()
                assertTrue(span.start >= 0, "highlight starts before the snippet: ${span.start}")
                assertTrue(span.end > span.start, "highlight $span is not a forward range")
                assertTrue(
                    span.end <= text.length,
                    "highlight $span runs past the ${text.length}-character snippet it indexes"
                )
            }

            // Delete, then restore. Restore is the assertion that matters: a
            // generated client with a stale route would 404 here, and one that
            // deserializes the wrong field names would come back with an empty id
            // rather than throwing.
            documents.v1DocumentsUpsertPost(
                UpsertDocumentRequest(
                    id = deletedId,
                    content = "this note will be deleted and then restored",
                    collection = "vault",
                    message = "create doomed",
                    author = "integration"
                )
            )
            documents.v1DocumentsDeletePost(DeleteDocumentRequest(deletedId, collection = "vault"))

            val restored = documents.v1DocumentsRestorePost(RestoreDocumentRequest(id = deletedId, collection = "vault"))
            assertEquals(deletedId, restored.id)
            assertEquals("vault", restored.collection)
            assertFalse(restored.revision.isBlank(), "restore must report the revision it created")

            val afterRestore = documents.v1DocumentsGetPost(GetDocumentRequest(deletedId))
            assertTrue(afterRestore.content.contains("deleted and then restored"))
        } finally {
            ApiClient.apiKey.clear()
            server.close()
        }
    }

    @Test
    fun `generated client renames a document and follows it to the new id`() {
        val server = StoreApiServer.startEphemeral(
            QueryFacade(
                store = InMemoryContextStore(),
                lookup = LuceneLookup(),
                clock = Clock { kotlin.time.Instant.fromEpochSeconds(0) },
                io = Dispatchers.Unconfined
            ),
            "secret"
        )
        ApiClient.apiKey["X-API-Key"] = "secret"
        try {
            val documents = DocumentsApi(server.baseUrl)
            val oldId = "sdk/rename-me"
            val newId = "sdk/renamed"
            documents.v1DocumentsUpsertPost(
                UpsertDocumentRequest(id = oldId, collection = "vault", content = "the body of the note")
            )

            val renamed = documents.v1DocumentsRenamePost(RenameDocumentRequest(id = oldId, newId = newId))

            assertEquals(newId, renamed.id)
            assertEquals(oldId, renamed.previousId)
            assertEquals("$newId.md", renamed.path)
            assertFalse(renamed.revision.isBlank(), "rename must report the revision it created")
            assertTrue(
                documents.v1DocumentsGetPost(GetDocumentRequest(newId)).content.contains("the body of the note")
            )
        } finally {
            ApiClient.apiKey.clear()
            server.close()
        }
    }
}
