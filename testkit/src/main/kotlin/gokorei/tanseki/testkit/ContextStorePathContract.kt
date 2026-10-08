package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.ConflictException
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The path contract: one rule every adapter shares.
 *
 * A document's path is its id plus the Markdown suffix, so no two ids can
 * ever share a path and no caller can store a divergent one. The vault cannot
 * represent anything else — the file's location *is* the path — so the
 * database adapters refuse divergent paths rather than recording metadata the
 * vault cannot round-trip. The same upsert therefore stores the same path on
 * every profile instead of one path per backend.
 *
 * Split out of [ContextStoreContract] rather than appended to it: that class
 * carries the whole write/read/edge surface and had reached the detekt size
 * limit, and path identity has enough of its own rules (derivation, collection
 * immutability, tombstone paths) to deserve a separate home.
 */
abstract class ContextStorePathContract {
    protected abstract fun newStore(): ContextStore

    protected open fun closeStore(store: ContextStore) = Unit

    private var storeRef: ContextStore? = null
    protected val store: ContextStore get() = storeRef!!

    @BeforeEach
    fun setUpPaths() {
        storeRef = newStore()
    }

    @AfterEach
    fun tearDownPaths() {
        storeRef?.let(::closeStore)
        storeRef = null
    }

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun documentAtPath(
        id: String,
        path: String,
        content: String,
        revision: String = "rev-1",
        collection: String = "vault"
    ) = Document(
        id = DocId(id),
        collection = Collection(collection),
        path = path,
        content = content,
        contentHash = "hash-$content",
        revision = RevisionId(revision),
        updatedAt = now
    )

    @Test
    @Suppress("FunctionNaming")
    fun `a path that does not derive from the id is rejected without touching either document`() {
        val owner = documentAtPath("a", "a.md", "owner")
        store.write(owner, "m", "tester")

        assertThrows(InvalidInputException::class.java) {
            store.write(documentAtPath("b", "a.md", "contender"), "m", "tester")
        }

        assertEquals("owner", store.read(owner.id)!!.content)
        assertNull(store.read(DocId("b")))

        // A move onto another derived path is the same violation: the path
        // names an id that is not the writer's.
        val second = documentAtPath("second", "second.md", "two")
        store.write(second, "m", "tester")
        assertThrows(InvalidInputException::class.java) {
            store.write(documentAtPath("second", "a.md", "moved", revision = "rev-2"), "m", "tester")
        }

        assertEquals("owner", store.read(owner.id)!!.content)
        assertEquals("two", store.read(second.id)!!.content)

        // Deletion does not free the path for another id either: the path still
        // names the deleted id, and the tombstone survives the refused claim.
        store.delete(owner.id, "gone", "tester")
        assertThrows(InvalidInputException::class.java) {
            store.write(documentAtPath("b", "a.md", "contender"), "m", "tester")
        }

        assertNull(store.read(owner.id))
        assertNull(store.read(DocId("b")))
        assertEquals(listOf(DocId("a")), store.listDeleted(null).map { it.id })
    }

    /**
     * Collections are immutable for an id on every adapter: the same id
     * rewritten under another collection conflicts instead of moving, live and
     * deleted alike. A shared path across collections cannot arise — two ids
     * never share a path — so there is no cross-collection ownership rule to
     * diverge on, only this one.
     */
    @Test
    @Suppress("FunctionNaming")
    fun `collections are immutable for an id, live and deleted`() {
        store.write(documentAtPath("a", "a.md", "one", collection = "one"), "m", "tester")

        assertThrows(ConflictException::class.java) {
            store.write(documentAtPath("a", "a.md", "two", collection = "two"), "m", "tester")
        }

        val current = store.read(DocId("a"))!!
        assertEquals("one", current.content)
        assertEquals(Collection("one"), current.collection)

        store.delete(DocId("a"), "gone", "tester")
        assertThrows(ConflictException::class.java) {
            store.write(documentAtPath("a", "a.md", "other", collection = "other"), "m", "tester")
        }

        assertEquals(Collection("one"), store.historyOwner(DocId("a"))!!.collection)
    }

    /**
     * Slash ids are ordinary ids: `notes/a` lives at `notes/a.md` on every
     * profile, and listings report that same derived path.
     */
    @Test
    @Suppress("FunctionNaming")
    fun `slash ids round-trip with id-derived paths`() {
        store.write(documentAtPath("notes/a", "notes/a.md", "hello"), "m", "tester")

        val read = store.read(DocId("notes/a"))!!
        assertEquals("hello", read.content)
        assertEquals("notes/a.md", read.path)
        val refs = store.list(Collection("vault"))
        assertEquals(listOf(DocId("notes/a")), refs.map { it.id })
        assertEquals("notes/a.md", refs.single().path)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `tombstones retain the id-derived path`() {
        store.write(documentAtPath("notes/a", "notes/a.md", "body"), "m", "tester")
        val deleted = store.delete(DocId("notes/a"), "gone", "tester")

        assertNull(store.read(DocId("notes/a")))
        assertTrue(store.list(Collection("vault")).isEmpty())
        val tombstone = store.listDeleted(Collection("vault")).single()
        assertEquals(DocId("notes/a"), tombstone.id)
        assertEquals("notes/a.md", tombstone.path)
        assertEquals(deleted.revision, tombstone.revision)
        val includingDeleted = store.readIncludingDeleted(DocId("notes/a"))!!
        assertEquals("notes/a.md", includingDeleted.path)
        assertTrue(includingDeleted.deleted)
    }
}
