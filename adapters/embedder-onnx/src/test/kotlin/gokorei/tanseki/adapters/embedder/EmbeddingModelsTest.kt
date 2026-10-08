package gokorei.tanseki.adapters.embedder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmbeddingModelsTest {
    @Test
    fun `default is all-MiniLM-L6-v2 with a 384-dimensional output`() {
        assertEquals("all-MiniLM-L6-v2", EmbeddingModels.DEFAULT.id)
        assertEquals(384, EmbeddingModels.DEFAULT.dimensions)
        assertEquals(256, EmbeddingModels.DEFAULT.maxLength)
        assertTrue(EmbeddingModels.DEFAULT.approxBytes in 10L * 1024 * 1024..500L * 1024 * 1024)
    }

    @Test
    fun `resolve is swappable by id and falls back to the default`() {
        assertEquals(EmbeddingModels.ALL_MINILM_L6_V2, EmbeddingModels.resolve("all-MiniLM-L6-v2"))
        assertNull(EmbeddingModels.resolve("nope"))
        assertNull(EmbeddingModels.resolve("  "))
        assertNull(EmbeddingModels.resolve(null))

        assertEquals(EmbeddingModels.DEFAULT, EmbeddingModels.resolveOrDefault("nope"))
        assertEquals(EmbeddingModels.DEFAULT, EmbeddingModels.resolveOrDefault(null))
    }
}
