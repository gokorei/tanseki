package gokorei.tanseki.adapters.context.sqlite

import gokorei.tanseki.adapters.context.sqlite.db.TansekiDatabase
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.EdgeProps
import gokorei.tanseki.core.domain.RelType

/**
 * The edge relation: one row per directed edge, rewritten in a transaction.
 *
 * Kept out of [SqliteContextStore] so the store is a facade and the edge queries
 * share one home. The `@Synchronized` reader pairs with the synchronized
 * transaction writer: this store has one shared connection, so an unsynchronized
 * read on that same connection would see the DELETE before the inserts — a
 * document with no outgoing edges, which is exactly what the contract forbids.
 */
internal class SqliteEdges(private val database: TansekiDatabase) {
    fun upsertEdge(edge: Edge) {
        withBusyRetry {
            database.edgesQueries.upsert(
                src = edge.src.value,
                dst = edge.dst.value,
                rel = edge.rel.value,
                props = EdgeProps.encode(edge.props)
            )
        }
    }

    fun removeEdges(src: DocId, rel: RelType?) {
        withBusyRetry {
            if (rel == null) {
                database.edgesQueries.deleteEdgesFrom(src.value)
            } else {
                database.edgesQueries.deleteEdgesFromRel(src.value, rel.value)
            }
        }
    }

    fun removeIncomingEdges(dst: DocId, rel: RelType?) {
        withBusyRetry {
            if (rel == null) {
                database.edgesQueries.deleteEdgesTo(dst.value)
            } else {
                database.edgesQueries.deleteEdgesToRel(dst.value, rel.value)
            }
        }
    }

    @Synchronized
    fun clearEdges() {
        withBusyRetry { database.edgesQueries.clearEdges() }
    }

    @Synchronized
    fun replaceEdges(src: DocId, edges: List<Edge>) {
        val replacement = edges.distinctBy { edge -> edge.rel to edge.dst }
        withBusyRetry {
            database.transaction {
                database.edgesQueries.deleteEdgesFrom(src.value)
                replacement.forEach { edge ->
                    database.edgesQueries.upsert(
                        src = src.value,
                        dst = edge.dst.value,
                        rel = edge.rel.value,
                        props = EdgeProps.encode(edge.props)
                    )
                }
            }
        }
    }

    /**
     * Synchronized for the same reason as [replaceEdges], and it is not redundant.
     *
     * The transaction in `replaceEdges` protects other *connections*. This store
     * has one, shared across threads, so an unsynchronized reader on that same
     * connection sees the DELETE before it sees the inserts — a document with no
     * outgoing edges, which is exactly what the contract forbids. Marking only the
     * writer synchronized gives atomicity to nobody.
     */
    @Synchronized
    fun neighbors(id: DocId, rel: RelType?): List<Edge> {
        val rows =
            if (rel == null) {
                database.edgesQueries.neighborsOut(id.value).executeAsList()
            } else {
                database.edgesQueries.neighborsByRel(id.value, rel.value).executeAsList()
            }
        return rows.map { Edge(DocId(it.src), DocId(it.dst), RelType(it.rel), parseProps(it.props)) }
    }

    fun incomingNeighbors(id: DocId, rel: RelType?): List<Edge> =
        database.edgesQueries
            .neighborsIn(id.value)
            .executeAsList()
            .filter { rel == null || it.rel == rel.value }
            .map { Edge(DocId(it.src), DocId(it.dst), RelType(it.rel), parseProps(it.props)) }
}
