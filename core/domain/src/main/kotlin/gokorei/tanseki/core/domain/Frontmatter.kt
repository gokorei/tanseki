package gokorei.tanseki.core.domain

import kotlin.time.Instant

/**
 * One value from a frontmatter block, in the shape it was written.
 *
 * Collections are modelled rather than flattened, because a flattened list is a
 * string that has to be re-parsed to be useful and cannot round-trip: a path
 * containing the separator is indistinguishable from two paths. Everything
 * downstream — the edge deriver, the `fm_*` filter fields — reads the shape
 * here instead of guessing at one.
 *
 * There is no null value. A YAML `key:` with nothing after it has no value to model, and
 * nothing downstream could interpret a null, so it is the key being *absent* rather than a
 * state of its own. The only two states a key has are "a value" and "no value".
 */
sealed interface FrontmatterValue {
    /**
     * A stable, injective term for this value.
     *
     * The Lookup writes `encode()` into a Lucene `StringField` and into a
     * Meilisearch filter attribute, and a query builds the same term to match
     * it. So it must be deterministic and two different values must never
     * produce the same term — otherwise a filter matches a document it should
     * not. Length prefixes are what make that hold: a bare `s:$value` would let
     * `["a,b"]` and `["a", "b"]` collide.
     */
    fun encode(): String

    /** This value as text, or `null` when it is a collection. */
    fun asText(): String?

    /**
     * Every scalar leaf, in document order.
     *
     * A collection is filterable by its elements, which is what makes
     * `fm_files=…a.py` match a document whose `files` is a two-element list
     * rather than only matching the list as a whole.
     */
    fun leaves(): List<FrontmatterValue>
}

/** A YAML string scalar. Kept distinct from [NumberValue] even when it looks like one. */
data class TextValue(val value: String) : FrontmatterValue {
    override fun encode(): String = "s${value.length}:$value"

    override fun asText(): String = value

    override fun leaves(): List<FrontmatterValue> = listOf(this)
}

/** A YAML number, held as the text the document used so precision is not lost. */
data class NumberValue(val value: String) : FrontmatterValue {
    init {
        require(value.toDoubleOrNull() != null) { "frontmatter number is invalid" }
    }

    override fun encode(): String = "n:$value"

    override fun asText(): String = value

    override fun leaves(): List<FrontmatterValue> = listOf(this)
}

/**
 * A filter value that arrived as text: either the [FrontmatterValue] it names, or
 * the reason it names none.
 *
 * The result is a pair rather than a bare value because an input that cannot be
 * resolved has to be *reported*. A filter that quietly matches nothing reads as
 * "no such document", which is the one answer that sends a caller off to check
 * their data instead of their query.
 */
sealed interface FrontmatterFilterValue {
    /** The term a filter compares against is [value]'s [FrontmatterValue.encode]. */
    data class Resolved(val value: FrontmatterValue) : FrontmatterFilterValue

    /** [reason] says what is wrong, in the caller's terms and without naming a key. */
    data class Invalid(val reason: String) : FrontmatterFilterValue
}

/**
 * The message a caller sees for a rejected filter, naming the parameter it came from.
 *
 * Built here rather than at each seam so the HTTP route and the MCP tool reject the
 * same input with the same words. Two wordings for one rule is how two seams come
 * to disagree, and a caller who is told "frontmatter filter 'pr=' has no value"
 * knows which parameter to fix without guessing.
 */
fun FrontmatterFilterValue.Invalid.explain(filter: String): String = "frontmatter filter '$filter' $reason"

/**
 * Resolves a `key=value` filter's value, written as text, into the [FrontmatterValue] it names.
 *
 * Frontmatter is typed and the index keeps that typing: `pr: 42` is stored as the term
 * `n:42` and `pr: "42"` as `s2:42`, and those are different documents. A query
 * parameter is not typed — `?fm=pr=42` arrives as the characters `42` — so the type has
 * to be recovered, and the only defensible rule is **the one a YAML or JSON scalar
 * already follows**: resolve the text the way the parser on the write side would have.
 * Resolution is the inverse of parsing, and the invariant worth protecting is that a
 * value which parses as a number on write is filterable as a number.
 *
 * | written                    | resolves to                    |
 * |----------------------------|--------------------------------|
 * | `42`, `1.5`, `-3`          | [NumberValue]                  |
 * | `true`, `false`            | [BooleanValue]                 |
 * | `"42"`, `'42'`             | [TextValue] `"42"` — quoted is text |
 * | `org/repo`, `src/a.py`     | [TextValue]                    |
 * | `0042`, `1.2.3`, `+42`     | [TextValue]                    |
 *
 * **Quoting is the disambiguator, not decoration.** Unquoted `42` is the caller's claim
 * that the stored value is the *number* 42, so it will not reach a document holding the
 * *string* `"42"`; reaching that one takes `fm=pr="42"`. That is the whole point: the
 * typed frontmatter model exists so those two documents are distinguishable, and a filter
 * that guessed would collapse them. The cost is that an unquoted numeric-looking value
 * which the document stores as a string now returns zero rather than the document — and
 * that zero is informative. It means "you asked for a number and this is a string", and
 * the answer is one quote away, where the previous behaviour (every value was compared
 * as text) meant the same query silently worked for strings and silently failed for
 * numbers.
 *
 * A value that is merely *unusual* is still text, never an error. `1.2.3` is a version,
 * `0042` is a zero-padded identifier, `org/repo` is a path-shaped string, and all three
 * are legitimate filter values that resolve to [TextValue]. Only a structurally malformed
 * input is rejected: an empty value, or a quoted value that is not closed.
 *
 * `1.2.3` being text rather than an error is the case most likely to look arbitrary to
 * the next reader, so to be explicit: it is text because it is not a number. Two dots and
 * no exponent is not a spelling any number has, and rejecting it would break a working
 * query to express a doubt about a shape. `0042` is text for the same reason, and by the
 * same token the write path's own resolver keeps it a string — a zero-padded digit string
 * is a formatting choice, and both `FrontmatterCodec`'s writer and this reader agree.
 *
 * `true` and `false` are matched lowercase only, as the YAML 1.2 core schema resolves
 * them. `TRUE` is therefore text, deliberately rather than accidentally: the write side
 * stores `TRUE` as the string `TRUE`, so treating it as a boolean here would break the
 * inverse this function exists to provide.
 *
 * Known limit: a value whose text *begins* with a quote is only reachable quoted, and a
 * quoted value may not contain its own quote character — there are no escape sequences,
 * because a second spelling of the same string is a second grammar to keep straight with
 * nothing to check it against. Such a value remains reachable through a text query.
 */
fun resolveFrontmatterFilter(raw: String): FrontmatterFilterValue =
    when {
        raw.isEmpty() -> {
            FrontmatterFilterValue.Invalid("has no value; write \"\" for the empty string")
        }

        raw.startsWith('"') || raw.startsWith('\'') -> {
            quotedFilterValue(raw)
        }

        // Lowercase only, and case-sensitively so: see the KDoc above.
        raw == "true" -> {
            FrontmatterFilterValue.Resolved(BooleanValue(true))
        }

        raw == "false" -> {
            FrontmatterFilterValue.Resolved(BooleanValue(false))
        }

        else -> {
            FrontmatterFilterValue.Resolved(canonicalNumber(raw)?.let(::NumberValue) ?: TextValue(raw))
        }
    }

/**
 * Resolves a whole `key=value` filter, refusing a key `fm=` cannot address.
 *
 * A contract key is not a frontmatter value: [Frontmatter.values] must not contain one and both
 * lookups index only `values`, so no `fm_tags` term exists to match. `fm=tags=review` was
 * therefore well-formed, meaningful to the caller, and guaranteed to return nothing — which
 * reads as "no document carries that tag" rather than "this store cannot express the question",
 * sending an operator to inspect data that is perfectly fine. Every other unusable filter
 * already comes back as a named rejection, so a contract key is one too, and the rejection
 * points at the parameter that does work.
 *
 * Refusing is chosen over indexing the contract keys deliberately because the two are not
 * equivalent in cost. `tags` is already indexed raw for `?tags=`, so an `fm_tags` field would
 * give one concept a second spelling; `updated_at` is an `Instant` rather than a
 * [FrontmatterValue] and has no `encode()` at all, so "index every contract key" cannot be
 * honoured without inventing a term format for a type the value model does not cover; and the
 * pending overlay compares `values[key]` directly, so indexing without changing it there would
 * answer a filter-only query differently depending on whether a document had been projected
 * yet.
 *
 * The value grammar is untouched and stays key-independent in [resolveFrontmatterFilter], which
 * is where the rules live and what they are tested against: a value resolves the same however
 * it arrived, and only the key decides whether `fm=` is a way to ask.
 */
fun resolveFrontmatterFilter(
    key: String,
    raw: String
): FrontmatterFilterValue =
    when (key) {
        Frontmatter.KEY_TAGS -> FrontmatterFilterValue.Invalid("is a contract key; use the tags filter")
        in Frontmatter.CONTRACT_KEYS -> FrontmatterFilterValue.Invalid("is a contract key and is not filterable")
        else -> resolveFrontmatterFilter(raw)
    }

/**
 * The text inside [raw]'s quotes, or a reason the quoting does not close.
 *
 * An unterminated quote has to be an error rather than a fallback to the literal text
 * `"42`. Quoting is how a caller forces a string, so a broken quote that silently became
 * the text `42` would return the *string* documents for a request that asked for the
 * number — the wrong answer, confidently, in the one case the caller wrote extra
 * characters precisely to avoid it.
 *
 * The quote closes at its first repeat, which is what makes anything after it an error
 * rather than part of the value: a second grammar for where a quoted value ends would be
 * a second thing to get right, and getting it wrong is indistinguishable from a document
 * that does not exist.
 */
private fun quotedFilterValue(raw: String): FrontmatterFilterValue {
    val quote = raw[0]
    val closing = raw.indexOf(quote, startIndex = 1)
    return when {
        closing < 0 -> {
            FrontmatterFilterValue.Invalid("has an unterminated quoted value; close the quote")
        }

        closing != raw.length - 1 -> {
            FrontmatterFilterValue.Invalid("has text after the closing quote")
        }

        else -> {
            FrontmatterFilterValue.Resolved(TextValue(raw.substring(1, closing)))
        }
    }
}

/**
 * The number [raw] names, spelled the way the store spells it, or `null` when it is not one.
 *
 * Normalisation is not cosmetic. The index term is the text a stored [NumberValue] holds,
 * and the store holds what the YAML parser produced: `1e3` was read as a double and stored
 * as `1000.0`, `12.30` as `12.3`, `-0` as `0`. Resolving the filter without the same
 * normalisation yields `n:1e3`, which matches no document at all — a zero the caller
 * cannot explain, which is the failure this whole change exists to remove.
 *
 * [java.math.BigInteger] rather than `Long` because the value model keeps a number as text
 * precisely so digits past 2^53 survive; a filter routed through `Long` would drop the same
 * digits the store kept. The grammar itself is the parser's, not a looser one: a leading
 * zero, a leading `+`, `_` separators and hex are all text here because the parser leaves
 * them text.
 */
private fun canonicalNumber(raw: String): String? =
    when {
        INTEGER_LITERAL.matches(raw) -> raw.toBigInteger().toString()

        FLOAT_LITERAL.matches(raw) -> raw.toDoubleOrNull()?.toString()

        // The parser's own spellings for the non-finite doubles. They are named rather
        // than parsed because `Double.parseDouble` only understands the Java ones, and
        // routing them through it is how `-.nan` would end up an exception rather than
        // the string it is.
        else -> NON_FINITE[raw]
    }

/** `0`, or digits with no leading zero — the integers the core schema resolves. */
private const val MAGNITUDE = "(?:0|[1-9][0-9]*)"

private val INTEGER_LITERAL = Regex("-?$MAGNITUDE")

/**
 * A float is a magnitude with a dot (`1.`, `1.05`) or with an exponent (`1e3`), and the
 * fraction may be empty — but there is no `.5` form and no leading `+`, because the
 * parser leaves both as strings.
 */
private val FLOAT_LITERAL = Regex("-?$MAGNITUDE(?:\\.[0-9]*(?:[eE][+-]?[0-9]+)?|[eE][+-]?[0-9]+)")

/** Resolved spelling for the non-finite doubles, as the store spells them. */
private val NON_FINITE =
    mapOf(
        ".inf" to "Infinity",
        "-.inf" to "-Infinity",
        ".nan" to "NaN"
    )

data class BooleanValue(val value: Boolean) : FrontmatterValue {
    override fun encode(): String = "b:$value"

    override fun asText(): String = value.toString()

    override fun leaves(): List<FrontmatterValue> = listOf(this)
}

/** A YAML sequence. Order is significant and preserved. */
data class SequenceValue(val items: List<FrontmatterValue>) : FrontmatterValue {
    override fun encode(): String = items.joinToString(separator = "", prefix = "l${items.size}:") { it.encode() }

    override fun asText(): String? = null

    override fun leaves(): List<FrontmatterValue> = items.flatMap { it.leaves() }
}

/** A YAML mapping. Insertion order is the document's key order. */
data class MappingValue(val entries: Map<String, FrontmatterValue>) : FrontmatterValue {
    override fun encode(): String =
        entries.entries.joinToString(separator = "", prefix = "m${entries.size}:") { (key, value) ->
            "${key.length}:$key${value.encode()}"
        }

    override fun asText(): String? = null

    override fun leaves(): List<FrontmatterValue> = entries.values.flatMap { it.leaves() }
}

/**
 * Typed view of a document's frontmatter.
 *
 * Contract keys are modelled as fields because Tanseki consumes them
 * structurally. Every other key is kept in [values] with the shape it was
 * written in, so an adapter round-trips losslessly and the index can filter on
 * a list or a nested map instead of a stringified one.
 *
 * The one exception is a null: `key:` and `key: null` carry no value, so they
 * leave no entry and read as absent. A document that had such a line keeps it in
 * [rawFrontmatter], so the block still round-trips verbatim; what is not
 * preserved is a null as a *value*, because nothing can interpret one.
 *
 * Contract keys: `title`, `author`, `tags`, `aliases`, `updated_at`, `content_hash`.
 */
data class Frontmatter(
    val title: String? = null,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Obsidian's alternate names for the note.
     *
     * Read the way `tags` is — one scalar or a sequence of them — because authors
     * write both shapes and the distinction carries no meaning here. Like every
     * other contract key it is a typed field rather than a `values` entry, so an
     * `fm_aliases` filter is refused instead of matching nothing.
     */
    val aliases: List<String> = emptyList(),
    val updatedAt: Instant? = null,
    val contentHash: String? = null,
    val values: Map<String, FrontmatterValue> = emptyMap(),
    /**
     * The verbatim frontmatter block, when this came from parsing a document
     * that had one. [gokorei.tanseki.core.text.MarkdownSerializer] emits it
     * unchanged instead of re-serializing the typed view, so an edit to the body
     * does not rewrite the user's property formatting.
     *
     * `null` for frontmatter Tanseki synthesized itself, which is the signal to
     * render canonically. The typed view remains lossy either way — this field
     * preserves the block, not the meaning.
     */
    val rawFrontmatter: String? = null
) {
    /** The value for a non-contract [key], whatever shape it was written in. */
    fun value(key: String): FrontmatterValue? = values[key]

    /** [key] as text, or `null` when it is absent or is a collection. */
    fun text(key: String): String? = values[key]?.asText()

    /**
     * Every scalar under [key], in document order.
     *
     * A scalar yields itself, so a caller that wants "the references this key
     * names" does not have to know whether the author wrote one path or five.
     */
    fun texts(key: String): List<String> = values[key]?.leaves()?.mapNotNull { it.asText() } ?: emptyList()

    companion object {
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_TAGS = "tags"
        const val KEY_ALIASES = "aliases"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_CONTENT_HASH = "content_hash"

        /** The keys [values] must not contain, because a field already owns them. */
        val CONTRACT_KEYS: Set<String> =
            setOf(KEY_TITLE, KEY_AUTHOR, KEY_TAGS, KEY_ALIASES, KEY_UPDATED_AT, KEY_CONTENT_HASH)
    }
}

/**
 * A `[[target]]` / `[[target#anchor]]` / `[[target|label]]` link from a body.
 *
 * [anchor] is the heading or block reference, kept separate from [target] because
 * Obsidian puts both in one bracket pair and they mean different things: the target
 * is a document, the anchor is a place inside it. Keeping them glued together made
 * `[[Note#Section]]` resolve to no document at all, so the graph under-reported
 * exactly the notes that were linked most precisely.
 *
 * A leading `^` is stripped: `[[Note#^block]]` addresses a block, and the caret is
 * Obsidian's marker for that, not part of the identifier.
 *
 * [embed] is true for `![[target]]`, Obsidian's transclusion marker. An embed names
 * the same document a plain link would — the `!` says "render the content here",
 * not "point somewhere else" — so embeds stay in the link list and are additionally
 * reachable through [gokorei.tanseki.core.text.MarkdownParser]'s `embeds` view.
 */
data class Link(
    val target: String,
    val label: String? = null,
    val anchor: String? = null,
    val embed: Boolean = false
)

/**
 * Well-known relationship types used for derived edges.
 *
 * This is the whole vocabulary: `links-to`, `references`, `embeds`, `mentions`.
 * Frontmatter *keys* (`files`, `repo`, `pr`, `jira`, `author`) are not relationship
 * types — they are the sources edges are derived from, mapped onto this vocabulary
 * by the edge deriver. A traversal asking for a key instead of a type names
 * nothing and must be rejected rather than answered with an empty list. See
 * `docs/document-schema.md` for what each type derives from.
 */
object RelTypes {
    val LinksTo = RelType("links-to")
    val References = RelType("references")
    val Embeds = RelType("embeds")
    val Mentions = RelType("mentions")

    /** Every known relationship type, in the order the seams publish them. */
    val All: List<RelType> = listOf(LinksTo, References, Embeds, Mentions)

    /** The relationship type [value] names, or `null` when it names none. */
    fun parse(value: String): RelType? = All.firstOrNull { it.value == value }
}
