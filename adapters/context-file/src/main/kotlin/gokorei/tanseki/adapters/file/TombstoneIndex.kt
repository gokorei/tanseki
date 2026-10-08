package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.PijulPatch
import gokorei.tanseki.core.ports.TansekiLogger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.time.Instant

/**
 * The tombstone sidecars: the only record of a deleted document's bytes.
 *
 * Kept out of [FileContextStore] so delete/restore/list share one place for what
 * a tombstone is, how it decodes (every version ever written), and how a
 * corrupt one is moved out of the listing path.
 */
internal class TombstoneIndex(
    private val layout: StoreLayout,
    private val files: MetadataFiles,
    private val logger: TansekiLogger
) {
    fun tombstoneFile(id: DocId): Path = files.metadataFile(layout.tombstonesDir, id.value)

    @Synchronized
    fun listDeleted(collection: Collection?): List<DocRef> {
        if (!Files.isDirectory(layout.tombstonesDir)) return emptyList()
        val tombstoneFiles =
            Files.walk(layout.tombstonesDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { !Files.isSymbolicLink(it) }
                    .toList()
            }
        return tombstoneFiles
            .mapNotNull { file ->
                val id = fileNameToId(layout, file)
                val tombstone = readTombstone(id, file)
                if (collection != null && tombstone.collection != collection.value) return@mapNotNull null
                DocRef(
                    id = id,
                    collection = Collection(tombstone.collection),
                    path = tombstone.path,
                    contentHash = tombstone.contentHash,
                    revision = RevisionId(tombstone.revision),
                    updatedAt = Instant.fromEpochMilliseconds(tombstone.deletedAt)
                )
            }.sortedBy { ref -> ref.id.value }
    }

    /** A tombstone sidecar as a [DocRef], or null after quarantining a corrupt one. */
    fun tombstoneRefOrNull(file: Path): DocRef? =
        try {
            val fields = Files.readString(file).trim().split('\t', limit = 6)
            val path = fields.getOrNull(2)?.let(::decodePendingText)
            val id = path?.let { runCatching { DocId(it.removeSuffix(".md")) }.getOrNull() }
            if (id == null) {
                null
            } else {
                val tombstone = readTombstone(id, file)
                DocRef(
                    id = id,
                    collection = Collection(tombstone.collection),
                    path = tombstone.path,
                    contentHash = tombstone.contentHash,
                    revision = RevisionId(tombstone.revision),
                    updatedAt = Instant.fromEpochMilliseconds(tombstone.deletedAt)
                )
            }
        } catch (error: Exception) {
            // One corrupt tombstone must not throw out of listAll/listDeleted:
            // quarantine it and list the rest.
            quarantineCorrupt(layout, logger, file, error)
            null
        }

    fun writeTombstone(pending: PendingHistory, patch: PijulPatch) {
        val tombstone =
            Tombstone(
                collection = pending.collection,
                path = pending.path,
                revision = patch.hash.value,
                contentHash = pending.contentHash,
                content = pending.previousContent,
                deletedAt = patch.timestamp.toEpochMilliseconds()
            )
        // The tombstone is the only record of a deleted document's bytes, so the
        // hash must describe the content it carries — including an empty body,
        // whose hash is the digest of the empty string, not the empty string.
        require(tombstone.contentHash == Hashes.sha256(tombstone.content)) {
            "tombstone content hash does not match the content it stores"
        }
        files.atomicWriteString(tombstoneFile(pending.documentId), encodeTombstone(tombstone))
    }

    fun readTombstone(id: DocId, file: Path): Tombstone {
        val fields = Files.readString(file).trim().split('\t')
        val version = fields.firstOrNull()
        val hasContent = version == TOMBSTONE_VERSION
        require(hasContent || version == "1" || version == "2")
        require(
            fields.size ==
                if (hasContent) {
                    7
                } else if (version == "1") {
                    5
                } else {
                    6
                }
        )
        val contentHash = if (version == "1") "" else decodePendingText(fields[4])
        val tombstone =
            Tombstone(
                collection = decodePendingText(fields[1]),
                path = decodePendingText(fields[2]),
                revision = decodePendingText(fields[3]),
                contentHash = contentHash,
                content = if (hasContent) decodePendingText(fields[5]) else "",
                deletedAt =
                    fields[
                        if (hasContent) {
                            6
                        } else if (version == "1") {
                            4
                        } else {
                            5
                        }
                    ].toLong()
            )
        require(tombstone.path == layout.relative(layout.fileFor(id)))
        if (hasContent) {
            require(tombstone.contentHash == Hashes.sha256(tombstone.content)) {
                "tombstone content hash does not match the content it stores"
            }
        }
        return tombstone
    }
}

/**
 * Recovers the document id from a tombstone file name.
 *
 * The name is `v2_<base64url(id)>` chunked across directories, exactly as
 * for edge files, so the id is decoded rather than stored a second time in
 * the record.
 */
private fun fileNameToId(layout: StoreLayout, file: Path): DocId =
    runCatching {
        val encoded = layout.tombstonesDir.relativize(file).joinToString("") { it.toString() }
        require(encoded.startsWith(METADATA_V2_PREFIX))
        DocId(
            String(
                Base64.getUrlDecoder().decode(encoded.removePrefix(METADATA_V2_PREFIX)),
                StandardCharsets.UTF_8
            )
        )
    }.getOrElse { DocId(file.fileName.toString()) }

/** Moves an undecodable tombstone aside so it cannot fail a listing. */
private fun quarantineCorrupt(
    layout: StoreLayout,
    logger: TansekiLogger,
    file: Path,
    error: Exception
) {
    runCatching {
        Files.createDirectories(layout.corruptTombstonesDir)
        Files.move(
            file,
            layout.corruptTombstonesDir.resolve(file.fileName.toString()),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
    logger.warn(
        "quarantined corrupt tombstone",
        mapOf(
            "file" to file.fileName.toString(),
            "error" to (error.message ?: error::class.simpleName ?: "error")
        )
    )
}
