package gokorei.tanseki.adapters.lucene

import org.apache.lucene.codecs.Codec
import org.apache.lucene.codecs.FilterCodec
import org.apache.lucene.codecs.lucene912.Lucene912Codec
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat
import org.apache.lucene.index.VectorSimilarityFunction

/**
 * kNN vector index tuning for [LuceneLookup].
 *
 * The shipped default is the measured best-trade-off preset (the "default"
 * row of the recall table in this module's README: maxConn 16, beamWidth 100,
 * no dimension pin, no overfetch — recall@10 0.995 at 1.149 ms/query on the
 * 1000-doc benchmark). It gains ~12 recall points over the lean shape for less
 * index time, while the rich shape buys only +0.005 recall for ~2.2x query
 * latency. The shipped values are pinned as literals so a Lucene upgrade
 * cannot silently retune the index; the codec's own out-of-the-box constants
 * are only used for the read-only no-arg codec instance.
 *
 * The HNSW graph is built at index time with [maxConn] (M: graph fanout) and
 * [beamWidth] (efConstruction: candidate beam while building). Both only take
 * effect on newly written segments, so retuning an existing on-disk index
 * requires a [LuceneLookup.rebuild] before the whole index uses the new shape.
 *
 * Lucene 9 fixes the search-time ef internally, so there is no native efSearch
 * knob. [numCandidates] is the search-time recall dial instead: when set, a
 * vector query fetches `max(limit, numCandidates)` candidates and keeps the
 * top `limit`. Larger values cost latency for recall, exactly the ef trade-off.
 *
 * [dimensions] is the expected vector length. Null (the default) accepts
 * whatever the embedder produces and lets Lucene enforce consistency; set it
 * to fail fast with a readable error when the embedder drifts.
 */
data class LuceneVectorConfig(
    val dimensions: Int? = null,
    val maxConn: Int = SHIPPED_MAX_CONN,
    val beamWidth: Int = SHIPPED_BEAM_WIDTH,
    val numCandidates: Int? = null,
    val similarity: VectorSimilarityFunction = VectorSimilarityFunction.COSINE
) {
    init {
        dimensions?.let { require(it > 0) { "dimensions must be positive, was $it" } }
        require(maxConn in MIN_MAX_CONN..MAXIMUM_MAX_CONN) {
            "maxConn (HNSW M) must be in $MIN_MAX_CONN..$MAXIMUM_MAX_CONN, was $maxConn"
        }
        require(beamWidth in MIN_BEAM_WIDTH..MAXIMUM_BEAM_WIDTH) {
            "beamWidth (HNSW efConstruction) must be in $MIN_BEAM_WIDTH..$MAXIMUM_BEAM_WIDTH, was $beamWidth"
        }
        numCandidates?.let { require(it > 0) { "numCandidates must be positive, was $it" } }
    }

    /** A [Codec] that builds HNSW graphs with this config's M/beam width. */
    internal fun codec(): Codec = TansekiHnswCodec(maxConn, beamWidth)

    /** Fails fast when a stored or query vector does not match [dimensions]. */
    fun requireDimension(size: Int, what: String) {
        dimensions?.let { expected ->
            require(size == expected) { "$what has dim $size, expected $expected" }
        }
    }

    companion object {
        /**
         * Shipped HNSW shape: the measured best-trade-off preset (recall@10
         * 0.995 at 1.149 ms/query). Pinned as literals, not aliased to the
         * codec's out-of-the-box constants, so the default cannot drift with a
         * Lucene upgrade. They currently coincide with the codec defaults.
         */
        const val SHIPPED_MAX_CONN = 16
        const val SHIPPED_BEAM_WIDTH = 100

        /** The shipped default preset as an instance. */
        val DEFAULT = LuceneVectorConfig()

        const val MAXIMUM_MAX_CONN = Lucene99HnswVectorsFormat.MAXIMUM_MAX_CONN
        const val MAXIMUM_BEAM_WIDTH = Lucene99HnswVectorsFormat.MAXIMUM_BEAM_WIDTH
        const val MIN_MAX_CONN = 2
        const val MIN_BEAM_WIDTH = 2
    }
}

/**
 * kNN codec with tuned HNSW params.
 *
 * The name is fixed (`TansekiHnsw`) so segments stay readable: Lucene stores
 * the codec name in each segment and resolves it via SPI on open, which is
 * why this class is public with a no-arg constructor and registered under
 * `META-INF/services`. The no-arg instance (defaults) only serves reads —
 * graph layout is self-describing, so a reader built with defaults reads
 * graphs written with any M/beam width. Writes always use [LuceneVectorConfig.codec].
 */
class TansekiHnswCodec : FilterCodec {
    private val vectorsFormat: Lucene99HnswVectorsFormat

    constructor() : this(
        Lucene99HnswVectorsFormat.DEFAULT_MAX_CONN,
        Lucene99HnswVectorsFormat.DEFAULT_BEAM_WIDTH
    )

    constructor(maxConn: Int, beamWidth: Int) : super(NAME, Lucene912Codec()) {
        vectorsFormat = Lucene99HnswVectorsFormat(maxConn, beamWidth)
    }

    override fun knnVectorsFormat() = vectorsFormat

    companion object {
        const val NAME = "TansekiHnsw"
    }
}
