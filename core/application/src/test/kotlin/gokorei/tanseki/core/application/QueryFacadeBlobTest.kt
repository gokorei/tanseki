package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The facade's attachment rules, tested at the layer that owns them.
 *
 * The HTTP tests cover the route shape; these pin the guards themselves, which
 * are domain decisions rather than transport behaviour.
 */
class QueryFacadeBlobTest {
    private val store = InMemoryContextStore()

    /** Blobs never reach the Lookup, so it only needs to exist. */
    private class NoopLookup : Lookup {
        override fun index(doc: Document, edges: List<Edge>) = Unit

        override fun remove(id: DocId) = Unit

        override fun searchText(
            q: String,
            filters: Filters,
            limit: Int
        ): List<Hit> = emptyList()

        override fun searchVector(
            v: FloatArray,
            filters: Filters,
            limit: Int
        ): List<Hit> = emptyList()

        override fun traverse(
            id: DocId,
            rel: RelType,
            depth: Int
        ): List<DocId> = emptyList()

        override fun rebuild(store: ContextStore) = Unit
    }

    private fun facade() =
        QueryFacade(
            store = store,
            lookup = NoopLookup(),
            clock = Clock { Instant.fromEpochSeconds(0) },
            io = Dispatchers.Unconfined
        )

    @Test
    fun `an attachment round-trips and is addressed by the digest of its bytes`() =
        runBlocking {
            val bytes = byteArrayOf(1, 2, 3, -1, -128)
            val facade = facade()

            val ref = facade.putBlob(bytes)

            assertEquals("sha256", ref.algorithm)
            assertEquals(64, ref.hash.length)
            assertArrayEquals(bytes, facade.getBlob(ref))
            assertEquals(ref, facade.putBlob(bytes), "identity is the content digest")
        }

    @Test
    fun `a fetch by digest alone verifies the bytes`() =
        runBlocking {
            val facade = facade()
            val bytes = "verified".toByteArray()
            val ref = facade.putBlob(bytes)

            assertArrayEquals(bytes, facade.getBlob(BlobRef(ref.hash, -1L)))
        }

    @Test
    fun `an empty attachment is refused`() {
        assertThrows(InvalidInputException::class.java) {
            runBlocking { facade().putBlob(ByteArray(0)) }
        }
    }

    @Test
    fun `a digest that is not lowercase sha-256 hex is refused before any lookup`() {
        val facade = facade()

        listOf(
            "0".repeat(63),
            "0".repeat(65),
            "Z".repeat(64),
            "A".repeat(64),
            "g".repeat(64),
            ""
        ).forEach { hash ->
            assertThrows(
                InvalidInputException::class.java,
                { runBlocking { facade.getBlob(BlobRef(hash, -1L)) } },
                "must refuse $hash"
            )
        }
    }

    @Test
    fun `a well-formed digest for a blob that was never stored is a not found`() {
        assertThrows(NotFoundException::class.java) {
            runBlocking { facade().getBlob(BlobRef("a".repeat(64), -1L)) }
        }
    }
}
