package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Frontmatter is YAML, so it is parsed as YAML.
 *
 * The previous codec was a hand-rolled subset: block sequences were understood
 * for `tags` alone, an inline list became a string that had to be re-parsed by
 * the edge deriver, and a nested map had its children hoisted to sibling
 * top-level keys — silently, so an `fm_owner` filter matched documents that had
 * no `owner`. Each test here pins one shape that the subset got wrong.
 */
class FrontmatterYamlTest {
    @Test
    fun `a block sequence round-trips as a sequence`() {
        val parsed = FrontmatterCodec.parse("files:\n  - src/a.py\n  - src/b.py")

        assertEquals(
            SequenceValue(listOf(TextValue("src/a.py"), TextValue("src/b.py"))),
            parsed.values["files"]
        )
        assertEquals(listOf("src/a.py", "src/b.py"), parsed.texts("files"))
    }

    @Test
    fun `an inline sequence round-trips as a sequence and not as its own text`() {
        val parsed = FrontmatterCodec.parse("files: [src/a.py, src/b.py]")

        assertEquals(
            SequenceValue(listOf(TextValue("src/a.py"), TextValue("src/b.py"))),
            parsed.values["files"]
        )
    }

    @Test
    fun `a nested map does not hoist its children to sibling keys`() {
        val parsed = FrontmatterCodec.parse("meta:\n  owner: me\ntitle: T")

        assertEquals(MappingValue(mapOf("owner" to TextValue("me"))), parsed.values["meta"])
        // The hoist is the bug this pins: `owner` must not exist at the top level.
        assertNull(parsed.values["owner"])
        assertEquals("T", parsed.title)
    }

    @Test
    fun `a folded scalar stays one scalar and does not empty the block`() {
        val parsed = FrontmatterCodec.parse("desc: >-\n  one\n  two\ntitle: T")

        assertEquals(TextValue("one two"), parsed.values["desc"])
        assertEquals("T", parsed.title)
    }

    @Test
    fun `a literal block scalar keeps its newlines`() {
        val parsed = FrontmatterCodec.parse("body: |-\n  one\n  two")

        assertEquals(TextValue("one\ntwo"), parsed.values["body"])
    }

    @Test
    fun `a value with nothing after it is absent rather than the empty string`() {
        // A null is the absence of a value, not a value, so neither `summary:` nor an explicit
        // `summary: null` leaves an entry. The empty string is a different thing and stays one.
        val blank = FrontmatterCodec.parse("summary:\ntitle: T")
        val explicit = FrontmatterCodec.parse("summary: null\ntitle: T")

        assertNull(blank.values["summary"])
        assertNull(blank.text("summary"))
        assertNull(explicit.values["summary"])
        assertEquals(TextValue(""), FrontmatterCodec.parse("summary: \"\"\ntitle: T").values["summary"])
    }

    @Test
    fun `a null inside a collection is dropped rather than carried`() {
        val sequence = FrontmatterCodec.parse("items:\n- a\n- null\n- b")
        val mapping = FrontmatterCodec.parse("nested:\n  keep: yes\n  drop: null")

        assertEquals(SequenceValue(listOf(TextValue("a"), TextValue("b"))), sequence.values["items"])
        assertEquals(MappingValue(mapOf("keep" to TextValue("yes"))), mapping.values["nested"])
    }

    @Test
    fun `a string that reads as a number stays a string`() {
        // The document drew this distinction with its quotes. Losing it is how a
        // `pr: "42"` becomes `pr: 42` on the way through the store.
        assertEquals(TextValue("42"), FrontmatterCodec.parse("pr: \"42\"").values["pr"])
        assertEquals(TextValue("007"), FrontmatterCodec.parse("ver: \"007\"").values["ver"])
        assertEquals(TextValue("true"), FrontmatterCodec.parse("flag: \"true\"").values["flag"])
    }

    @Test
    fun `an unquoted scalar resolves to the type YAML gives it`() {
        val parsed = FrontmatterCodec.parse("n: 42\nf: 1.5\nb: true")

        assertEquals(NumberValue("42"), parsed.values["n"])
        assertEquals(NumberValue("1.5"), parsed.values["f"])
        assertEquals(BooleanValue(true), parsed.values["b"])
    }

    @Test
    fun `a sequence survives a serialize and parse round trip`() {
        val original =
            Frontmatter(values = mapOf("files" to SequenceValue(listOf(TextValue("a.py"), TextValue("b.py")))))

        val reparsed = FrontmatterCodec.parse(FrontmatterCodec.serialize(original))

        assertEquals(original, reparsed)
        // And the rendered form is the block style a person would have written.
        assertTrue(
            FrontmatterCodec.serialize(original).contains("files:\n  - a.py\n  - b.py"),
            FrontmatterCodec.serialize(original)
        )
    }

    @Test
    fun `a nested map survives a serialize and parse round trip`() {
        val original =
            Frontmatter(
                values =
                    mapOf(
                        "meta" to
                            MappingValue(
                                mapOf(
                                    "owner" to TextValue("me"),
                                    "tier" to NumberValue("2")
                                )
                            )
                    )
            )

        assertEquals(original, FrontmatterCodec.parse(FrontmatterCodec.serialize(original)))
    }

    @Test
    fun `a value containing a comma is one value`() {
        // The reason the old edge deriver comma-split a string: it made the
        // separator load-bearing, and a path containing one could not survive.
        val parsed = FrontmatterCodec.parse("files:\n  - a,b.py\n  - c.py")

        assertEquals(listOf("a,b.py", "c.py"), parsed.texts("files"))
    }

    @Test
    fun `a value that looks like the old stringified repr is still one value`() {
        val parsed = FrontmatterCodec.parse("files: [\"[a, b]\"]")

        assertEquals(listOf("[a, b]"), parsed.texts("files"))
    }

    @Test
    fun `leaves reach nested scalars so a filter can name one`() {
        val value = MappingValue(mapOf("a" to TextValue("one"), "b" to SequenceValue(listOf(TextValue("two")))))

        assertEquals(listOf(TextValue("one"), TextValue("two")), value.leaves())
    }

    @Test
    fun `encoding distinguishes values that would otherwise collide`() {
        // Every one of these is a term in the derived index, and two of them
        // sharing a term would make a filter match the wrong document.
        val encodings =
            listOf(
                TextValue("a,b"),
                SequenceValue(listOf(TextValue("a"), TextValue("b"))),
                SequenceValue(listOf(TextValue("a,b"))),
                TextValue("42"),
                NumberValue("42"),
                BooleanValue(true),
                MappingValue(mapOf("k" to TextValue("v")))
            ).map { it.encode() }

        assertEquals(encodings.size, encodings.toSet().size, "encodings must be distinct: $encodings")
    }

    @Test
    fun `a duplicate key is refused rather than silently resolved`() {
        // Picking a winner quietly is the kind of answer that looks right and is
        // not, and a note with `title` twice is malformed.
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                FrontmatterCodec.parse("title: one\ntitle: two")
            }
        assertTrue(failure.message!!.contains("duplicate", ignoreCase = true), failure.message!!)
    }

    @Test
    fun `malformed YAML is refused with the engine's reason`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                FrontmatterCodec.parse("title: \"unterminated")
            }
        assertTrue(failure.message!!.startsWith("malformed frontmatter:"), failure.message!!)
    }

    @Test
    fun `a nesting bomb is refused before it is parsed`() {
        // The engine has no depth limit of its own, so the bound is applied to
        // the text: by the time a structure exists, the stack is already spent.
        val bomb = "deep: " + "[".repeat(5_000) + "]".repeat(5_000)

        val failure = assertThrows(IllegalArgumentException::class.java) { FrontmatterCodec.parse(bomb) }
        assertTrue(failure.message!!.contains("too deep"), failure.message!!)
    }

    @Test
    fun `an alias bomb is refused`() {
        // A kilobyte that expands to a megabyte, which is the shape a naive
        // recursive parser will happily walk.
        val bomb =
            buildString {
                appendLine("a: &a [x, x, x, x, x, x, x, x, x, x]")
                append("b: ")
                repeat(60) { append("&a ") }
            }

        assertThrows(IllegalArgumentException::class.java) { FrontmatterCodec.parse(bomb) }
    }

    @Test
    fun `tags still read as a list of strings`() {
        val parsed = FrontmatterCodec.parse("tags:\n  - work\n  - inbox")

        assertEquals(listOf("work", "inbox"), parsed.tags)
    }

    @Test
    fun `an updated_at still parses as an instant`() {
        val parsed = FrontmatterCodec.parse("updated_at: 2026-01-02T03:04:05Z")

        assertEquals(kotlin.time.Instant.parse("2026-01-02T03:04:05Z"), parsed.updatedAt)
    }

    @Test
    fun `a contract key cannot be shadowed by a value`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrontmatterCodec.serialize(Frontmatter(values = mapOf("title" to TextValue("x"))))
        }
    }
}
