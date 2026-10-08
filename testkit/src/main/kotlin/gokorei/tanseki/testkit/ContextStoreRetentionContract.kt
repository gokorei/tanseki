package gokorei.tanseki.testkit

import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.NotFoundException
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.ContextStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Instant

/**
 * The retention contract: what a store owes callers about content it holds on
 * behalf of nobody in particular — deleted documents, and attachments.
 *
 * Both are the same idea from the store's side. A tombstone is a document kept
 * after the caller said to drop it; a blob is bytes kept under a digest rather
 * than a name. In both cases the store is the only thing that can prove the
 * retained content is still what it claims to be, so integrity verification
 * lives here.
 *
 * Split out of [ContextStoreContract] for the same reason listing is: the main
 * contract had reached the detekt size limit, and these cases turn on conflict
 * and non-destruction rather than ordinary reads.
 */
abstract class ContextStoreRetentionContract {
    protected abstract fun newStore(): ContextStore

    /**
     * Whether the store implements [ContextStore.restore]. Postgres retains
     * tombstones but cannot reverse a delete yet, so the restore cases are skipped
     * there rather than reported green without being exercised.
     */
    protected open fun supportsRestore() = true

    protected open fun closeStore(store: ContextStore) = Unit

    /**
     * Simulates corruption of stored bytes for [hash], leaving the hash alone.
     * Only adapters whose storage this test can reach should override it.
     */
    protected open fun corruptStoredBlob(hash: String) = Unit

    private var storeRef: ContextStore? = null
    protected val store: ContextStore get() = storeRef!!

    @BeforeEach
    fun setUpRetention() {
        storeRef = newStore()
    }

    @AfterEach
    fun tearDownRetention() {
        storeRef?.let(::closeStore)
        storeRef = null
    }

    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun document(id: String, content: String, revision: String = "rev-1") =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$content",
            revision = RevisionId(revision),
            updatedAt = now
        )

    private fun documentAtPath(
        id: String,
        path: String,
        content: String,
        collection: String = "vault"
    ) = Document(
        id = DocId(id),
        collection = Collection(collection),
        path = path,
        content = content,
        contentHash = "hash-$content",
        revision = RevisionId("rev-1"),
        updatedAt = now
    )

    @Test
    @Suppress("FunctionNaming")
    fun `listDeleted reports tombstones and hides live documents`() {
        store.write(document("live", "still here"), "m", "tester")
        val deleted = store.delete(DocId("live"), "gone", "tester")

        val tombstones = store.listDeleted(null)

        assertEquals(listOf(DocId("live")), tombstones.map { it.id })
        val tombstone = tombstones.single()
        assertEquals(deleted.revision, tombstone.revision)
        assertEquals("live.md", tombstone.path)
        assertEquals(Collection("vault"), tombstone.collection)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `listDeleted filters by collection and tolerates an empty store`() {
        assertEquals(emptyList<DocRef>(), store.listDeleted(null))

        store.write(document("a", "a"), "m", "tester")
        store.delete(DocId("a"), "gone", "tester")

        assertEquals(1, store.listDeleted(Collection("vault")).size)
        assertEquals(emptyList<DocRef>(), store.listDeleted(Collection("elsewhere")))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `restore returns the original bytes under the original id and path`() {
        assumeTrue(supportsRestore(), "adapter does not support restore")
        store.write(document("a", "original body"), "m", "tester")
        store.delete(DocId("a"), "gone", "tester")

        val restored = store.restore(DocId("a"), "back", "tester")

        val document = store.read(DocId("a"))
        assertNotNull(document, "restored document must be readable again")
        assertEquals("original body", document!!.content)
        assertEquals("a.md", document.path)
        assertEquals(restored.revision, document.revision)
        assertEquals(emptyList<DocRef>(), store.listDeleted(null))
        assertEquals(listOf(DocId("a")), store.list(null).map { it.id })
    }

    @Test
    @Suppress("FunctionNaming")
    fun `restoring a live document is idempotent and records no new revision`() {
        assumeTrue(supportsRestore(), "adapter does not support restore")
        store.write(document("a", "body"), "m", "tester")
        val before = store.history(DocId("a")).size

        val first = store.restore(DocId("a"), "back", "tester")
        val second = store.restore(DocId("a"), "back", "tester")

        assertEquals(first.revision, second.revision)
        assertEquals(before, store.history(DocId("a")).size, "an idempotent restore must not grow history")
    }

    @Test
    @Suppress("FunctionNaming")
    fun `restore of an id the store never held is a not found`() {
        assumeTrue(supportsRestore(), "adapter does not support restore")
        assertThrows(NotFoundException::class.java) {
            store.restore(DocId("never/existed"), "back", "tester")
        }
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a tombstoned path cannot be claimed, so restore always finds its path free`() {
        // Under the path contract no other id can hold this tombstone's path —
        // a divergent path is rejected as invalid — so a restore never has to
        // refuse a reclaimed path. The squatter write below must fail, and the
        // tombstone must survive it.
        assumeTrue(supportsRestore(), "adapter does not support restore")
        store.write(document("a", "original"), "m", "tester")
        store.delete(DocId("a"), "gone", "tester")

        assertThrows(InvalidInputException::class.java) {
            store.write(documentAtPath("b", "a.md", "squatter"), "m", "tester")
        }

        val restored = store.restore(DocId("a"), "back", "tester")

        val document = store.read(DocId("a"))
        assertNotNull(document, "restored document must be readable again")
        assertEquals("original", document!!.content)
        assertEquals("a.md", document.path)
        assertEquals(restored.revision, document.revision)
        assertEquals(emptyList<DocRef>(), store.listDeleted(null))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `restore of a nested id returns the derived path`() {
        assumeTrue(supportsRestore(), "adapter does not support restore")
        store.write(document("notes/a", "original body"), "m", "tester")
        store.delete(DocId("notes/a"), "gone", "tester")

        val restored = store.restore(DocId("notes/a"), "back", "tester")

        val document = store.read(DocId("notes/a"))
        assertNotNull(document, "restored document must be readable again")
        assertEquals("original body", document!!.content)
        assertEquals("notes/a.md", document.path)
        assertEquals(restored.revision, document.revision)
    }

    @Test
    @Suppress("FunctionNaming")
    fun `blob round-trips byte for byte and is addressed by its digest`() {
        val bytes = byteArrayOf(0, 1, 2, 3, -1, -2, 127)
        val ref = store.putBlob(bytes)

        assertEquals("sha256", ref.algorithm)
        assertEquals(bytes.size.toLong(), ref.size)
        assertArrayEquals(bytes, store.getBlob(ref))

        // Identity is the digest of the content, so identical bytes are the same blob.
        assertEquals(ref, store.putBlob(bytes))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `fetching a blob by digest alone still verifies the bytes`() {
        val bytes = "attachment payload".toByteArray()
        val ref = store.putBlob(bytes)

        assertArrayEquals(bytes, store.getBlob(BlobRef(ref.hash, -1L)))
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a blob whose size does not match its reference is refused as corrupted`() {
        val ref = store.putBlob("real bytes".toByteArray())

        assertThrows(BlobCorruptionException::class.java) {
            store.getBlob(BlobRef(ref.hash, ref.size + 1))
        }
    }

    @Test
    @Suppress("FunctionNaming")
    fun `a blob is refused when its bytes do not match the digest it is addressed by`() {
        val ref = store.putBlob("honest bytes".toByteArray())
        corruptStoredBlob(ref.hash)

        assertThrows(BlobCorruptionException::class.java) { store.getBlob(ref) }
    }

    @Test
    @Suppress("FunctionNaming")
    fun `an unknown blob digest is a not found`() {
        val absent = "0".repeat(64)

        assertThrows(NotFoundException::class.java) {
            store.getBlob(BlobRef(absent, -1L))
        }
    }
}
