package gokorei.tanseki.core.ports

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.FrontmatterFilterValue
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.InvalidInputException
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.UnsupportedStoreOperationException
import gokorei.tanseki.core.domain.explain
import gokorei.tanseki.core.domain.resolveFrontmatterFilter

/** Filter facets materialized by the Lookup, always derivable from the store. */
data class Filters(
    val collections: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val frontmatter: Map<String, FrontmatterValue> = emptyMap()
) {
    /**
     * Whether these filters ask a question on their own.
     *
     * A blank query with a filter is a filter-only search and is answered; a blank query
     * with nothing to filter on is not a question, and enumerating a collection is
     * `documents:list`'s job. Stated once here because two places decide it — the lookup
     * and the pending overlay — and when they disagreed a document still awaiting
     * projection was in neither result.
     */
    val isSearching: Boolean
        get() = collections.isNotEmpty() || tags.isNotEmpty() || frontmatter.isNotEmpty()

    companion object {
        /**
         * [Filters] from the text a caller sent, with each value resolved to the typed
         * value it names.
         *
         * Typed on the way in, not just out: a filter arrives as text, and `42` and `"42"`
         * are different stored values that can only be told apart by resolving the text the
         * way the parser would have on the way in. Both adapters compare `value.encode()`,
         * so this is the one place the type has to be recovered — and putting it here, next
         * to the seam-independent rules, is what keeps `GET /v1/search` and the MCP `search`
         * tool from answering the same query differently.
         *
         * A filter that cannot be expressed is raised rather than dropped: a value that does not
         * resolve, or a key `fm=` does not address. Callers get [InvalidInputException] — which
         * each seam already maps to its own error envelope — carrying a message that names the
         * parameter, because a filter that matches nothing is indistinguishable from a document
         * that does not exist.
         */
        fun fromStrings(
            collections: Set<String> = emptySet(),
            tags: Set<String> = emptySet(),
            frontmatter: Map<String, String>
        ): Filters =
            Filters(
                collections,
                tags,
                frontmatter.mapValues { (key, value) ->
                    when (val resolved = resolveFrontmatterFilter(key, value)) {
                        is FrontmatterFilterValue.Resolved -> {
                            resolved.value
                        }

                        is FrontmatterFilterValue.Invalid -> {
                            throw InvalidInputException(resolved.explain("$key=$value"))
                        }
                    }
                }
            )
    }
}

interface VectorWriter {
    fun writeVectors(doc: Document, model: String, vectors: List<FloatArray>)
}

/**
 * A ranked search result.
 *
 * [path] and [title] are carried so a client can render a result row from one
 * response instead of issuing a follow-up fetch per hit. [snippet] is raw
 * Markdown windowed around the match, never rendered HTML — the `/v1` seam
 * returns raw content everywhere and rendering is the client's decision.
 * [highlights] are character offsets into [snippet] marking the matched terms,
 * so a client can highlight without re-tokenizing.
 */
data class Hit(
    val id: DocId,
    val score: Double,
    val snippet: String? = null,
    val path: String? = null,
    val title: String? = null,
    val highlights: List<IntRange> = emptyList()
)

/**
 * Derived read model: lexical text + kNN vectors + graph traversal. Pure and
 * rebuildable — `Lookup = f(ContextStore)`. Never written by clients, only by
 * the Indexer.
 */
interface Lookup {
    fun index(doc: Document, edges: List<Edge>)

    fun remove(id: DocId)

    fun indexedIds(): Set<DocId>? = null

    fun clear(): Unit = throw UnsupportedStoreOperationException("lookup does not support clear")

    fun searchText(q: String, filters: Filters, limit: Int): List<Hit>

    fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit>

    fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId>

    /**
     * Documents that link TO [id], optionally narrowed to a single relation.
     *
     * This is the reverse of [traverse], and it is deliberately a separate method
     * rather than a `direction` parameter on it. A direction flag would force
     * every adapter to answer both, and an adapter that cannot do so
     * efficiently would have to fake it. Declining here is honest; returning an
     * empty list would be a lie the caller cannot distinguish from "nothing
     * links here", which is exactly the failure that makes a backlinks pane
     * look broken rather than unavailable.
     */
    fun backlinks(id: DocId, rel: RelType? = null): List<DocId> =
        throw UnsupportedStoreOperationException("lookup does not support backlinks")

    fun rebuild(store: ContextStore)
}
