package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.BooleanValue
import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.FrontmatterValue
import gokorei.tanseki.core.domain.MappingValue
import gokorei.tanseki.core.domain.NumberValue
import gokorei.tanseki.core.domain.SequenceValue
import gokorei.tanseki.core.domain.TextValue
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import kotlin.time.Instant

/**
 * Parses and serializes a YAML frontmatter block.
 *
 * The block is real YAML, parsed by a real YAML implementation, so a sequence is
 * a sequence and a nested map is a nested map. The typed [Frontmatter] is an
 * honest index of the block rather than an approximation of it, which is the
 * whole point: an earlier version of this file hand-parsed a subset and quietly
 * turned every collection into a string.
 *
 * Frontmatter is untrusted input — it arrives from a user's vault and from the
 * HTTP seam — so parsing is bounded. The engine's own constructor builds only
 * the standard YAML types and has no path to constructing an arbitrary Java
 * class, which is the structural form of the fix for the deserialization RCE
 * that the older `org.yaml:snakeyaml` coordinate was vulnerable to. Alias
 * expansion and input size are capped, and nesting depth is checked here
 * because the engine does not check it: a small document can otherwise recurse
 * far enough to exhaust the stack.
 */
object FrontmatterCodec {
    /**
     * A scalar that can be written bare: it starts with a letter, so it cannot be
     * re-read as a number, and it is not a word a YAML 1.1 resolver turns into a
     * boolean or null. Anything else gets quoted, including a string that merely
     * looks like `42` or `true`.
     */
    private val SAFE_PLAIN = Regex("[A-Za-z][A-Za-z0-9_./-]*")
    private val RESERVED_PLAIN = setOf("null", "true", "false", "yes", "no", "on", "off")

    /** An alias bomb is a small document that expands to a large one. */
    private const val MAX_ALIASES_FOR_COLLECTIONS = 50

    /** Generous for a frontmatter block, and bounded so a hostile one cannot exhaust memory. */
    private const val MAX_CODE_POINTS = 1 shl 20

    /**
     * Deepest nesting accepted, counted before the parse.
     *
     * The engine has no depth limit of its own, and parsing a deeply nested
     * document recurses once per level, so the bound has to be applied to the
     * text rather than to the result — by the time a structure exists, the stack
     * has already been spent.
     */
    private const val MAX_NESTING_DEPTH = 20

    fun parse(block: String): Frontmatter {
        require(depthWithinLimit(block)) { "frontmatter nesting is too deep" }
        val root = load(block) as? Map<*, *> ?: return Frontmatter()
        return Frontmatter(
            title = root[Frontmatter.KEY_TITLE]?.let { toValue(it) }?.let(::scalarText),
            author = root[Frontmatter.KEY_AUTHOR]?.let { toValue(it) }?.let(::scalarText),
            tags = root[Frontmatter.KEY_TAGS]?.let { toValue(it) }?.let(::tagStrings) ?: emptyList(),
            aliases = root[Frontmatter.KEY_ALIASES]?.let { toValue(it) }?.let(::tagStrings) ?: emptyList(),
            updatedAt =
                root[Frontmatter.KEY_UPDATED_AT]
                    ?.let { toValue(it) }
                    ?.let(::scalarText)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            contentHash = root[Frontmatter.KEY_CONTENT_HASH]?.let { toValue(it) }?.let(::scalarText),
            values = extras(root)
        )
    }

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
     * A fresh loader per parse.
     *
     * `Load` is cheap to build and not documented as thread-safe, and frontmatter
     * is parsed on every indexed document, so a shared instance would be a
     * concurrency question bought for nothing.
     *
     * The single-argument `Load` builds a `StandardConstructor`, which handles
     * only the standard YAML types. That is the safety property that matters
     * here: there is no constructor an untrusted document can name.
     *
     * Duplicate keys are rejected rather than silently resolved to the last one.
     * A block that declares `title` twice is malformed, and picking a winner
     * quietly is the kind of answer that looks right and is not.
     *
     * Engine failures are rethrown as [IllegalArgumentException]. That is the
     * contract every caller already handles — [MarkdownParser] catches exactly
     * that, and it is the narrowest signal available that the block itself is
     * bad, as opposed to a fault in Tanseki.
     */
    private fun load(block: String): Any? {
        val settings =
            LoadSettings
                .builder()
                .setMaxAliasesForCollections(MAX_ALIASES_FOR_COLLECTIONS)
                .setCodePointLimit(MAX_CODE_POINTS)
                .setAllowRecursiveKeys(false)
                .setAllowDuplicateKeys(false)
                .setAllowNonScalarKeys(false)
                .build()
        return try {
            Load(settings).loadFromString(block)
        } catch (failure: YamlEngineException) {
            // The engine names the line and the problem; the caller needs both,
            // because "invalid request" tells an author nothing about their note.
            val where = (failure as? MarkedYamlEngineException)?.problem ?: "unparseable YAML"
            throw IllegalArgumentException("malformed frontmatter: $where", failure)
        }
    }

    /**
     * Whether [block] nests no deeper than [MAX_NESTING_DEPTH].
     *
     * Two forms have to be counted. Flow style nests with brackets; block style
     * nests with indentation, and YAML requires at least one more column per
     * level and forbids tabs, so the widest indentation in the block is an upper
     * bound on its block-nesting depth. Taking the larger of the two bounds
     * both, and rejecting before the parse, is what keeps the recursion bounded.
     */
    private fun depthWithinLimit(block: String): Boolean {
        var widestIndent = 0
        var deepestBrackets = 0
        for (line in block.lineSequence()) {
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) continue
            if (indent > widestIndent) widestIndent = indent
            deepestBrackets = maxOf(deepestBrackets, bracketDepth(line))
        }
        val blockDepth = if (widestIndent == 0) 1 else widestIndent + 1
        return maxOf(blockDepth, deepestBrackets) <= MAX_NESTING_DEPTH
    }

    /** The deepest bracket nesting a single line reaches. */
    private fun bracketDepth(line: String): Int {
        var open = 0
        var deepest = 0
        for (character in line) {
            when (character) {
                '[', '{' -> {
                    open++
                    if (open > deepest) deepest = open
                }

                ']', '}' -> {
                    if (open > 0) open--
                }
            }
        }
        return deepest
    }

    /** Every non-contract key, in the order the document declared them. */
    private fun extras(root: Map<*, *>): Map<String, FrontmatterValue> {
        val values = LinkedHashMap<String, FrontmatterValue>()
        for ((rawKey, rawValue) in root) {
            val key = rawKey?.toString() ?: continue
            if (key !in Frontmatter.CONTRACT_KEYS) toValue(rawValue)?.let { values[key] = it }
        }
        return values
    }

    /**
     * Maps what the loader returned onto the value model, or `null` when there is no value.
     *
     * A quoted scalar arrives as a `String` and an unquoted one as whatever the
     * YAML resolver made of it, so `"42"` stays text and `42` becomes a number —
     * which is the distinction the document actually drew.
     *
     * A YAML null maps to `null` rather than to a value: `key:` has nothing after it, so the
     * key is left out of the model and reads as absent. It is the one input with no shape to
     * carry, and giving it a shape would make every reader decide what a null means when none
     * of them can. A null *inside* a collection is dropped the same way, so `[a, null]` is the
     * sequence `[a]`.
     */
    private fun toValue(raw: Any?): FrontmatterValue? =
        when (raw) {
            null -> null

            is Boolean -> BooleanValue(raw)

            is String -> text(raw)

            is Number -> NumberValue(raw.toString())

            is Map<*, *> -> mapping(raw)

            is Iterable<*> -> SequenceValue(raw.mapNotNull(::toValue))

            is Array<*> -> SequenceValue(raw.mapNotNull(::toValue))

            // A YAML 1.1 timestamp resolves to a Date. There is no date in the
            // model: it is carried as its ISO text so it survives a round trip,
            // and a block that was parsed from a document is never re-rendered.
            is java.util.Date -> TextValue(raw.toInstant().toString())

            else -> text(raw.toString())
        }

    private fun text(value: String): TextValue {
        validateSupported(value)
        return TextValue(value)
    }

    private fun mapping(raw: Map<*, *>): MappingValue {
        val entries = LinkedHashMap<String, FrontmatterValue>()
        for ((key, value) in raw) {
            key?.toString()?.let { name -> toValue(value)?.let { entries[name] = it } }
        }
        return MappingValue(entries)
    }

    /** A string-list contract key (`tags`, `aliases`): one scalar or a collection of them. */
    private fun tagStrings(value: FrontmatterValue): List<String> =
        when (value) {
            is SequenceValue -> value.items.mapNotNull { scalarText(it) }
            else -> scalarText(value)?.let(::listOf) ?: emptyList()
        }

    private fun scalarText(value: FrontmatterValue): String? = value.asText()

    // --- Rendering ----------------------------------------------------------

    /**
     * A collection of scalars renders in block style, because that is what a
     * person writes and what Obsidian round-trips. Anything nested inside a
     * collection renders in flow style instead, which keeps the emitter honest
     * without a combinatorial set of indentation cases.
     */
    private fun appendValue(out: StringBuilder, key: String, value: FrontmatterValue, indent: Int) {
        when {
            value is SequenceValue && value.items.all(::isScalar) -> appendSequence(out, key, value, indent)
            value is MappingValue && value.entries.values.all(::isScalar) -> appendMapping(out, key, value, indent)
            else -> out.appendLine("$key: ${renderFlow(value)}")
        }
    }

    private fun appendSequence(out: StringBuilder, key: String, value: SequenceValue, indent: Int) {
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

    private fun appendMapping(out: StringBuilder, key: String, value: MappingValue, indent: Int) {
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

    private fun pad(indent: Int): String = "  ".repeat(indent)

    private fun isScalar(value: FrontmatterValue): Boolean =
        value is TextValue || value is NumberValue || value is BooleanValue

    private fun renderScalar(value: FrontmatterValue): String =
        when (value) {
            is TextValue -> quote(value.value)
            is NumberValue -> value.value
            is BooleanValue -> value.value.toString()
            else -> renderFlow(value)
        }

    private fun renderFlow(value: FrontmatterValue): String =
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
    private fun quote(value: String): String {
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

    /**
     * Rejects control characters in a scalar, on the way in and on the way out.
     *
     * A YAML 1.1 parser is lenient about some of them inside a quoted scalar, so
     * a block can carry a NUL or a NEL through the parse and then fail to be
     * written back out. Refusing it at both ends means the store never holds a
     * value it cannot render, and an author learns about it from the write that
     * carried it rather than from the next one.
     */
    private fun validateSupported(value: String) {
        require(value.none { it.isISOControl() && it !in "\n\r\t" }) {
            "frontmatter scalars cannot contain unsupported control characters"
        }
    }
}
