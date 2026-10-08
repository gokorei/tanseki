package gokorei.tanseki.adapters.file

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * The store's concurrency rule, kept in one place: one lock per document path,
 * acquired in creation order.
 *
 * A path never mutates while its own `pijul record` is being staged, and
 * different paths proceed concurrently. The Pijul repo itself stays
 * single-writer through [recordLock], because a patch DAG cannot take two
 * writers. Locks carry a creation sequence number so an operation that takes
 * several paths (rename takes both ends of the move) acquires them in one global
 * order and cannot deadlock another that takes them in reverse.
 */
internal class DocumentLocks {
    private val pathLocks = ConcurrentHashMap<Path, IndexedLock>()
    private val lockSequence = AtomicLong()

    /** Serializes every `pijul record` across all paths: the DAG is single-writer. */
    val recordLock = ReentrantLock()

    private fun indexedLock(file: Path): IndexedLock =
        pathLocks.computeIfAbsent(file) { IndexedLock(ReentrantLock(), lockSequence.incrementAndGet()) }

    fun <T> withDocumentLock(file: Path, body: () -> T): T {
        val indexed = indexedLock(file)
        indexed.lock.lock()
        try {
            return body()
        } finally {
            indexed.lock.unlock()
        }
    }

    /** Runs [body] holding every given path lock, in the global order, deadlock-free. */
    fun <T> withDocumentLocks(files: List<Path>, body: () -> T): T {
        val locks = files.distinct().map(::indexedLock).sortedBy { it.order }
        locks.forEach { it.lock.lock() }
        try {
            return body()
        } finally {
            locks.asReversed().forEach { it.lock.unlock() }
        }
    }
}
