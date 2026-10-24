package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue

/**
 * Renders a [Frontmatter] in canonical form.
 *
 * Split out of [FrontmatterCodec] so the parser and the renderer can each stay
 * under the function budget: parsing answers "what did the author write" and
 * this answers "how is a synthesized block written back".
 */
object FrontmatterSerializer {
    /**
     * Renders frontmatter in canonical form.
     *
     * Only reached for frontmatter Tanseki synthesized or was handed as a JSON
     * map. A block that was parsed from a document is emitted verbatim by
     * MarkdownSerializer instead, so a user never has their quoting, key order,
     * or comments rewritten by editing the body.
     *
     * Key order is the caller's, and this is the same promise the docstring above
     * makes about the verbatim path. Sorting here would mean the same document
     * read one way kept its key order and written another way came back
     * alphabetised, so editing a note with an explicit frontmatter map would
     * reshuffle a block nobody asked to reorder — and on a store that versions
     * with Pijul that diff claims a change that was not made. It also put the two
     * seams at odds: the JSON field and the equivalent YAML block held identical
     * values and produced different bytes.
     *
     * Idempotency does not need the sort either. `documents:upsert` sorts its
     * own fingerprint before reserving a key, so two requests that differ only in
     * key order are still recognised as the same write and replay rather than
     * conflict — which is the property that actually matters there, and it is
     * enforced at the seam that owns it.
     */
    fun serialize(frontmatter: Frontmatter): String {
        require(frontmatter.values.keys.none { it in Frontmatter.CONTRACT_KEYS }) {
            "frontmatter values must not override contract keys"
        }

        return buildString {
            frontmatter.title?.let { appendLine("${Frontmatter.KEY_TITLE}: ${quote(it)}") }
            frontmatter.author?.let { appendLine("${Frontmatter.KEY_AUTHOR}: ${quote(it)}") }
            if (frontmatter.tags.isNotEmpty()) {
                val rendered = frontmatter.tags.joinToString(", ") { quote(it) }
                appendLine("${Frontmatter.KEY_TAGS}: [$rendered]")
            }
            if (frontmatter.aliases.isNotEmpty()) {
                val rendered = frontmatter.aliases.joinToString(", ") { quote(it) }
                appendLine("${Frontmatter.KEY_ALIASES}: [$rendered]")
            }
            frontmatter.updatedAt?.let { appendLine("${Frontmatter.KEY_UPDATED_AT}: ${quote(it.toString())}") }
            frontmatter.contentHash?.let { appendLine("${Frontmatter.KEY_CONTENT_HASH}: ${quote(it)}") }
            // Every map that reaches here is built by `associate` over an ordered
            // source, so this iterates in the order the caller supplied.
            for ((key, value) in frontmatter.values) {
                appendValue(this, quote(key), value, indent = 0)
            }
        }
    }

    /**
     * A collection of scalars renders in block style, because that is what a
     * person writes and what Obsidian round-trips. Anything nested inside a
     * collection renders in flow style instead, which keeps the emitter honest
     * without a combinatorial set of indentation cases.
     */
    fun appendValue(out: StringBuilder, key: String, value: FrontmatterValue, indent: Int) {
        when {
            value is SequenceValue && value.items.all(::isScalar) -> appendSequence(out, key, value, indent)
            value is MappingValue && value.entries.values.all(::isScalar) -> appendMapping(out, key, value, indent)
            else -> out.appendLine("$key: ${renderFlow(value)}")
        }
    }

    fun appendSequence(out: StringBuilder, key: String, value: SequenceValue, indent: Int) {
        if (value.items.isEmpty()) {
            out.appendLine("$key: []")
            return
        }
        out.appendLine("$key:")
        val pad = pad(indent + 1)
        for (item in value.items) {
            out.appendLine("$pad- ${renderScalar(item)}")
        }
    }

    fun appendMapping(out: StringBuilder, key: String, value: MappingValue, indent: Int) {
        if (value.entries.isEmpty()) {
            out.appendLine("$key: {}")
            return
        }
        out.appendLine("$key:")
        val pad = pad(indent + 1)
        for ((entryKey, entryValue) in value.entries) {
            out.appendLine("$pad${quote(entryKey)}: ${renderScalar(entryValue)}")
        }
    }

    fun pad(indent: Int): String = "  ".repeat(indent)

    fun isScalar(value: FrontmatterValue): Boolean =
        value is TextValue || value is NumberValue || value is BooleanValue

    fun renderScalar(value: FrontmatterValue): String =
        when (value) {
            is TextValue -> quote(value.value)
            is NumberValue -> value.value
            is BooleanValue -> value.value.toString()
            else -> renderFlow(value)
        }

    fun renderFlow(value: FrontmatterValue): String =
        when (value) {
            is SequenceValue -> {
                value.items.joinToString(", ", "[", "]") { renderFlow(it) }
            }

            is MappingValue -> {
                value.entries.entries.joinToString(", ", "{", "}") { (key, item) ->
                    "${quote(key)}: ${renderFlow(item)}"
                }
            }

            else -> {
                renderScalar(value)
            }
        }

    /**
     * Quotes unless the value is unambiguously a plain string.
     *
     * A leading digit never satisfies [SAFE_PLAIN], which is what keeps `42`,
     * `007`, and `1.5` quoted as the strings they were sent as; [RESERVED_PLAIN]
     * keeps `true`, `yes`, and `null` quoted for the same reason.
     */
    fun quote(value: String): String {
        validateSupported(value)
        if (SAFE_PLAIN.matches(value) && value.lowercase() !in RESERVED_PLAIN) return value

        return buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }
    }

    /** A scalar that can be written bare: starts with a letter, so it can never re-read as a number. */
    private val SAFE_PLAIN = Regex("[A-Za-z][A-Za-z0-9_./-]*")
    private val RESERVED_PLAIN = setOf("null", "true", "false", "yes", "no", "on", "off")
}

/**
 * Rejects control characters in a scalar, on the way in and on the way out.
 *
 * A YAML 1.1 parser is lenient about some of them inside a quoted scalar, so
 * a block can carry a NUL or a NEL through the parse and then fail to be
 * written back out. Refusing it at both ends means the store never holds a
 * value it cannot render, and an author learns about it from the write that
 * carried it rather than from the next one.
 */
internal fun validateSupported(value: String) {
    require(value.none { it.isISOControl() && it !in "\n\r\t" }) {
        "frontmatter scalars cannot contain unsupported control characters"
    }
}
