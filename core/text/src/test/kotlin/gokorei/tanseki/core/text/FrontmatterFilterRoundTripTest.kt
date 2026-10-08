package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.FrontmatterFilterValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import gokorei.tanseki.core.domain.resolveFrontmatterFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A filter value resolves to the value the same text would have been stored as.
 *
 * This is the invariant the whole resolution rests on, and it is not checkable from
 * either module alone: the resolution lives in `core:domain` and the parser it inverts
 * lives here, so the only place both can be compared is a test that depends on both.
 *
 * The comparison is on the encoded term rather than on the type, because the term is
 * what the index actually stores and compares. Two values of the same type can encode
 * differently — `1e3` and `1000.0` are both numbers and only one of them is what a
 * document holds — and a resolution that agreed on types while disagreeing on spelling
 * would still match nothing.
 *
 * Every literal here is one a vault really contains. If the engine's scalar resolution
 * ever changes, this fails rather than leaving queries that silently match nothing.
 */
class FrontmatterFilterRoundTripTest {
    private val literals =
        listOf(
            // Numbers, including the spellings the store does not keep verbatim.
            "42",
            "0",
            "-3",
            "1.5",
            "12.30",
            "1e3",
            "2e-1",
            "5.",
            "-0",
            "9007199254740993",
            "123456789012345678901234567890",
            ".inf",
            "-.inf",
            ".nan",
            // `-.nan` has no sign once the resolver has turned it into a double, so
            // the store keeps the *text* `-.nan` rather than a number. The filter
            // side has to agree, or it would go looking for `n:NaN` and find nothing.
            "-.nan",
            // Upper case is text, because the YAML 1.2 core schema the parser uses
            // only resolves the lower-case spellings. Asserted against the parser
            // rather than hard-coded, so a resolver upgrade that starts accepting
            // `.Inf` fails here instead of silently unmatching stored documents.
            ".Inf",
            ".NAN",
            // Numbers the write path leaves as text, so the filter must too.
            "0042",
            "007",
            "+42",
            "1_000",
            "0x1F",
            "00.5",
            "1.2.3",
            "v1.2.3",
            ".5",
            // Booleans, and the near misses that are strings.
            "true",
            "false",
            "TRUE",
            "yes",
            "no",
            "on",
            // Text of every shape a frontmatter value takes.
            "org/repo",
            "src/a.py",
            "ABC-1",
            "draft",
            "a b"
        )

    @Test
    fun `every filter value resolves to the value the same text would have been stored as`() {
        literals.forEach { literal ->
            val stored = FrontmatterCodec.parse("k: $literal").value("k")
            val resolved = resolveFrontmatterFilter(literal)

            assertEquals(
                FrontmatterFilterValue.Resolved(stored!!),
                resolved,
                "[$literal] resolves differently from what it would be stored as"
            )
        }
    }

    @Test
    fun `a quoted filter value resolves to the value the same quoted text would have been stored as`() {
        listOf("\"42\"", "'42'", "\"true\"", "\"org/repo\"", "\"\"", "'1.2.3'").forEach { quoted ->
            val stored = FrontmatterCodec.parse("k: $quoted").value("k")

            assertEquals(
                FrontmatterFilterValue.Resolved(stored!!),
                resolveFrontmatterFilter(quoted),
                "[$quoted] resolves differently from what it would be stored as"
            )
        }
    }

    @Test
    fun `the store holds a quoted string and the filter reaches it only when quoted too`() {
        // The asymmetry is the point of the quoting rule, asserted from both ends: the
        // document keeps its quotes, so the unquoted filter misses it and the quoted one
        // does not. A resolution that ignored quotes would make them the same query.
        val stored = FrontmatterCodec.parse("k: \"42\"").value("k")

        assertEquals("s2:42", stored!!.encode())
        assertEquals(NumberValue("42").encode(), resolveFrontmatterFilter("42").resolved().encode())
        assertEquals(stored.encode(), resolveFrontmatterFilter("\"42\"").resolved().encode())
    }

    @Test
    fun `a null is absent, and a sequence is not a filter value`() {
        // A `key:` with nothing after it has no value, so it is not stored at all: there is no
        // null state for `fm=` to address, which is why the old "no spelling for null" gap is
        // gone rather than answered. What a filter value still cannot be is the empty string —
        // `fm=pr=` is far more often a caller who forgot the value than one reaching for `""` —
        // and `null` resolves to the *string* "null", which is a value a document can hold.
        assertNull(FrontmatterCodec.parse("k:").value("k"))
        assertNull(FrontmatterCodec.parse("k: null").value("k"))

        assertEquals(
            FrontmatterFilterValue.Invalid("has no value; write \"\" for the empty string"),
            resolveFrontmatterFilter("")
        )
        assertEquals(TextValue("null"), resolveFrontmatterFilter("null").resolved())

        // A sequence is the other shape a filter value is not: a filter value is one scalar,
        // and the MCP seam refuses a structured filter outright rather than flattening it to a
        // string that matches nothing.
        assertEquals(
            SequenceValue(listOf(TextValue("a"), TextValue("b"))),
            FrontmatterCodec.parse("k: [a, b]").value("k")
        )
        assertEquals(TextValue("[a, b]"), resolveFrontmatterFilter("[a, b]").resolved())
    }

    private fun FrontmatterFilterValue.resolved() =
        (this as FrontmatterFilterValue.Resolved).value
}
