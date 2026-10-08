package gokorei.tanseki.adapters.lucene

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Edge
import gokorei.tanseki.core.domain.RelType
import gokorei.tanseki.core.ports.ContextStore
import gokorei.tanseki.core.ports.Filters
import gokorei.tanseki.core.ports.Hit
import gokorei.tanseki.core.ports.Lookup
import gokorei.tanseki.core.ports.TansekiLogger
import gokorei.tanseki.core.ports.VectorWriter
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import org.apache.lucene.document.Document as LuceneDocument

/**
 * Embedded local [Lookup] backed by Apache Lucene.
 *
 * One index holds three kinds of Lucene documents:
 *  - **node** docs: text fields (`title`/`content`/`tags`), stored metadata, and
 *    the (optional) kNN `vector` field;
 *  - **edge** docs: stored `edge_src`/`edge_dst`/`edge_rel`, used for traversal.
 *
 * `Lookup = f(ContextStore)`: [rebuild] drops the index and recreates nodes and
 * edges from the store. Vectors are supplied separately via [upsertVector] (the
 * embedder pipeline owns embedding) and recomputed after a rebuild.
 */
class LuceneLookup(
    private val directory: Directory = ByteBuffersDirectory(),
    private val logger: TansekiLogger = TansekiLogger.Noop,
    val vectorConfig: LuceneVectorConfig = LuceneVectorConfig()
) : Lookup, VectorWriter, AutoCloseable {
    private val analyzer = StandardAnalyzer()
    private val writer =
        IndexWriter(
            directory,
            IndexWriterConfig(analyzer).apply {
                openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
                codec = vectorConfig.codec()
            }
        )
    private val parser = MultiFieldQueryParser(arrayOf(FIELD_TITLE, FIELD_CONTENT, FIELD_TAGS), analyzer)

    /**
     * Indexes every non-contract frontmatter key as a filterable term.
     *
     * A collection is indexed twice: once as the whole value, so a filter can
     * name it exactly, and once per scalar leaf, so `fm_files=…a.py` matches a
     * document whose `files` is a five-element list. Without the leaves a list
     * would only be filterable by reproducing the entire list, which is not a
     * filter anyone can write.
     */
    private fun indexFrontmatter(node: LuceneDocument, doc: Document) {
        for ((key, value) in doc.frontmatter.values) {
            val field = "$FIELD_FRONTMATTER$key"
            node.add(StringField(field, value.encode(), Field.Store.NO))
            for (leaf in value.leaves()) {
                val encoded = leaf.encode()
                if (encoded != value.encode()) node.add(StringField(field, encoded, Field.Store.NO))
            }
        }
    }

    @Synchronized
    override fun index(doc: Document, edges: List<Edge>) {
        indexWithoutCommit(doc, edges)
        writer.commit()
        logger.debug("lookup index", mapOf("doc" to doc.id.value, "edges" to edges.size))
    }

    /**
     * Writes one document and its edges without committing, so rebuild and other
     * batch paths can commit once instead of once per document.
     */
    private fun indexWithoutCommit(doc: Document, edges: List<Edge>) {
        deleteOwned(doc.id)

        val node = LuceneDocument()
        node.add(StringField(FIELD_DOC_ID, doc.id.value, Field.Store.YES))
        node.add(StringField(FIELD_COLLECTION, doc.collection.value, Field.Store.YES))
        node.add(StringField(FIELD_PATH, doc.path, Field.Store.YES))
        node.add(StringField(FIELD_CONTENT_HASH, doc.contentHash, Field.Store.YES))
        node.add(TextField(FIELD_CONTENT, doc.content, Field.Store.YES))
        doc.frontmatter.title?.let { node.add(TextField(FIELD_TITLE, it, Field.Store.YES)) }
        doc.frontmatter.tags.forEach { node.add(StringField(FIELD_TAGS, it, Field.Store.YES)) }
        indexFrontmatter(node, doc)
        node.add(StoredField(FIELD_UPDATED_AT, doc.updatedAt.toEpochMilliseconds()))
        writer.addDocument(node)

        edges.forEach { edge ->
            val edgeDoc = LuceneDocument()
            edgeDoc.add(StringField(FIELD_EDGE_SRC, edge.src.value, Field.Store.YES))
            edgeDoc.add(StringField(FIELD_EDGE_DST, edge.dst.value, Field.Store.YES))
            edgeDoc.add(StringField(FIELD_EDGE_REL, edge.rel.value, Field.Store.YES))
            writer.addDocument(edgeDoc)
        }
    }

    @Synchronized
    override fun writeVectors(doc: Document, model: String, vectors: List<FloatArray>) {
        writeVectors(doc, model, vectors, 0)
    }

    @Synchronized
    fun upsertVector(doc: Document, chunk: Int, vector: FloatArray) {
        writeVectors(doc, "legacy", listOf(vector), chunk)
    }

    private fun writeVectors(
        doc: Document,
        model: String,
        vectors: List<FloatArray>,
        firstChunk: Int
    ) {
        vectors.forEach { vectorConfig.requireDimension(it.size, "stored vector") }
        deleteVectors(doc.id)
        vectors.forEachIndexed { index, vector ->
            val chunk = firstChunk + index
            val vectorDoc = LuceneDocument()
            vectorDoc.add(StringField(FIELD_ID, "${doc.id.value}#$chunk", Field.Store.YES))
            vectorDoc.add(StringField(FIELD_KIND, "vector", Field.Store.YES))
            vectorDoc.add(StringField(FIELD_DOC_ID, doc.id.value, Field.Store.YES))
            vectorDoc.add(StringField(FIELD_MODEL, model, Field.Store.YES))
            vectorDoc.add(StringField(FIELD_COLLECTION, doc.collection.value, Field.Store.YES))
            vectorDoc.add(StoredField(FIELD_VECTOR_CONTENT, doc.content))
            doc.frontmatter.tags.forEach { vectorDoc.add(StringField(FIELD_TAGS, it, Field.Store.YES)) }
            indexFrontmatter(vectorDoc, doc)
            vectorDoc.add(StoredField(FIELD_CHUNK, chunk))
            vectorDoc.add(KnnFloatVectorField(FIELD_VECTOR, vector, vectorConfig.similarity))
            writer.addDocument(vectorDoc)
        }
        writer.commit()
    }

    @Synchronized
    override fun remove(id: DocId) {
        deleteOwned(id)
        writer.deleteDocuments(Term(FIELD_EDGE_DST, id.value))
        writer.commit()
    }

    @Synchronized
    override fun indexedIds(): Set<DocId> {
        val reader = reader() ?: return emptySet()
        return reader.use { open ->
            val searcher = IndexSearcher(open)
            val query = MatchAllDocsQuery()
            val count = searcher.count(query)
            if (count == 0) return@use emptySet()
            searcher
                .search(query, count)
                .scoreDocs
                .mapNotNull { scoreDoc ->
                    runCatching { DocId(searcher.storedFields().document(scoreDoc.doc).get(FIELD_DOC_ID)) }.getOrNull()
                }.toSet()
        }
    }

    @Synchronized
    override fun clear() {
        writer.deleteAll()
        writer.commit()
    }

    /**
     * Text search, optionally narrowed by [filters].
     *
     * A blank `q` with filters is a **filter-only query**: it enumerates what
     * matches the facets rather than what matches a term. That is what makes
     * `fm=repo=org/repo` answer "which documents have this property" instead of
     * silently returning nothing, and it is the behaviour Meilisearch already
     * gives for an empty `q` — so this also removes a profile-dependent answer
     * from the same request.
     *
     * A blank `q` with no filters still returns nothing. Enumerating the whole
     * index is `documents:list`'s job, and a search endpoint that answers an
     * empty query with every document would make a mistyped parameter look like a
     * successful one. `limit` bounds the filtered case, as it does every other
     * page.
     */
    @Synchronized
    override fun searchText(q: String, filters: Filters, limit: Int): List<Hit> {
        if (q.isBlank()) {
            val facets = filtersQuery(filters) ?: return emptyList()
            val hits = search(facets, limit)
            logger.debug("lookup filter-only search", mapOf("hits" to hits.size))
            return hits
        }
        val parsed =
            try {
                parser.parse(QueryParser.escape(q))
            } catch (error: Exception) {
                logger.warn("lookup query parse failed", mapOf("q" to q), error)
                return emptyList()
            }
        val hits = search(withFilters(parsed, filters), limit, queryTerms(q))
        logger.debug("lookup text search", mapOf("q" to q, "hits" to hits.size))
        return hits
    }

    /**
     * The terms a query contributes, analyzed the way the index was.
     *
     * Taken from the raw query rather than the parsed `Query` so that a
     * multi-field or boolean query still highlights every term the user typed,
     * including ones Lucene scored down.
     */
    private fun queryTerms(q: String): Set<String> {
        val terms = mutableSetOf<String>()
        analyzer.tokenStream(FIELD_CONTENT, q).use { stream ->
            val term: CharTermAttribute = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) terms += term.toString()
            stream.end()
        }
        return terms
    }

    @Synchronized
    override fun searchVector(v: FloatArray, filters: Filters, limit: Int): List<Hit> {
        vectorConfig.requireDimension(v.size, "query vector")
        // Lucene 9 fixes search-time ef internally; overfetching emulates the
        // efSearch dial (more candidates = more recall for more latency).
        val candidates = vectorConfig.numCandidates?.coerceAtLeast(limit) ?: limit
        val filter = filtersQuery(filters)
        val query =
            if (filter == null) {
                KnnFloatVectorQuery(FIELD_VECTOR, v, candidates)
            } else {
                KnnFloatVectorQuery(FIELD_VECTOR, v, candidates, filter)
            }
        return try {
            search(query, candidates).take(limit)
        } catch (error: Exception) {
            logger.warn("lookup vector search failed", mapOf("limit" to limit), error)
            emptyList()
        }
    }

    @Synchronized
    override fun traverse(id: DocId, rel: RelType, depth: Int): List<DocId> {
        val visited = LinkedHashSet<DocId>()
        var frontier = listOf(id)
        repeat(depth.coerceAtLeast(0)) {
            val next = mutableListOf<DocId>()
            val reader = reader()
            if (reader != null) {
                reader.use { open ->
                    val searcher = IndexSearcher(open)
                    for (node in frontier) {
                        val query =
                            BooleanQuery
                                .Builder()
                                .add(TermQuery(Term(FIELD_EDGE_SRC, node.value)), BooleanClause.Occur.MUST)
                                .add(TermQuery(Term(FIELD_EDGE_REL, rel.value)), BooleanClause.Occur.MUST)
                                .build()
                        searcher.search(query, MAX_RESULTS).scoreDocs.forEach { scoreDoc ->
                            val stored = searcher.storedFields().document(scoreDoc.doc)
                            val dst = stored.get(FIELD_EDGE_DST)?.let(::DocId) ?: return@forEach
                            if (visited.add(dst)) next += dst
                        }
                    }
                }
            }
            frontier = next
        }
        return visited.toList()
    }

    @Synchronized
    override fun backlinks(id: DocId, rel: RelType?): List<DocId> {
        val sources = LinkedHashSet<DocId>()
        val open = reader() ?: return emptyList()
        open.use {
            val searcher = IndexSearcher(it)
            val query =
                BooleanQuery
                    .Builder()
                    .add(TermQuery(Term(FIELD_EDGE_DST, id.value)), BooleanClause.Occur.MUST)
                    .apply {
                        if (rel !=
                            null
                        ) {
                            add(TermQuery(Term(FIELD_EDGE_REL, rel.value)), BooleanClause.Occur.FILTER)
                        }
                    }.build()
            searcher.search(query, MAX_RESULTS).scoreDocs.forEach { scoreDoc ->
                val stored = searcher.storedFields().document(scoreDoc.doc)
                stored.get(FIELD_EDGE_SRC)?.let { sources.add(DocId(it)) }
            }
        }
        return sources.toList()
    }

    @Synchronized
    override fun rebuild(store: ContextStore) {
        writer.deleteAll()
        for (ref in store.list()) {
            val doc = store.read(ref.id) ?: continue
            indexWithoutCommit(doc, store.neighbors(ref.id))
        }
        writer.commit()
    }

    @Synchronized
    override fun close() {
        writer.close()
        directory.close()
    }

    private fun deleteOwned(id: DocId) {
        writer.deleteDocuments(Term(FIELD_DOC_ID, id.value))
        writer.deleteDocuments(Term(FIELD_ID, id.value))
        writer.deleteDocuments(Term(FIELD_EDGE_SRC, id.value))
    }

    private fun deleteVectors(id: DocId) {
        writer.deleteDocuments(
            BooleanQuery
                .Builder()
                .add(TermQuery(Term(FIELD_KIND, "vector")), BooleanClause.Occur.FILTER)
                .add(TermQuery(Term(FIELD_DOC_ID, id.value)), BooleanClause.Occur.FILTER)
                .build()
        )
    }

    private fun withFilters(base: Query, filters: Filters): Query {
        val filter = filtersQuery(filters) ?: return base
        return BooleanQuery
            .Builder()
            .add(base, BooleanClause.Occur.MUST)
            .add(filter, BooleanClause.Occur.FILTER)
            .build()
    }

    private fun filtersQuery(filters: Filters): Query? {
        if (filters.collections.isEmpty() && filters.tags.isEmpty() && filters.frontmatter.isEmpty()) return null
        val builder = BooleanQuery.Builder()
        filters.collections.forEach { builder.add(TermQuery(Term(FIELD_COLLECTION, it)), BooleanClause.Occur.FILTER) }
        filters.tags.forEach { builder.add(TermQuery(Term(FIELD_TAGS, it)), BooleanClause.Occur.FILTER) }
        filters.frontmatter.forEach { (key, value) ->
            builder.add(TermQuery(Term("$FIELD_FRONTMATTER$key", value.encode())), BooleanClause.Occur.FILTER)
        }
        return builder.build()
    }

    private fun search(query: Query, limit: Int, terms: Set<String> = emptySet()): List<Hit> {
        val reader = reader() ?: return emptyList()
        reader.use { open ->
            val searcher = IndexSearcher(open)
            val top = searcher.search(query, limit)
            return top.scoreDocs
                .mapNotNull { scoreDoc -> toHit(searcher, scoreDoc, terms) }
                .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id.value })
        }
    }

    private fun toHit(searcher: IndexSearcher, scoreDoc: ScoreDoc, terms: Set<String>): Hit? {
        val stored = searcher.storedFields().document(scoreDoc.doc)
        val id = stored.get(FIELD_DOC_ID) ?: return null
        val content = stored.get(FIELD_CONTENT) ?: stored.get(FIELD_VECTOR_CONTENT)
        val windowed = content?.let { window(it, terms) }
        return Hit(
            id = DocId(id),
            score = scoreDoc.score.toDouble(),
            snippet = windowed?.snippet,
            path = stored.get(FIELD_PATH),
            title = stored.get(FIELD_TITLE),
            highlights = windowed?.highlights.orEmpty()
        )
    }

    /**
     * Cut a [SNIPPET_LENGTH] window around the first matched term.
     *
     * Windowing uses the same analyzer as indexing rather than a substring
     * search, because the analyzer is what decided the match in the first
     * place. That also means scripts without whitespace keep working: the
     * analyzer splits CJK into single-character terms, so each ideograph is its
     * own match and the window lands on it.
     *
     * Falls back to the head of the content when nothing matched — a
     * vector-only hit, or a term the analyzer resolves differently on the way
     * back. An empty snippet would read as "this document does not contain the
     * match", which is worse than an uninformative one.
     */
    private fun window(
        content: String,
        terms: Set<String>
    ): Windowed {
        if (terms.isEmpty() || content.length <= SNIPPET_LENGTH) {
            return Windowed(content.take(SNIPPET_LENGTH), emptyList())
        }
        val matches = matchOffsets(content, terms)
        if (matches.isEmpty()) return Windowed(content.take(SNIPPET_LENGTH), emptyList())
        val start = (matches.first().first - SNIPPET_LEAD).coerceAtLeast(0)
        val end = (start + SNIPPET_LENGTH).coerceAtMost(content.length)
        val snippet = content.substring(start, end)
        val highlights =
            matches
                // IntRange.last is inclusive, so the exclusive end is restored
                // before re-basing rather than carried through the subtraction.
                .map { (it.first - start) until (it.last - start + 1) }
                .filter { it.first >= 0 && it.last < snippet.length }
        return Windowed(snippet, highlights)
    }

    /** Character offsets of every [terms] occurrence, using the index analyzer. */
    private fun matchOffsets(content: String, terms: Set<String>): List<IntRange> {
        val wanted = terms.map(String::lowercase).toSet()
        if (wanted.isEmpty()) return emptyList()
        val offsets = mutableListOf<IntRange>()
        analyzer.tokenStream(FIELD_CONTENT, content).use { stream ->
            val term: CharTermAttribute = stream.addAttribute(CharTermAttribute::class.java)
            val offset: OffsetAttribute = stream.addAttribute(OffsetAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) {
                if (term.toString().lowercase() in wanted) {
                    offsets += offset.startOffset() until offset.endOffset()
                }
            }
            stream.end()
        }
        return offsets.sortedBy { it.first }
    }

    private data class Windowed(val snippet: String, val highlights: List<IntRange>)

    /** Opens a reader, or null when the index has no segments yet (fresh vault). */
    private fun reader(): DirectoryReader? =
        try {
            DirectoryReader.open(directory)
        } catch (error: Exception) {
            // An uncommitted or absent index is the expected fresh-vault case;
            // anything else is worth a debug line rather than a silent empty.
            logger.debug("lookup index reader unavailable", mapOf("error" to (error.message ?: "")))
            null
        }

    companion object {
        /** Opens (or creates) a durable on-disk index under [indexDir]. */
        fun open(
            indexDir: java.nio.file.Path,
            logger: TansekiLogger = TansekiLogger.Noop,
            vectorConfig: LuceneVectorConfig = LuceneVectorConfig()
        ): LuceneLookup {
            java.nio.file.Files
                .createDirectories(indexDir)
            return LuceneLookup(
                org.apache.lucene.store.FSDirectory
                    .open(indexDir),
                logger,
                vectorConfig
            )
        }

        private const val FIELD_ID = "id"
        private const val FIELD_KIND = "kind"
        private const val FIELD_DOC_ID = "doc_id"
        private const val FIELD_MODEL = "model"
        private const val FIELD_COLLECTION = "collection"
        private const val FIELD_PATH = "path"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_TITLE = "title"
        private const val FIELD_TAGS = "tags"
        private const val FIELD_CONTENT_HASH = "content_hash"
        private const val FIELD_UPDATED_AT = "updated_at"
        private const val FIELD_FRONTMATTER = "fm_"
        private const val FIELD_VECTOR = "vector"
        private const val FIELD_VECTOR_CONTENT = "vector_content"
        private const val FIELD_CHUNK = "chunk"
        private const val FIELD_EDGE_SRC = "edge_src"
        private const val FIELD_EDGE_DST = "edge_dst"
        private const val FIELD_EDGE_REL = "edge_rel"
        private const val MAX_RESULTS = 1000
        private const val SNIPPET_LEAD = 40
        private const val SNIPPET_LENGTH = 200
    }
}
