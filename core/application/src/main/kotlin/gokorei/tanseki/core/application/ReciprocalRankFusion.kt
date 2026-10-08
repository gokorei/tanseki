package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.ports.Hit

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009): combines several ranked result
 * lists without needing comparable scores, using only ranks:
 *
 *     score(d) = Σ_r 1 / (k + rank_r(d))
 *
 * Lexical and vector search produce scores on different scales, so rank fusion
 * is the natural way to blend them. `k = 60` is the standard default. Ties keep
 * first-seen order, so the first ranking (lexical) wins ties.
 */
object ReciprocalRankFusion {
    const val K = 60

    fun fuse(rankings: List<List<Hit>>, limit: Int): List<Hit> {
        if (limit <= 0) return emptyList()
        val scores = LinkedHashMap<DocId, Double>()
        val snippets = HashMap<DocId, String>()
        for (ranking in rankings) {
            ranking.forEachIndexed { rank, hit ->
                scores.merge(hit.id, 1.0 / (K + rank + 1), Double::plus)
                hit.snippet?.let { if (!snippets.containsKey(hit.id)) snippets[hit.id] = it }
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { Hit(it.key, it.value, snippets[it.key]) }
    }
}
