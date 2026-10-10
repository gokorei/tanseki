package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.domain.RelTypes
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.MarkdownParser

/**
 * Derives typed edges from a document and reconciles them into the ContextStore.
 *
 * Sources:
 *  - `[[wikilinks]]` → `links-to` edges (only when the target resolves);
 *  - frontmatter refs → typed edges: `repo`/`pr`/`jira` → `references`,
 *    `author` → `mentions`, `files` → `embeds`.
 *
 * Every reference value is resolved to a real document the way a wikilink
 * target is: a `repo`/`pr`/`jira` value that names no document derives no edge
 * rather than an edge to a synthetic id. A dangling reference is therefore
 * absent from the graph rather than visibly dangling — it cannot be told apart
 * from "no such document yet" by traversing. The documents that carry the value
 * are still discoverable through a frontmatter filter (`fm=repo=…`), which is
 * how sibling discovery ("the other documents from this repo") is asked; see
 * `docs/document-schema.md`.
 *
 * On rewrite, [reconcile] removes the document's previous edges first so stale
 * links do not linger.
 */
class EdgeDeriver(
    private val store: ContextStore,
    private val index: ReferenceIndex? = null
) {
    fun derive(doc: Document): List<Edge> {
        val edges = mutableListOf<Edge>()

        MarkdownParser.parse(doc.content).links.forEach { link ->
            resolve(link.target)?.let { target ->
                val props = link.label?.let { mapOf("label" to it) } ?: emptyMap()
                edges += Edge(doc.id, target, RelTypes.LinksTo, props)
            }
        }

        val authors =
            buildList {
                doc.frontmatter.author?.let(::add)
                addAll(references(doc, "author"))
            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        authors.forEach { author ->
            runCatching { DocId("author/$author") }.getOrNull()?.let { target ->
                edges += Edge(doc.id, target, RelTypes.Mentions, mapOf("author" to author))
            }
        }

        listOf("repo", "pr", "jira", "files").forEach { key ->
            val rel = relFor(key) ?: return@forEach
            references(doc, key).forEach { ref ->
                resolve(ref)?.let { target ->
                    edges += Edge(doc.id, target, rel)
                }
            }
        }

        return edges.distinctBy { edge -> edge.rel to edge.dst }
    }

    /** Derive fresh edges, replace the old ones atomically, and return the set. */
    fun reconcile(doc: Document): List<Edge> {
        val derived = derive(doc)
        store.replaceEdges(doc.id, derived)
        return derived
    }

    /**
     * Re-derives the documents that can only now resolve (or unresolve) links to
     * [target]. A document whose own projection is still queued is left alone:
     * its outbox operation owns its edge set, and repairing it here would make a
     * failed delivery look projected.
     *
     * This is what repairs a `references` edge whose target is written after the
     * referrer: the referrer derived nothing while the value dangled, and
     * re-deriving it once the target exists produces the edge.
     *
     * With a [ReferenceIndex] only sources whose normalized reference is the
     * target id or one of its suffix parents are re-derived. A review comment
     * whose `repo`/`pr` names a GitHub pull request matches nothing, so its
     * repair is empty without reading the collection. Without an index the
     * whole collection is re-derived, as before.
     */
    fun repairTarget(target: DocId): List<EdgeRepair> {
        val fast = index
        if (fast != null) {
            if (!fast.isReverseSeeded()) {
                fast.ensureReverseSeeded(store)
            }
            val candidates = fast.candidatesFor(target)
            if (candidates.isEmpty()) return emptyList()
            val unprojected = unprojectedDocuments()
            return candidates.mapNotNull { id ->
                if (id == target || id in unprojected) return@mapNotNull null
                val document = store.read(id) ?: return@mapNotNull null
                val before = store.neighbors(document.id)
                val derived = derive(document)
                if (before.toSet() == derived.toSet()) return@mapNotNull null
                store.replaceEdges(document.id, derived)
                EdgeRepair(document, derived)
            }
        }
        val unprojected = unprojectedDocuments()
        return store.list().mapNotNull { ref ->
            if (ref.id == target || ref.id in unprojected) return@mapNotNull null
            val document = store.read(ref.id) ?: return@mapNotNull null
            val before = store.neighbors(document.id)
            val derived = derive(document)
            if (before.toSet() == derived.toSet()) return@mapNotNull null
            store.replaceEdges(document.id, derived)
            EdgeRepair(document, derived)
        }
    }

    private fun unprojectedDocuments(): Set<DocId> =
        store
            .projectionOperations()
            ?.pending(Int.MAX_VALUE)
            ?.mapTo(mutableSetOf()) { it.documentId }
            ?: emptySet()

    @Suppress("NestedBlockDepth")
    fun resolve(target: String): DocId? {
        val fast = index
        if (fast != null) {
            fast.resolve(target)?.let { hit ->
                // The index can hold an id the store no longer has (rename
                // without an evict): one read keeps a stale entry from becoming
                // a phantom edge.
                if (store.read(hit) != null) return hit
                fast.evictResolve(hit)
                fast.resolve(target)?.let { retry ->
                    if (store.read(retry) != null) return retry
                    fast.evictResolve(retry)
                }
            }
            // Exact external write not yet tracked: one read, no catalog scan.
            // Trailing misses stay unresolved until the target is indexed, at
            // which point repair (via the reverse map) fixes the referrer.
            val trimmed = target.trim()
            if (trimmed.isEmpty()) return null
            val key = LinkResolver.normalize(target)
            if (key.isEmpty()) return null
            val direct = runCatching { DocId(key) }.getOrNull() ?: return null
            return if (store.read(direct) != null) direct else null
        }
        return LinkResolver.resolve(target, exists = { store.read(it) != null }, refs = store.list())
    }

    private fun relFor(key: String): RelType? =
        when (key) {
            "repo", "pr", "jira" -> RelTypes.References
            "author" -> RelTypes.Mentions
            "files" -> RelTypes.Embeds
            else -> null
        }

    /**
     * The values [key] names, in document order.
     *
     * A scalar names one thing and a sequence names several, and the author may
     * write either — so both are read as what they are rather than as text that
     * has to be taken apart. An earlier version stripped brackets and split on
     * commas, which made the comma load-bearing: a path containing one could not
     * survive the round trip.
     */
    private fun references(doc: Document, key: String): List<String> = doc.frontmatter.texts(key)
}

data class EdgeRepair(
    val document: Document,
    val edges: List<Edge>
)
