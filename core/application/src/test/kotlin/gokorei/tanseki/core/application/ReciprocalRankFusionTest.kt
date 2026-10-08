package gokorei.tanseki.core.application

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.ports.Hit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReciprocalRankFusionTest {
    @Test
    fun `documents appearing in both rankings outrank single-signal documents`() {
        val lexical = listOf(Hit(DocId("a"), 1.0, "snippet-a"), Hit(DocId("b"), 0.9))
        val vector = listOf(Hit(DocId("c"), 0.9), Hit(DocId("a"), 0.8))

        val fused = ReciprocalRankFusion.fuse(listOf(lexical, vector), 3)

        assertEquals(listOf(DocId("a"), DocId("c"), DocId("b")), fused.map { it.id })
        assertEquals("snippet-a", fused.first().snippet)
    }

    @Test
    fun `ties keep first-seen order`() {
        val lexical = listOf(Hit(DocId("a"), 1.0))
        val vector = listOf(Hit(DocId("b"), 1.0))

        assertEquals(
            listOf(DocId("a"), DocId("b")),
            ReciprocalRankFusion.fuse(listOf(lexical, vector), 2).map { it.id }
        )
    }

    @Test
    fun `limit and empty inputs are handled`() {
        val ranking = listOf(Hit(DocId("a"), 1.0), Hit(DocId("b"), 0.5), Hit(DocId("c"), 0.1))

        assertEquals(2, ReciprocalRankFusion.fuse(listOf(ranking), 2).size)
        assertTrue(ReciprocalRankFusion.fuse(listOf(emptyList()), 5).isEmpty())
        assertTrue(ReciprocalRankFusion.fuse(listOf(ranking), 0).isEmpty())
    }
}
