package gokorei.tanseki.service.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VaultLockTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a second writer is refused while the first holds the vault`() {
        val lockFile = tmp.resolve(".tanseki/daemon.lock")
        val first = VaultLock(lockFile).acquire()
        try {
            assertTrue(first.isHeld)
            val second = VaultLock(lockFile)

            val error = assertThrows(IllegalStateException::class.java) { second.acquire() }

            assertTrue("single writer" in error.message.orEmpty(), error.message.orEmpty())
            assertFalse(second.isHeld)
        } finally {
            first.release()
        }
        assertFalse(first.isHeld)
    }

    @Test
    fun `the lock can be taken again once released`() {
        val lockFile = tmp.resolve(".tanseki/daemon.lock")
        val first = VaultLock(lockFile).acquire()
        first.release()

        val second = VaultLock(lockFile).acquire()
        try {
            assertTrue(second.isHeld)
        } finally {
            second.release()
        }
    }

    @Test
    fun `acquiring twice from one handle is idempotent`() {
        val lock = VaultLock(tmp.resolve(".tanseki/daemon.lock"))
        try {
            assertTrue(lock.acquire().acquire().isHeld)
        } finally {
            lock.release()
        }
    }

    @Test
    fun `the refusal names only the lock file and never the vault path`() {
        val vault = Files.createDirectories(tmp.resolve("private/vault-name"))
        val lockFile = vault.resolve(".tanseki/daemon.lock")
        val holder = VaultLock(lockFile).acquire()
        try {
            val error = assertThrows(IllegalStateException::class.java) { VaultLock(lockFile).acquire() }

            assertEquals("vault already locked (single writer): daemon.lock", error.message)
            assertFalse("private" in error.message.orEmpty(), error.message.orEmpty())
            assertFalse("vault-name" in error.message.orEmpty(), error.message.orEmpty())
        } finally {
            holder.release()
        }
    }

    /**
     * Two daemons can reach `acquire()` at the same moment. The state
     * operations share one monitor, so a second caller of the *same* handle sees
     * the lock it already holds instead of racing to open a second channel and
     * either losing one or telling itself the vault is taken by someone else.
     */
    @Test
    fun `concurrent acquisitions of one handle are idempotent`() {
        val lockFile = tmp.resolve(".tanseki/daemon.lock")
        val lock = VaultLock(lockFile)
        val contenders = 8
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(contenders)
        try {
            val outcomes =
                (1..contenders).map {
                    executor.submit<Boolean> {
                        start.await()
                        lock.acquire().isHeld
                    }
                }
            start.countDown()
            outcomes.forEach { outcome ->
                assertTrue(outcome.get(20, TimeUnit.SECONDS), "every caller must see the lock it already holds")
            }
            assertTrue(lock.isHeld)
        } finally {
            lock.release()
            executor.shutdownNow()
        }

        assertFalse(lock.isHeld)
        val next = VaultLock(lockFile).acquire()
        try {
            assertTrue(next.isHeld, "the vault must be free again after a release")
        } finally {
            next.release()
        }
    }

    /**
     * The single-writer rule under contention: handles that do not share state
     * are serialised by the file lock itself, so exactly one of them ends up
     * holding the vault.
     */
    @Test
    fun `only one of many vault handles is held`() {
        val lockFile = tmp.resolve(".tanseki/daemon.lock")
        val contenders = 8
        val start = CountDownLatch(1)
        val holding = AtomicInteger()
        val handles = ConcurrentLinkedQueue<VaultLock>()
        val executor = Executors.newFixedThreadPool(contenders)
        try {
            val attempts =
                (1..contenders).map {
                    executor.submit<Unit> {
                        val lock = VaultLock(lockFile)
                        handles += lock
                        start.await()
                        runCatching { lock.acquire() }.onSuccess { holding.incrementAndGet() }
                    }
                }
            start.countDown()
            attempts.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, holding.get(), "the vault has exactly one writer")
        } finally {
            handles.forEach { handle -> runCatching { handle.release() } }
            executor.shutdownNow()
        }
    }

    @Test
    fun `a refused acquisition leaves the lock file in place for the owner`() {
        val lockFile = tmp.resolve(".tanseki/daemon.lock")
        val holder = VaultLock(lockFile).acquire()
        try {
            assertThrows(IllegalStateException::class.java) { VaultLock(lockFile).acquire() }
            assertTrue(Files.exists(lockFile))
        } finally {
            holder.release()
        }
    }
}
