package gokorei.tanseki.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The value model has to hold a document's shape without flattening it.
 *
 * These live in `core:domain` rather than beside the codec because the model is
 * a domain concern: the codec decides what a YAML document means, and this
 * decides what a meaning is allowed to be. A list that only exists as a string
 * is where the flattening used to happen.
 */
class FrontmatterValueTest {
    @Test
    fun `a scalar encodes with its type so a filter can tell it from a string of the same text`() {
        assertNotEquals(TextValue("42").encode(), NumberValue("42").encode())
        assertNotEquals(TextValue("true").encode(), BooleanValue(true).encode())
    }

    @Test
    fun `sequences that would collide under comma joining stay distinct`() {
        // These two are one token apart if a sequence is joined with commas,
        // which is what the old stringified repr did.
        val joined = SequenceValue(listOf(TextValue("a"), TextValue("b")))
        val literal = SequenceValue(listOf(TextValue("a,b")))

        assertNotEquals(joined.encode(), literal.encode())
        assertEquals(listOf("a", "b"), textOf(joined))
        assertEquals(listOf("a,b"), textOf(literal))
    }

    @Test
    fun `mappings that would collide stay distinct`() {
        val split = MappingValue(mapOf("a" to TextValue("b")))
        val joined = MappingValue(mapOf("a" to TextValue("b:c")))

        assertNotEquals(split.encode(), joined.encode())
    }

    @Test
    fun `a collection has no scalar text of its own`() {
        assertNull(SequenceValue(listOf(TextValue("a"))).asText())
        assertNull(MappingValue(mapOf("k" to TextValue("v"))).asText())
    }

    @Test
    fun `leaves reach every scalar a nested value holds`() {
        val value =
            MappingValue(
                mapOf(
                    "one" to TextValue("a"),
                    "two" to SequenceValue(listOf(TextValue("b"), BooleanValue(false)))
                )
            )

        assertEquals(
            listOf(TextValue("a"), TextValue("b"), BooleanValue(false)),
            value.leaves()
        )
    }

    @Test
    fun `texts reads one scalar and several alike`() {
        val single = Frontmatter(values = mapOf("k" to TextValue("a")))
        val several = Frontmatter(values = mapOf("k" to SequenceValue(listOf(TextValue("a"), TextValue("b")))))

        assertEquals(listOf("a"), single.texts("k"))
        assertEquals(listOf("a", "b"), several.texts("k"))
    }

    @Test
    fun `text is null for a collection rather than a flattened string`() {
        val frontmatter = Frontmatter(values = mapOf("k" to SequenceValue(listOf(TextValue("a")))))

        assertNull(frontmatter.text("k"))
        assertEquals(listOf("a"), frontmatter.texts("k"))
    }

    @Test
    fun `an absent key reads as absent`() {
        val frontmatter = Frontmatter()

        assertNull(frontmatter.value("k"))
        assertNull(frontmatter.text("k"))
        assertEquals(emptyList<String>(), frontmatter.texts("k"))
    }

    @Test
    fun `a number that is not a number is refused at construction`() {
        // Fail here rather than at serialize time: a NumberValue that cannot be
        // encoded is a value nobody should have been able to build.
        assertThrows(IllegalArgumentException::class.java) { NumberValue("not a number") }
    }

    @Test
    fun `every contract key is named exactly once`() {
        assertEquals(
            setOf("title", "author", "tags", "aliases", "updated_at", "content_hash"),
            Frontmatter.CONTRACT_KEYS
        )
    }

    @Test
    fun `a sequence preserves order because order is meaning`() {
        val forward = SequenceValue(listOf(TextValue("a"), TextValue("b")))
        val backward = SequenceValue(listOf(TextValue("b"), TextValue("a")))

        assertNotEquals(forward.encode(), backward.encode())
        assertTrue(forward.encode().length < backward.encode().length + 1)
    }

    private fun textOf(value: FrontmatterValue): List<String> = value.leaves().mapNotNull { it.asText() }
}
