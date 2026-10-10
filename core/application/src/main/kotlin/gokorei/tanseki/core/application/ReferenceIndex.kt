package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.MarkdownParser

/**
 * Two in-memory maps that narrow edge repair to the documents that can
 * actually be affected.
 *
 * 1. Resolve map: id, path and every id suffix point at their document, so
 *    `[[notes]]`, `notes.md` and `daily/notes.md` resolve by lookup instead
 *    of a linear catalog scan.
 * 2. Reverse map: each normalized wikilink target and each `repo`/`pr`/
 *    `jira`/`files` value points at the documents that wrote it. When `T`
 *    arrives or disappears only those sources are re-derived.
 *
 * A `repo`/`pr` value naming a GitHub pull request (not a document id) simply
 * has no entry on the target side, so indexing such a document costs only its
 * own derivation.
 */
@Suppress("TooManyFunctions")
class ReferenceIndex {
    private val lock = Any()

    /** id -> path, the live document set this index has observed. */
    private val docs = LinkedHashMap<DocId, String>()
    private val pathToId = HashMap<String, DocId>()

    /** Normalized suffix (`a/b/c`, `b/c`, `c`) -> ids carrying it, for trailing matches. */
    private val suffixToIds = HashMap<String, MutableSet<DocId>>()

    /** Normalized reference value -> source ids that wrote it. */
    private val reverse = HashMap<String, MutableSet<DocId>>()

    /** Source id -> normalized values it currently carries, for diffing rewrites. */
    private val sourceRefs = HashMap<DocId, Set<String>>()

    private var resolveSeeded = false
    private var reverseSeeded = false

    fun isReverseSeeded(): Boolean = synchronized(lock) { reverseSeeded }

    /** One cheap `list()` to learn the ids/paths already in the store. No doc reads. */
    fun ensureResolveSeeded(store: ContextStore) {
        synchronized(lock) {
            if (resolveSeeded) return
        }
        val refs =
            try {
                store.list()
            } catch (_: Exception) {
                return
            }
        synchronized(lock) {
            if (resolveSeeded) return
            refs.forEach { ref -> putResolveLocked(ref.id, ref.path) }
            resolveSeeded = true
        }
    }

    /** Bulk seed from documents already in hand (rebuild path): no extra reads. */
    fun seedFrom(documents: List<Document>) {
        synchronized(lock) {
            documents.forEach { doc ->
                putResolveLocked(doc.id, doc.path)
                putReverseLocked(doc.id, referenceKeys(doc))
            }
            if (documents.isNotEmpty()) {
                resolveSeeded = true
                reverseSeeded = true
            }
        }
    }

    /**
     * Read every document once to learn who references what. One-time cost per
     * process: after a restart the reverse map is empty and a new target would
     * otherwise miss referrers indexed by the previous process.
     */
    fun ensureReverseSeeded(store: ContextStore) {
        synchronized(lock) {
            if (reverseSeeded) return
        }
        val refs =
            try {
                store.list()
            } catch (_: Exception) {
                return
            }
        refs.forEach { ref ->
            val doc =
                try {
                    store.read(ref.id)
                } catch (_: Exception) {
                    null
                } ?: return@forEach
            synchronized(lock) {
                putResolveLocked(doc.id, doc.path)
                putReverseLocked(doc.id, referenceKeys(doc))
            }
        }
        synchronized(lock) {
            resolveSeeded = true
            reverseSeeded = true
        }
    }

    /** Record `doc` as live and remember the references it carries. */
    fun track(doc: Document) {
        synchronized(lock) {
            putResolveLocked(doc.id, doc.path)
            putReverseLocked(doc.id, referenceKeys(doc))
            // Tracking real writes means the map is no longer cold-start empty;
            // still leave reverseSeeded alone so a restart still triggers one
            // catch-up scan for sources this process never indexed.
        }
    }

    /** Forget `id` as a resolve target, but keep it as a source until repaired. */
    fun evictResolve(id: DocId) {
        synchronized(lock) {
            val path = docs.remove(id) ?: id.value + ".md"
            pathToId.remove(path)
            LinkResolver.suffixes(id).forEach { suffix -> suffixToIds[suffix]?.remove(id) }
        }
    }

    /** Forget `id` as a source (after its delete is fully processed). */
    fun evictSource(id: DocId) {
        synchronized(lock) {
            removeReverseLocked(id)
        }
    }

    fun untrack(id: DocId) {
        synchronized(lock) {
            val path = docs.remove(id) ?: id.value + ".md"
            pathToId.remove(path)
            LinkResolver.suffixes(id).forEach { suffix -> suffixToIds[suffix]?.remove(id) }
            removeReverseLocked(id)
        }
    }

    /** Fast resolve: exact id first (documented precedence), then trailing suffix. */
    fun resolve(target: String): DocId? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val key = LinkResolver.normalize(target)
        if (key.isEmpty()) return null
        synchronized(lock) {
            // Exact id wins over any trailing match.
            val direct = runCatching { DocId(key) }.getOrNull()
            if (direct != null && docs.containsKey(direct)) return direct
            suffixToIds[key]?.minByOrNull { it.value }?.let { return it }
            return null
        }
    }

    /** Sources whose normalized reference is `targetId` or a suffix parent of it. */
    fun candidatesFor(targetId: DocId): Set<DocId> {
        synchronized(lock) {
            if (reverse.isEmpty() && !reverseSeeded) return emptySet()
            val out = LinkedHashSet<DocId>()
            LinkResolver.suffixes(targetId).forEach { suffix ->
                reverse[suffix]?.let { out.addAll(it) }
            }
            return out
        }
    }

    fun hasReverseEntries(): Boolean = synchronized(lock) { reverse.isNotEmpty() }

    private fun putResolveLocked(id: DocId, path: String) {
        docs[id] = path
        pathToId[path] = id
        LinkResolver.suffixes(id).forEach { suffix ->
            suffixToIds.getOrPut(suffix) { LinkedHashSet() }.add(id)
        }
    }

    private fun putReverseLocked(source: DocId, keys: Set<String>) {
        val old = sourceRefs[source] ?: emptySet()
        if (old == keys) return
        old.forEach { key -> reverse[key]?.remove(source) }
        keys.forEach { key -> reverse.getOrPut(key) { LinkedHashSet() }.add(source) }
        if (keys.isEmpty()) sourceRefs.remove(source) else sourceRefs[source] = keys
    }

    private fun removeReverseLocked(source: DocId) {
        val old = sourceRefs.remove(source) ?: return
        old.forEach { key -> reverse[key]?.remove(source) }
    }

    companion object {
        /** Normalized reference keys a document carries (wikilinks + resolvable frontmatter). */
        fun referenceKeys(doc: Document): Set<String> {
            val keys = LinkedHashSet<String>()
            MarkdownParser.parse(doc.content).links.forEach { link ->
                keyOf(link.target)?.let { keys += it }
            }
            listOf("repo", "pr", "jira", "files").forEach { field ->
                doc.frontmatter.texts(field).forEach { value ->
                    keyOf(value)?.let { keys += it }
                }
            }
            return keys
        }

        private fun keyOf(raw: String): String? {
            if (raw.trim().isEmpty()) return null
            val key = LinkResolver.normalize(raw)
            return key.ifEmpty { null }
        }
    }
}
