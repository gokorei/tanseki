package gokorei.tanseki.core.domain

/**
 * How a query is ranked.
 *
 * Both seams name the same two behaviours with the same two words — `?mode=` on
 * `GET /v1/search`, and the `mode` argument of the MCP `search` tool — so the
 * vocabulary is declared once here rather than spelled as loose strings on each
 * seam. A caller who learns it on one gets it on the other, and a mode added here
 * cannot be spelled one way on one seam and another way on the other.
 *
 * It is a closed set, so every surface that takes it declares an `enum` rather
 * than a free string: an unrecognised mode is a caller mistake worth naming, not
 * a value to coerce into whatever the code defaults to.
 *
 * [Hybrid] adds cosine kNN to the lexical ranking and fuses the two with
 * reciprocal rank fusion. It **degrades to [Lexical]** rather than failing when
 * the profile has no embedder, or when the Lookup holds no vectors (Meili today):
 * a query that cannot be answered better is not an error. The response carries
 * nothing that says which ranking actually ran, so both seams state the fallback
 * in prose rather than signalling it per hit.
 */
enum class SearchMode(val wire: String) {
    /** Rank by the Lookup's text index alone. */
    Lexical("lexical"),

    /** Rank by lexical and vector results fused with reciprocal rank fusion. */
    Hybrid("hybrid");

    companion object {
        /**
         * The wire values, in declaration order: what a tool schema advertises as
         * its `enum` and what a query parameter is validated against.
         */
        val WIRES: List<String> = entries.map { it.wire }

        /** What a caller gets for asking for nothing: what `search` did before modes existed. */
        val DEFAULT: SearchMode = Lexical

        /** The mode named by [value], or `null` when the caller named something else. */
        fun parse(value: String): SearchMode? = entries.firstOrNull { it.wire == value }
    }
}
