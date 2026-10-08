package gokorei.tanseki.adapters.file

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64

/**
 * The edge sidecars, one file per declaring document.
 *
 * Kept out of [FileContextStore] so every edge mutation shares one codec for how
 * a sidecar is read, rewritten and deduplicated, and inbound-edge scans (which
 * cross every file, because an edge lives in the sidecar of the document that
 * declared it) do not collide with document reads.
 */
internal class EdgeIndex(
    private val layout: StoreLayout,
    private val files: MetadataFiles
) {
    fun upsertEdge(edge: Edge) {
        files.migrateLegacyMetadataFile(layout.edgesDir, edge.src)
        writeEdges(
            layout,
            files,
            edge.src,
            edgesFor(edge.src).filterNot { it.rel == edge.rel && it.dst == edge.dst } + edge
        )
    }

    @Synchronized
    fun removeEdges(src: DocId, rel: RelType?) {
        val remaining = edgesFor(src).filterNot { rel == null || it.rel == rel }
        if (remaining.isEmpty()) {
            files.deleteMetadataFile(layout.edgesDir, src.value)
        } else {
            writeEdges(layout, files, src, remaining)
        }
    }

    @Synchronized
    fun removeIncomingEdges(dst: DocId, rel: RelType?) {
        if (!Files.isDirectory(layout.edgesDir)) return
        edgeFiles(layout).forEach { file ->
            val edges = readEdgeLines(file, DocId(UNKNOWN_SOURCE))
            if (edges.isEmpty()) return@forEach
            val remaining = edges.filterNot { it.dst == dst && (rel == null || it.rel == rel) }
            if (remaining.size == edges.size) return@forEach
            if (remaining.isEmpty()) {
                Files.deleteIfExists(file)
            } else {
                files.atomicWrite(file, encodeEdges(remaining))
            }
        }
    }

    /**
     * Re-points every edge whose destination is [from] at [to], across all sidecars.
     *
     * An edge lives in the sidecar of the document that *declared* it, so a
     * renamed document's own sidecar says nothing about who points at it: the
     * inbound edges are scattered across other documents' files and have to be
     * rewritten one file at a time. De-duplicated by (rel, dst) because retargeting
     * can land two edges on the same pair — a document that linked both `notes/t`
     * and `notes/t.md` now has two edges to `folder/t`, and one of them has to go.
     */
    fun retargetIncomingEdges(from: DocId, to: DocId) {
        if (!Files.isDirectory(layout.edgesDir)) return
        edgeFiles(layout).forEach { file ->
            val edges = readEdgeLines(file, DocId(UNKNOWN_SOURCE))
            if (edges.none { it.dst == from }) return@forEach
            val retargeted = edges.map { if (it.dst == from) it.copy(dst = to) else it }
            val deduped = retargeted.distinctBy { edge -> edge.rel to edge.dst }
            if (deduped.isEmpty()) {
                Files.deleteIfExists(file)
            } else {
                files.atomicWrite(file, encodeEdges(deduped))
            }
        }
    }

    @Synchronized
    fun clearEdges() {
        if (!Files.isDirectory(layout.edgesDir)) return
        edgeFiles(layout).forEach { Files.deleteIfExists(it) }
    }

    @Synchronized
    fun replaceEdges(src: DocId, edges: List<Edge>) {
        val replacement = edges.distinctBy { edge -> edge.rel to edge.dst }
        if (replacement.isEmpty()) {
            files.deleteMetadataFile(layout.edgesDir, src.value)
        } else {
            writeEdges(layout, files, src, replacement)
        }
    }

    fun edgesFor(src: DocId): List<Edge> {
        val file = files.metadataFileOrNull(layout.edgesDir, src) ?: return emptyList()
        return readEdgeLines(file, src)
    }

    /** Synchronized to pair with [replaceEdges]: reads must not straddle a swap. */
    @Synchronized
    fun neighbors(id: DocId, rel: RelType?): List<Edge> =
        edgesFor(id)
            .filter { rel == null || it.rel == rel }

    @Synchronized
    fun incomingNeighbors(id: DocId, rel: RelType?): List<Edge> {
        if (!Files.isDirectory(layout.edgesDir)) return emptyList()
        return edgeFiles(layout)
            .mapNotNull { file -> sourceOfEdgeFile(layout, file)?.let { readEdgeLines(file, it) } }
            .flatten()
            .filter { it.dst == id && (rel == null || it.rel == rel) }
            .sortedWith(EDGE_ORDER)
    }
}

private fun readEdgeLines(file: Path, src: DocId): List<Edge> =
    Files
        .readAllLines(file)
        .filter { it.isNotBlank() }
        .mapNotNull { decodeEdge(it, src) }
        .sortedWith(EDGE_ORDER)

private fun writeEdges(layout: StoreLayout, files: MetadataFiles, src: DocId, edges: List<Edge>) {
    val file = files.metadataFile(layout.edgesDir, src.value)
    files.migrateLegacyMetadataFile(layout.edgesDir, src)
    files.atomicWrite(file, encodeEdges(edges))
}

private fun edgeFiles(layout: StoreLayout): List<Path> =
    Files.walk(layout.edgesDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { !Files.isSymbolicLink(it) }
            .toList()
    }

/**
 * The owning source of an edge file, recovered from the file's name. Edge
 * files are named `v2_<base64url(src)>` chunked across directories, and the
 * src is not repeated in each line — so a scan for inbound edges has to
 * decode the path rather than read it off a line.
 */
private fun sourceOfEdgeFile(layout: StoreLayout, file: Path): DocId? =
    runCatching {
        val encoded = layout.edgesDir.relativize(file).joinToString("") { it.toString() }
        require(encoded.startsWith(METADATA_V2_PREFIX))
        val value =
            String(
                Base64.getUrlDecoder().decode(encoded.removePrefix(METADATA_V2_PREFIX)),
                StandardCharsets.UTF_8
            )
        DocId(value)
    }.getOrNull()
