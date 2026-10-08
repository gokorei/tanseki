@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.BlobCorruptionException
import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.NotFoundException

/**
 * Content-addressed blobs across the `blobs` (metadata) and `blob_content`
 * tables.
 *
 * Kept out of [SqliteContextStore] so the store is a facade and the
 * metadata/content consistency rules live once.
 */
internal class SqliteBlobs(private val database: TansekiDatabase) {
    fun putBlob(bytes: ByteArray): BlobRef {
        val hash = BlobSupport.sha256(bytes)
        val ref = BlobRef(hash = hash, size = bytes.size.toLong(), algorithm = BlobSupport.ALGORITHM)
        withBusyRetry {
            database.transaction {
                val metadata = database.blobsQueries.selectByHash(hash).executeAsOneOrNull()
                val content = database.blobContentQueries.selectContent(hash).executeAsOneOrNull()
                if (metadata != null || content != null) {
                    if (metadata == null || content == null) {
                        throw BlobCorruptionException("blob metadata and content are inconsistent for $hash")
                    }
                    if (metadata.algorithm != BlobSupport.ALGORITHM || metadata.size != ref.size) {
                        throw BlobCorruptionException("blob metadata and content are inconsistent for $hash")
                    }
                    BlobSupport.verify(ref, content)
                } else {
                    database.blobsQueries.upsert(hash = hash, algorithm = BlobSupport.ALGORITHM, size = ref.size)
                    database.blobContentQueries.insertContent(hash = hash, bytes = bytes)
                }
            }
        }
        return ref
    }

    fun getBlob(ref: BlobRef): ByteArray {
        BlobSupport.validateRef(ref)
        val metadata = database.blobsQueries.selectByHash(ref.hash).executeAsOneOrNull()
        val bytes = database.blobContentQueries.selectContent(ref.hash).executeAsOneOrNull()
        if (metadata == null && bytes == null) {
            throw NotFoundException()
        }
        if (metadata == null || bytes == null) {
            throw BlobCorruptionException("blob metadata and content are inconsistent for ${ref.hash}")
        }
        // A negative ref.size means the caller asserted a digest but not a size,
        // which is what a fetch-by-hash can do. Comparing the recorded size
        // against it would report corruption for every such fetch, so the size
        // comparison is skipped unless a size was actually asserted. BlobSupport.
        // verify still checks the digest of the bytes themselves.
        val sizeMismatch = BlobSupport.sizeDisagrees(ref, metadata.size)
        if (metadata.algorithm != BlobSupport.ALGORITHM || sizeMismatch) {
            throw BlobCorruptionException("blob metadata does not match reference ${ref.hash}")
        }
        BlobSupport.verify(ref, bytes)
        return bytes
    }
}
