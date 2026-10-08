package gokorei.tanseki.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EdgePropsTest {
    @Test
    fun `round-trips values containing the structural characters`() {
        val props =
            mapOf(
                "label" to "a&b=c",
                "path" to "C:\\notes\\a&b",
                "empty" to ""
            )

        assertEquals(props, EdgeProps.decode(EdgeProps.encode(props)))
    }

    @Test
    fun `round-trips an empty map`() {
        assertEquals(emptyMap<String, String>(), EdgeProps.decode(EdgeProps.encode(emptyMap())))
        assertEquals(emptyMap<String, String>(), EdgeProps.decode(""))
    }

    @Test
    fun `still decodes records written before escaping existed`() {
        // The pre-escape encoding is unchanged for values without separators, so
        // existing vaults and rows keep decoding.
        assertEquals(mapOf("label" to "review", "x" to "1"), EdgeProps.decode("label=review&x=1"))
        assertEquals(mapOf("key" to ""), EdgeProps.decode("key="))
        assertEquals(mapOf("key" to ""), EdgeProps.decode("key"))
    }

    @Test
    fun `distinct values do not collide`() {
        val one = EdgeProps.encode(mapOf("k" to "a&b"))
        val two = EdgeProps.encode(mapOf("k" to "a"))
        assertEquals(false, one == two)
    }
}
