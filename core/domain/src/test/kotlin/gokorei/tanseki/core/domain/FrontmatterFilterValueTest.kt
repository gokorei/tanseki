package gokorei.tanseki.core.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a text filter value resolves to the typed value it names.
 *
 * This is the query-side half of the typed frontmatter model: `pr: 42` and `pr: "42"`
 * are indexed as different terms, so the filter has to say which one it means, and the
 * only rule that can say it is the one the document's own YAML already followed.
 *
 * Every case is asserted against the [FrontmatterValue] it resolves to rather than
 * against the term it encodes to, because the type *is* the answer — `NumberValue("42")`
 * and `TextValue("42")` encode differently only because they are different values.
 */
class FrontmatterFilterValueTest {
    /** The value [raw] resolves to, failing the test if it does not resolve at all. */
    private fun resolved(raw: String): FrontmatterValue {
        val outcome = resolveFrontmatterFilter(raw)
        assertEquals(
            true,
            outcome is FrontmatterFilterValue.Resolved,
            "[$raw] should resolve, but was ${(outcome as? FrontmatterFilterValue.Invalid)?.reason}"
        )
        return (outcome as FrontmatterFilterValue.Resolved).value
    }

    /** The rejection reason for [raw], failing the test if it resolves. */
    private fun rejected(raw: String): String {
        val outcome = resolveFrontmatterFilter(raw)
        assertEquals(
            true,
            outcome is FrontmatterFilterValue.Invalid,
            "[$raw] should be rejected, but resolved to ${(outcome as? FrontmatterFilterValue.Resolved)?.value}"
        )
        return (outcome as FrontmatterFilterValue.Invalid).reason
    }

    /** The rejection reason for a `key=raw` filter, failing the test if it resolves. */
    private fun rejectedKey(key: String, raw: String): String {
        val outcome = resolveFrontmatterFilter(key, raw)
        assertEquals(
            true,
            outcome is FrontmatterFilterValue.Invalid,
            "[$key=$raw] should be rejected, but resolved to ${(outcome as? FrontmatterFilterValue.Resolved)?.value}"
        )
        return (outcome as FrontmatterFilterValue.Invalid).reason
    }

    /** The value a `key=raw` filter resolves to, failing the test if it does not resolve. */
    private fun resolvedKey(key: String, raw: String): FrontmatterValue {
        val outcome = resolveFrontmatterFilter(key, raw)
        assertEquals(
            true,
            outcome is FrontmatterFilterValue.Resolved,
            "[$key=$raw] should resolve, but was ${(outcome as? FrontmatterFilterValue.Invalid)?.reason}"
        )
        return (outcome as FrontmatterFilterValue.Resolved).value
    }

    @Test
    fun `every contract key is refused, because no fm field is ever indexed for one`() {
        // A contract key is a typed field rather than a frontmatter value, so `values` must
        // not hold one and neither lookup emits an `fm_` term for it. `fm=tags=review` was
        // therefore well-formed, meaningful and guaranteed to return nothing — which reads
        // as "no document carries that tag" and sends an operator to inspect data that is
        // fine. Refused is the only answer that is not that wrong one.
        Frontmatter.CONTRACT_KEYS.forEach { key ->
            assertEquals(
                true,
                resolveFrontmatterFilter(key, "review") is FrontmatterFilterValue.Invalid,
                "[$key] should be refused, but no fm_ field is indexed for a contract key"
            )
        }
    }

    @Test
    fun `the tags rejection names the parameter that does work`() {
        // Every other contract key has no parameter of its own, so pointing somewhere is
        // all it can honestly do; `tags` has a working spelling and saying so is the
        // difference between a fix and a dead end.
        assertEquals("is a contract key; use the tags filter", rejectedKey("tags", "review"))
        assertEquals("is a contract key and is not filterable", rejectedKey("title", "Runbook"))
        assertEquals("is a contract key and is not filterable", rejectedKey("author", "dana"))
        assertEquals("is a contract key and is not filterable", rejectedKey("updated_at", "2026-01-01"))
        assertEquals("is a contract key and is not filterable", rejectedKey("content_hash", "abc123"))
    }

    @Test
    fun `a contract key is refused whatever its value would have been`() {
        // The refusal is about the key, so it must not depend on the value resolving
        // cleanly first: `fm=tags=` and `fm=title="unclosed` are still refusals, not the
        // empty-value and unterminated-quote errors, and not a match.
        assertEquals("is a contract key; use the tags filter", rejectedKey("tags", ""))
        assertEquals("is a contract key; use the tags filter", rejectedKey("tags", "\"unclosed"))
        assertEquals("is a contract key; use the tags filter", rejectedKey("tags", "42"))
        assertEquals("is a contract key; use the tags filter", rejectedKey("tags", "true"))
    }

    @Test
    fun `a key the contract does not claim resolves its value by the ordinary rules`() {
        // The value grammar is unchanged by knowing the key: `42` is the number 42 under
        // any key that is not a contract key, so a caller cannot reach a different answer
        // by naming a different field.
        assertEquals(NumberValue("42"), resolvedKey("pr", "42"))
        assertEquals(TextValue("42"), resolvedKey("pr", "\"42\""))
        assertEquals(BooleanValue(true), resolvedKey("published", "true"))
        // Including a key that merely looks like a contract key, which is an ordinary one.
        assertEquals(TextValue("v"), resolvedKey("tag", "v"))
        assertEquals(TextValue("v"), resolvedKey("tags_extra", "v"))
        assertEquals(TextValue("v"), resolvedKey("Titles", "v"))
    }

    @Test
    fun `the key refusal is reported in the caller's terms, naming the filter they sent`() {
        // The whole sentence a caller sees, because the message is the fix: it echoes what
        // they sent and points at the parameter that does work.
        val outcome = resolveFrontmatterFilter("tags", "review")

        assertEquals(
            "frontmatter filter 'tags=review' is a contract key; use the tags filter",
            (outcome as FrontmatterFilterValue.Invalid).explain("tags=review")
        )
    }

    @Test
    fun `a bare number resolves to the number the document would have stored`() {
        assertEquals(NumberValue("42"), resolved("42"))
        assertEquals(NumberValue("1.5"), resolved("1.5"))
        assertEquals(NumberValue("-3"), resolved("-3"))
        assertEquals(NumberValue("0"), resolved("0"))
    }

    @Test
    fun `a number is normalised the way the write path normalised it`() {
        // These are the spellings a document cannot hold: `1e3` was parsed as a double
        // and stored as `1000.0`, so a filter that kept the caller's characters would
        // build `n:1e3` and match nothing at all.
        assertEquals(NumberValue("1000.0"), resolved("1e3"))
        assertEquals(NumberValue("1000.0"), resolved("1.0e3"))
        assertEquals(NumberValue("12.3"), resolved("12.30"))
        assertEquals(NumberValue("5.0"), resolved("5."))
        assertEquals(NumberValue("1000.0"), resolved("1.e3"))
        assertEquals(NumberValue("0"), resolved("-0"))
        assertEquals(NumberValue("0.2"), resolved("2e-1"))
        assertEquals(NumberValue("Infinity"), resolved(".inf"))
        assertEquals(NumberValue("-Infinity"), resolved("-.inf"))
        assertEquals(NumberValue("NaN"), resolved(".nan"))
        // …while these are spellings the parser never made numbers, so neither is this.
        assertEquals(TextValue("-.nan"), resolved("-.nan"))
        assertEquals(TextValue(".5"), resolved(".5"))
        assertEquals(TextValue("007.0"), resolved("007.0"))
    }

    @Test
    fun `an integer past 2^53 keeps its digits`() {
        // The value model holds a number as text precisely so the digits survive; a
        // filter routed through a Long would drop the same digits the store kept.
        assertEquals(NumberValue("9007199254740993"), resolved("9007199254740993"))
        assertEquals(NumberValue("123456789012345678901234567890"), resolved("123456789012345678901234567890"))
    }

    @Test
    fun `a bare boolean word resolves to a boolean and only in lower case`() {
        assertEquals(BooleanValue(true), resolved("true"))
        assertEquals(BooleanValue(false), resolved("false"))
        // The write side stores `TRUE` as the string `TRUE`, so calling it a boolean
        // here would break the inverse this resolution exists to provide.
        assertEquals(TextValue("TRUE"), resolved("TRUE"))
        assertEquals(TextValue("True"), resolved("True"))
        // Nor are the YAML 1.1 words, which this store never made booleans either.
        assertEquals(TextValue("yes"), resolved("yes"))
        assertEquals(TextValue("no"), resolved("no"))
    }

    @Test
    fun `quoting forces a string, which is what makes the ambiguous case expressible`() {
        assertEquals(TextValue("42"), resolved("\"42\""))
        assertEquals(TextValue("42"), resolved("'42'"))
        assertEquals(TextValue("true"), resolved("\"true\""))
        assertEquals(TextValue("org/repo"), resolved("'org/repo'"))
        // An explicitly empty string is a value a caller can hold, so it is not the
        // same request as `fm=pr=`, which is rejected.
        assertEquals(TextValue(""), resolved("\"\""))
        assertEquals(TextValue(""), resolved("''"))
    }

    @Test
    fun `a value that merely looks numeric is text rather than an error`() {
        // `1.2.3` is a version and `0042` a zero-padded identifier; both are values a
        // vault really holds, and rejecting one to express a doubt about its shape would
        // break a query that works today.
        assertEquals(TextValue("1.2.3"), resolved("1.2.3"))
        assertEquals(TextValue("1.2.3.4"), resolved("1.2.3.4"))
        assertEquals(TextValue("0042"), resolved("0042"))
        assertEquals(TextValue("v1.2.3"), resolved("v1.2.3"))
        assertEquals(TextValue("org/repo"), resolved("org/repo"))
        assertEquals(TextValue("src/a.py"), resolved("src/a.py"))
        assertEquals(TextValue("ABC-1"), resolved("ABC-1"))
        assertEquals(TextValue("[\"a.py\"]"), resolved("[\"a.py\"]"))
    }

    @Test
    fun `the non-numeric spellings of a number are text, as on the write path`() {
        assertEquals(TextValue("+42"), resolved("+42"))
        assertEquals(TextValue(".5"), resolved(".5"))
        assertEquals(TextValue("1_000"), resolved("1_000"))
        assertEquals(TextValue("0x1F"), resolved("0x1F"))
        assertEquals(TextValue("42x"), resolved("42x"))
        assertEquals(TextValue("1.2e"), resolved("1.2e"))
    }

    @Test
    fun `a value with no value at all is rejected rather than matched as the empty string`() {
        assertEquals("has no value; write \"\" for the empty string", rejected(""))
    }

    @Test
    fun `an unclosed quote is an error and never the text it was going to quote`() {
        // This is the important rejection. Quoting is how a caller forces a string, so
        // a broken quote that fell back to the literal text `42` would return the
        // documents storing the string "42" for a request that asked for the number.
        assertEquals("has an unterminated quoted value; close the quote", rejected("\"42"))
        assertEquals("has an unterminated quoted value; close the quote", rejected("'42"))
        assertEquals("has an unterminated quoted value; close the quote", rejected("\""))
        assertEquals("has an unterminated quoted value; close the quote", rejected("'"))
    }

    @Test
    fun `text around the quotes is rejected rather than silently dropped`() {
        assertEquals("has an unterminated quoted value; close the quote", rejected("\"42 trailing"))
        // The quote closes at its first repeat, so what follows it is not part of the
        // value — including a second pair of quotes inside it.
        assertEquals("has text after the closing quote", rejected("\"42\"x"))
        assertEquals("has text after the closing quote", rejected("\"a\"b\""))
    }

    @Test
    fun `a rejection names the parameter the caller sent`() {
        val reason = rejected("\"42")

        assertEquals(
            "frontmatter filter 'pr=\"42' $reason",
            (resolveFrontmatterFilter("\"42") as FrontmatterFilterValue.Invalid).explain("pr=\"42")
        )
    }
}
