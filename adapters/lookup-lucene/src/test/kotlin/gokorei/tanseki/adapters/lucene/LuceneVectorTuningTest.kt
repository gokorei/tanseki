package gokorei.tanseki.adapters.lucene

import gokorei.tanseki.core.domain.Collection
import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.Document
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.RevisionId
import gokorei.tanseki.core.ports.Filters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Instant

class LuceneVectorTuningTest {
    private val now: Instant = Instant.fromEpochSeconds(1_700_000_000)

    private fun doc(id: String, content: String) =
        Document(
            id = DocId(id),
            collection = Collection("vault"),
            path = "$id.md",
            content = content,
            contentHash = "hash-$id",
            revision = RevisionId("rev"),
            updatedAt = now,
            frontmatter = Frontmatter()
        )

    @Test
    fun `defaults match the Lucene HNSW out-of-the-box shape`() {
        val config = LuceneVectorConfig()

        assertEquals(16, config.maxConn)
        assertEquals(100, config.beamWidth)
        assertEquals(null, config.dimensions)
        assertEquals(null, config.numCandidates)
    }

    @Test
    fun `out-of-range params are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LuceneVectorConfig(maxConn = 1) }
        assertThrows(IllegalArgumentException::class.java) { LuceneVectorConfig(maxConn = 513) }
        assertThrows(IllegalArgumentException::class.java) { LuceneVectorConfig(beamWidth = 1) }
        assertThrows(IllegalArgumentException::class.java) { LuceneVectorConfig(beamWidth = 3201) }
        assertThrows(IllegalArgumentException::class.java) { LuceneVectorConfig(dimensions = 0) }
        assertThrows(IllegalArgumentException::class.java) { LuceneVectorConfig(numCandidates = 0) }
    }

    @Test
    fun `config reaches the lookup and the dimension fails fast`() {
        val config = LuceneVectorConfig(dimensions = 3, maxConn = 8, beamWidth = 20)

        LuceneLookup(vectorConfig = config).use { lookup ->
            assertEquals(config, lookup.vectorConfig)

            val document = doc("a", "alpha")
            lookup.index(document, emptyList())
            lookup.writeVectors(document, "m", listOf(floatArrayOf(1f, 0f, 0f)))

            assertThrows(IllegalArgumentException::class.java) {
                lookup.writeVectors(document, "m", listOf(floatArrayOf(1f, 0f)))
            }
            assertThrows(IllegalArgumentException::class.java) {
                lookup.searchVector(floatArrayOf(1f, 0f), Filters(), 5)
            }
            assertEquals(1, lookup.searchVector(floatArrayOf(1f, 0f, 0f), Filters(), 5).size)
        }
    }

    @Test
    fun `numCandidates overfetch still bounds results to limit`() {
        val config = LuceneVectorConfig(numCandidates = 50)

        LuceneLookup(vectorConfig = config).use { lookup ->
            repeat(5) { index ->
                val document = doc("d$index", "body $index")
                lookup.index(document, emptyList())
                lookup.writeVectors(document, "m", listOf(floatArrayOf(index.toFloat(), 1f)))
            }

            val hits = lookup.searchVector(floatArrayOf(4f, 1f), Filters(), 2)

            assertEquals(2, hits.size)
            assertEquals(DocId("d4"), hits.first().id)
        }
    }

    @Test
    fun `recall and latency across HNSW shapes`() {
        val shapes =
            listOf(
                Triple("lean", LuceneVectorConfig(maxConn = 8, beamWidth = 20), 64),
                Triple("default", LuceneVectorConfig(), 64),
                Triple("rich", LuceneVectorConfig(maxConn = 32, beamWidth = 200, numCandidates = 50), 64),
                Triple("compact", LuceneVectorConfig(maxConn = 8, beamWidth = 20), 16)
            )
        val rows = shapes.map { (name, config, dim) -> measure(name, config, docs = 1000, dim = dim) }

        println("HNSW recall@10 / latency (seed 42, 1000 docs):")
        println("shape    dim maxConn beam recall  indexMs searchMs/q")
        rows.forEach { println(it) }

        val byName = rows.associateBy { it.name }
        // The default shape must stay near-exact at this scale; the lean shape
        // is allowed to trade recall for a smaller graph.
        assertTrue(byName.getValue("default").recall >= 0.95, "default recall was ${byName.getValue("default").recall}")
        assertTrue(byName.getValue("rich").recall >= byName.getValue("default").recall)
        rows.forEach { assertTrue(it.recall in 0.0..1.0) }
    }

    private data class Row(
        val name: String,
        val dim: Int,
        val maxConn: Int,
        val beamWidth: Int,
        val recall: Double,
        val indexMs: Long,
        val searchMsPerQuery: Double
    ) {
        override fun toString(): String =
            "%-8s %-3d %-7d %-4d %-6.3f %-7d %.3f".format(
                name,
                dim,
                maxConn,
                beamWidth,
                recall,
                indexMs,
                searchMsPerQuery
            )
    }

    private fun measure(name: String, config: LuceneVectorConfig, docs: Int, dim: Int): Row {
        val random = Random(42)
        val vectors = List(docs) { unit(random, dim) }
        // Probes are stored vectors with a nudge, so the ground truth is exact
        // search over the same space rather than a guess at neighbours.
        val probes = List(20) { nudge(vectors[(it * 53) % docs], random, 0.01f) }

        LuceneLookup(vectorConfig = config).use { lookup ->
            val indexMs =
                kotlin.system.measureTimeMillis {
                    vectors.forEachIndexed { index, vector ->
                        val document = doc("v$index", "vector body $index")
                        lookup.index(document, emptyList())
                        lookup.writeVectors(document, "tuning", listOf(vector))
                    }
                }
            var hits = 0.0
            val searchNs =
                kotlin.system.measureNanoTime {
                    probes.forEach { probe ->
                        val expected = bruteForce(vectors, probe, 10)
                        val found = lookup.searchVector(probe, Filters(), 10)
                        val actual =
                            found
                                .mapNotNull { hit -> docIndex(hit.id) }
                                .toSet()
                        hits += expected.intersect(actual).size / 10.0
                    }
                }
            val recall = hits / probes.size
            return Row(
                name,
                dim,
                config.maxConn,
                config.beamWidth,
                recall,
                indexMs,
                searchNs / 1_000_000.0 / probes.size
            )
        }
    }

    private fun unit(random: Random, dim: Int): FloatArray {
        val raw = FloatArray(dim) { random.nextFloat() * 2 - 1 }
        val norm = Math.sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { raw[it] / norm }
    }

    private fun nudge(base: FloatArray, random: Random, scale: Float): FloatArray {
        val raw = FloatArray(base.size) { base[it] + (random.nextFloat() * 2 - 1) * scale }
        val norm = Math.sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(base.size) { raw[it] / norm }
    }

    private fun bruteForce(vectors: List<FloatArray>, probe: FloatArray, k: Int): Set<Int> =
        vectors
            .mapIndexed { index, vector -> index to dot(vector, probe) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
            .toSet()

    private fun docIndex(id: DocId): Int? {
        val raw: String = id.value
        val bare = raw.removePrefix("v")
        return bare.toIntOrNull()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
