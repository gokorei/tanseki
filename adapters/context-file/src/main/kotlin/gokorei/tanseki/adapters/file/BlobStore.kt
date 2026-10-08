@file:Suppress("ThrowsCount")

package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.BlobRef
import gokorei.tanseki.core.domain.BlobSupport
import gokorei.tanseki.core.domain.NotFoundException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Content-addressed blob store under `.tanseki/blobs/sha256/`.
 *
 * Kept out of [FileContextStore]: blobs are the one concern with no document
 * path or sidecar semantics, so their existence checks and escape guards live
 * apart from the metadata rules.
 */
internal class BlobStore(
    private val layout: StoreLayout,
    private val files: MetadataFiles
) {
    fun putBlob(bytes: ByteArray): BlobRef {
        val hash = BlobSupport.sha256(bytes)
        val ref = BlobRef(hash = hash, size = bytes.size.toLong(), algorithm = BlobSupport.ALGORITHM)
        Files.createDirectories(layout.blobsDir)
        val target = layout.blobsDir.resolve(hash)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)) throw layout.invalidPath("symbolic links are not valid blobs")
            layout.requireContainedRealPath(target)
            BlobSupport.verify(ref, Files.readAllBytes(target))
        } else {
            files.atomicWrite(target, bytes)
        }
        return ref
    }

    fun getBlob(ref: BlobRef): ByteArray {
        BlobSupport.validateRef(ref)
        val target = layout.blobsDir.resolve(ref.hash).normalize()
        if (!target.startsWith(layout.blobsDir)) throw layout.invalidPath("blob path escapes the blob store")
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw NotFoundException()
        if (Files.isSymbolicLink(target)) throw layout.invalidPath("symbolic links are not valid blobs")
        layout.requireContainedRealPath(target)
        val bytes = Files.readAllBytes(target)
        BlobSupport.verify(ref, bytes)
        return bytes
    }
}
