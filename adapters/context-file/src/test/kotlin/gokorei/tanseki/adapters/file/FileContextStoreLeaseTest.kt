package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.ProjectionKind
import gokorei.tanseki.core.domain.ProjectionOperation
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.domain.VaultPath
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.core.ports.ProjectionClaim
import gokorei.tanseki.testkit.FakePijulClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Projection ownership on the file-backed outbox.
 *
 * The lease lives in a sidecar file created with `CREATE_NEW`, which is what makes
 * it atomic where the SQL backends use a conditional UPDATE. These tests exercise
 * that adapter directly rather than through the testkit double, because the sidecar
 * is the part most likely to be wrong in a way no double would notice.
 */
class FileContextStoreLeaseTest {
    private var clock: Instant = Instant.fromEpochSeconds(1_700_000_000)
    private lateinit var root: Path

    private fun store(): FileContextStore {
        root = Files.createTempDirectory("tanseki-lease")
        return FileContextStore(
            vault = VaultPath(root.toString()),
            pijul = FakePijulClient(),
            clock = Clock { clock }
        )
    }

    private fun document(id: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = "body\n",
            contentHash = "hash-$id",
            revision = RevisionId("rev-$id"),
            updatedAt = clock
        )

    private fun pendingOperation(store: FileContextStore, id: String = "a/b/c"): ProjectionOperation {
        store.write(document(id), "tester", "tester")
        return requireNotNull(store.projectionOperations())
            .pendingLive(10)
            .first { it.documentId == DocId(id) }
    }

    @Test
    fun `a claimed operation is excluded from a competing claim`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        val claim = outbox.claim(operation.id, "worker-a", 30.seconds, clock)

        assertEquals("worker-a", claim?.owner)
        assertNull(outbox.claim(operation.id, "worker-b", 30.seconds, clock))
    }

    @Test
    fun `completion requires the fencing token to still match`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        val stale = requireNotNull(outbox.claim(operation.id, "worker-a", 10.seconds, clock))
        clock += 11.seconds
        val takeover = requireNotNull(outbox.claim(operation.id, "worker-b", 30.seconds, clock))

        assertNotEquals(stale.token, takeover.token, "a takeover must mint a fresh token")
        assertFalse(outbox.completeClaim(stale), "a lapsed owner must not complete the operation")
        assertEquals(
            listOf(operation.id),
            outbox.pendingLive(10).map { it.id },
            "the record must survive a stolen completion"
        )
        assertTrue(outbox.completeClaim(takeover))
        assertTrue(outbox.pendingLive(10).isEmpty())
    }

    @Test
    fun `an expired lease is taken over by another worker`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        // A worker that died holding the claim: never released, never completed.
        requireNotNull(outbox.claim(operation.id, "worker-a", 10.seconds, clock))
        assertNull(outbox.claim(operation.id, "worker-b", 30.seconds, clock))

        clock += 11.seconds
        assertTrue(outbox.claim(operation.id, "worker-b", 30.seconds, clock) != null)
    }

    @Test
    fun `a dead letter cannot be claimed`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        outbox.deadLetter(operation)

        assertNull(outbox.claim(operation.id, "worker-a", 30.seconds, clock))
    }

    @Test
    fun `a released claim is immediately available`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        val claim = requireNotNull(outbox.claim(operation.id, "worker-a", 30.seconds, clock))
        outbox.releaseClaim(claim)

        assertTrue(outbox.claim(operation.id, "worker-b", 30.seconds, clock) != null)
    }

    @Test
    fun `an id containing a path separator still gets a claim`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        // The lease filename is an encoding of the id; a raw join would try to write
        // into a directory that does not exist and fail an ordinary write with a 500.
        val operation = pendingOperation(store, "deeply/nested/document/id")

        assertTrue(outbox.claim(operation.id, "worker-a", 30.seconds, clock) is ProjectionClaim)
        assertEquals(1, outbox.leasedOperations(clock).size)
    }

    @Test
    fun `leased operations report the owning id`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store, "a/b/c")

        requireNotNull(outbox.claim(operation.id, "worker-a", 30.minutes, clock))

        val leased = outbox.leasedOperations(clock)
        assertEquals(1, leased.size)
        assertEquals(operation.id, leased.single().operationId, "the id must survive the filename encoding")
        assertEquals("worker-a", leased.single().owner)
        assertTrue(outbox.leasedOperations(clock + 1.hours).isEmpty(), "an expired lease is not reported as live")
    }

    @Test
    fun `completing an operation removes its lease`() {
        val store = store()
        val outbox = requireNotNull(store.projectionOperations())
        val operation = pendingOperation(store)

        val claim = requireNotNull(outbox.claim(operation.id, "worker-a", 30.seconds, clock))
        outbox.completeClaim(claim)

        assertTrue(
            outbox.leasedOperations(clock).isEmpty(),
            "a stale lease file would make the operation un-claimable forever"
        )
    }
}
