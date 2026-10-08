package gokorei.tanseki.service.api

import gokorei.tanseki.adapters.lucene.LuceneLookup
import gokorei.tanseki.core.application.QueryFacade
import gokorei.tanseki.core.ports.Clock
import gokorei.tanseki.service.api.StoreApiServer
import gokorei.tanseki.testkit.InMemoryContextStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Instant

/**
 * The `/v1` frontmatter round trip, asserted on the wire rather than in-process.
 *
 * This exists because the defect it pins was found by *measuring* a live daemon —
 * a table of what came back — and the measurement stopped reproducing when an
 * unrelated merge landed. A measurement that another branch can invalidate is worth
 * turning into a test, because the day it stops reproducing again nothing else
 * will say so.
 *
 * It runs over HTTP rather than against `toFrontmatter()` directly for the same
 * reason. The seam is the whole path: JSON in, `FrontmatterValue`, YAML rendered
 * into the document's canonical Markdown, parsed back, JSON out. A unit test on
 * the DTO mapper passes while the renderer stringifies, which is exactly how this
 * defect survived a green suite.
 *
 * The load-bearing row is `files`. `tags` is a *contract* key with a typed field,
 * so it round-tripped before the fix and would let a regression in the extras path
 * pass unnoticed — asserting on it alone is the trap this file exists to close.
 */
class FrontmatterValueTypeProbeTest {
    private val apiKey = "probe-key"
    private val client = HttpClient.newHttpClient()

    @Test
    fun `a sequence-valued extra survives upsert to get as a sequence`() {
        val probe = probe()

        val frontmatter =
            probe.upsert(
                "probe/files",
                "review body",
                mapOf(
                    "files" to array("src/a.py", "src/b.py"),
                    "novel_list" to array("x", "y"),
                    "tags" to array("review", "inline_comment")
                )
            )

        // The extras: these are the values a repr string used to flatten.
        assertEquals(
            listOf("src/a.py", "src/b.py"),
            frontmatter.strings("files"),
            "files must read back as a sequence, not as its repr string"
        )
        assertEquals(listOf("x", "y"), frontmatter.strings("novel_list"))
        // The contract key, which already worked — asserted so a regression in the
        // extras path cannot hide behind it.
        assertEquals(listOf("review", "inline_comment"), frontmatter.strings("tags"))
    }

    @Test
    fun `scalars keep the JSON type they arrived as`() {
        val probe = probe()

        val frontmatter =
            probe.upsert(
                "probe/scalars",
                "body",
                mapOf(
                    "int_pr" to JsonPrimitive(42),
                    "float_ratio" to JsonPrimitive(1.5),
                    "bool_flag" to JsonPrimitive(true),
                    // Beyond 2^53: a double would round this to ...992 and the
                    // digits would be lost, which is why the domain holds a
                    // number as text.
                    "big_int" to JsonUnquotedLiteral("9007199254740993"),
                    "null_key" to JsonPrimitive(null as String?)
                )
            )

        assertEquals("42", frontmatter.number("int_pr"))
        assertEquals("1.5", frontmatter.number("float_ratio"))
        assertEquals("9007199254740993", frontmatter.number("big_int"))
        assertEquals(true, frontmatter["bool_flag"]!!.jsonPrimitive.booleanOrNull)
        // An explicit null is the absence of a value, not a value: it leaves no key behind.
        assertFalse("null_key" in frontmatter, "an explicit null leaves no key")
    }

    @Test
    fun `a quoted scalar is not re-typed on the way back`() {
        val probe = probe()

        val frontmatter =
            probe.upsert(
                "probe/quoted",
                "body",
                mapOf(
                    "leading_zero" to JsonPrimitive("0042"),
                    "word_false" to JsonPrimitive("false"),
                    "not_a_list" to JsonPrimitive("[\"a.py\"]")
                )
            )

        // These arrived as JSON strings and must leave as JSON strings. A reader
        // that re-typed them would silently rewrite the caller's data.
        assertEquals("0042", frontmatter.text("leading_zero"))
        assertEquals("false", frontmatter.text("word_false"))
        assertEquals("[\"a.py\"]", frontmatter.text("not_a_list"))
    }

    @Test
    fun `a nested object extra survives with its leaves`() {
        val probe = probe()

        val frontmatter =
            probe.upsert(
                "probe/nested",
                "body",
                mapOf(
                    "meta" to
                        buildJsonObject {
                            put("owner", "me")
                            put("depth", JsonPrimitive(2))
                        }
                )
            )

        val meta = frontmatter["meta"] as JsonObject
        assertEquals("me", meta["owner"]!!.jsonPrimitive.content)
        assertEquals("2", meta["depth"]!!.jsonPrimitive.content)
    }

    /**
     * A document written before this behaviour existed.
     *
     * The old renderer stringified an array, so its canonical Markdown carries the
     * repr as a quoted scalar. That document must keep reading as the text it was
     * stored as — re-typing it would make a document written last week and one
     * written today read the same field differently, with nothing in the data
     * saying which.
     */
    @Test
    fun `a document written with a stringified repr still reads as text`() {
        val probe = probe()

        probe.upsertContent(
            "probe/legacy",
            """
            ---
            files: '["src/a.py","src/b.py"]'
            int_pr: '42'
            ---
            legacy body
            """.trimIndent()
        )

        val frontmatter = probe.get("probe/legacy")
        assertEquals("[\"src/a.py\",\"src/b.py\"]", frontmatter.text("files"))
        assertEquals("42", frontmatter.text("int_pr"))
    }

    /**
     * A JSON string that looks like a scalar stays a string in the stored bytes.
     *
     * A repro on this seam showed `"42"`, `"007"` and `"true"` arriving as JSON
     * strings and coming back bare from the stored document, while the equivalent
     * quoted YAML block kept every quote. Two seams storing different bytes for
     * the same document is the whole defect, so the pairing is asserted directly
     * rather than each seam against a hand-written expectation: the JSON field and
     * the YAML block carry the same three values, and their stored frontmatter must
     * be identical.
     */
    @Test
    fun `a JSON string that looks like a scalar is stored as a string`() {
        val probe = probe()

        probe.upsert(
            "probe/typed/json",
            "body",
            mapOf("pr" to JsonPrimitive("42"), "ver" to JsonPrimitive("007"), "flag" to JsonPrimitive("true"))
        )
        probe.upsertContent(
            "probe/typed/yaml",
            """
            ---
            pr: "42"
            ver: "007"
            flag: "true"
            ---
            body
            """.trimIndent()
        )

        val stored = probe.storedContent("probe/typed/json")
        assertTrue(stored.contains("pr: \"42\""), "a string 42 must keep its quotes: $stored")
        assertTrue(stored.contains("ver: \"007\""), "a leading-zero string must keep its quotes: $stored")
        assertTrue(stored.contains("flag: \"true\""), "the string true must keep its quotes: $stored")

        assertEquals(
            probe.storedContent("probe/typed/yaml"),
            stored,
            "the JSON field and the YAML block hold the same values and must store the same bytes"
        )
    }

    /**
     * A JSON string that looks like a scalar stays a string in the stored bytes,
     * and the two seams store the same bytes for the same values.
     *
     * A repro sent `"42"`, `"007"` and `"true"` as JSON strings and got them back
     * bare, while the equivalent quoted YAML block kept every quote.
     * The quoting half is fixed and asserted here. The pairing is the assertion
     * that matters: two seams holding identical values must produce identical
     * stored documents, or a note's bytes depend on which route wrote it.
     */
    @Test
    fun `both seams store the same bytes for the same frontmatter values`() {
        val probe = probe()

        probe.upsert(
            "probe/seam/json",
            "body",
            mapOf("pr" to JsonPrimitive("42"), "ver" to JsonPrimitive("007"), "flag" to JsonPrimitive("true"))
        )
        probe.upsertContent(
            "probe/seam/yaml",
            """
            ---
            pr: "42"
            ver: "007"
            flag: "true"
            ---
            body
            """.trimIndent()
        )

        val fromJson = probe.storedContent("probe/seam/json")
        assertTrue(fromJson.contains("pr: \"42\""), "a string 42 must keep its quotes: $fromJson")
        assertTrue(fromJson.contains("ver: \"007\""), "a leading-zero string must keep its quotes: $fromJson")
        assertTrue(fromJson.contains("flag: \"true\""), "the string true must keep its quotes: $fromJson")
        // Key order is the caller's on both seams. Sorting one of them would make
        // this fail on a document whose values are perfectly correct.
        assertEquals(
            probe.storedContent("probe/seam/yaml"),
            fromJson,
            "the JSON field and the YAML block hold the same values and must store the same bytes"
        )
    }

    /**
     * `updated_at` is canonicalised, and that is the decision rather than an
     * accident.
     *
     * It is a contract key typed `Instant`, so a write is free to render it in one
     * spelling: `+00:00` and `Z` are the same instant and the same value, and two
     * clients that agree about the instant should agree about the bytes. The cost
     * is that a caller comparing the stored *text* sees a difference, so this
     * pins both halves — the canonical form, and that the instant is unchanged.
     *
     * Preserving the caller's exact text was the alternative and was declined: it
     * would need `rawFrontmatter` to cover a caller-supplied JSON block as well as
     * a parsed YAML one, so the store would carry a shadow copy of a value it has
     * already decided to model. Recording the trade is the point: a client
     * comparing the stored text needs to know it will not get its own characters
     * back.
     */
    @Test
    fun `updated_at is stored in one canonical spelling and keeps its instant`() {
        val probe = probe()

        probe.upsert(
            "probe/instant/offset",
            "body",
            mapOf("updated_at" to JsonPrimitive("2026-10-03T12:00:00+00:00"))
        )

        val stored = probe.storedContent("probe/instant/offset")
        assertTrue(
            stored.contains("updated_at: \"2026-10-03T12:00:00Z\""),
            "an offset spelling must be canonicalised to Z: $stored"
        )
        // The instant is the thing that is preserved, so assert it rather than the text.
        assertEquals(
            Instant.parse("2026-10-03T12:00:00Z"),
            Instant.parse(probe.get("probe/instant/offset").text("updated_at"))
        )

        // A non-UTC offset is normalised to the same instant, not rejected and not shifted.
        probe.upsert(
            "probe/instant/east",
            "body",
            mapOf("updated_at" to JsonPrimitive("2026-10-03T14:00:00+02:00"))
        )
        assertEquals(
            Instant.parse("2026-10-03T12:00:00Z"),
            Instant.parse(probe.get("probe/instant/east").text("updated_at")),
            "14:00+02:00 is the same instant as 12:00Z and must be stored as such"
        )
    }

    /**
     * `fm` alone is a query, not a modifier that needs `q` alongside it.
     *
     * An equality filter asked in prose is "which documents have `repo=org/one`",
     * and `GET /v1/search?fm=…` is the request for it. It used to return nothing,
     * on any profile, while `?fm=…&q=something` returned the document — a filter
     * that only works when you already know what to type is not a filter.
     *
     * Asserted over HTTP because the defect was in the seam's handling of an
     * absent `q`, not in the index: `q=body` returned the document while
     * `fm=…` alone did not, so both facts had to be true at once for the
     * original symptom to be explicable.
     */
    @Test
    fun `an fm filter alone returns the documents that carry the property`() {
        val probe = probe()

        probe.upsert(
            "probe/filter/one",
            "alpha body",
            mapOf("repo" to JsonPrimitive("org/one"), "pr" to JsonPrimitive(7))
        )
        probe.upsert("probe/filter/two", "beta body", mapOf("repo" to JsonPrimitive("org/two")))
        probe.upsert("probe/filter/none", "gamma body", emptyMap())

        assertEquals(
            listOf("probe/filter/one"),
            probe.searchIds("fm=repo=org/one"),
            "a string filter alone must find the document"
        )
        // A bare number is the payoff of the typed frontmatter model: the comparison is
        // expressible without inventing a text query, and it needs no syntax of its own
        // because `pr: 7` in the document's own YAML already meant the number 7.
        assertEquals(
            listOf("probe/filter/one"),
            probe.searchIds("fm=pr=7"),
            "a bare 7 must reach the number the document stored"
        )
        // The distinction has to be bidirectional, or quoting is decoration: a quoted 7
        // is the string, and reaches the document that holds the string.
        assertEquals(
            emptyList<String>(),
            probe.searchIds("fm=pr=%227%22"),
            "a quoted 7 is the string 7, and this document holds the number"
        )
        assertEquals(emptyList<String>(), probe.searchIds("fm=repo=org/absent"))
        // A document without the property is not returned by a filter that has it.
        assertFalse(probe.searchIds("fm=repo=org/one").contains("probe/filter/none"))
        // And the filter still narrows a text query rather than replacing it.
        assertEquals(listOf("probe/filter/one"), probe.searchIds("q=alpha&fm=repo=org/one"))
        assertEquals(emptyList<String>(), probe.searchIds("q=beta&fm=repo=org/one"))
    }

    /**
     * Blank query text with no filter asks nothing, so it answers nothing.
     *
     * The asymmetry with the case above is deliberate. `?fm=` is a question;
     * `?q=` alone is not, and enumerating every document belongs to
     * `documents:list`. A mistyped parameter must not look like a successful
     * empty result.
     */
    @Test
    fun `a search with neither query text nor a filter returns nothing`() {
        val probe = probe()
        probe.upsert("probe/empty/one", "body", emptyMap())

        assertEquals(emptyList<String>(), probe.searchIds(""))
        assertEquals(listOf("probe/empty/one"), probe.searchIds("q=body"))
    }

    @Test
    fun `a filter-only search is bounded by limit`() {
        val probe = probe()
        repeat(6) { index ->
            probe.upsert("probe/bounded/$index", "body $index", mapOf("k" to JsonPrimitive("v")))
        }

        assertEquals(6, probe.searchIds("fm=k=v").size)
        assertEquals(2, probe.searchIds("fm=k=v&limit=2").size)
    }

    /**
     * Non-finite numbers parse as doubles but are not RFC-8259 JSON numbers. Emitting
     * them unquoted produced `{"x":Infinity}`, which a strict parser rejects — so an
     * MCP proxy in front of such a document failed to read the daemon response at all.
     * `probe.get` parses with a strict [Json], so this fails on the unquoted form.
     */
    @Test
    fun `non-finite frontmatter numbers leave as valid JSON strings`() {
        val probe = probe()

        probe.upsertContent(
            "probe/nonfinite",
            """
            ---
            pos: .inf
            neg: -.inf
            nan: .nan
            finite: 42
            ---
            body
            """.trimIndent()
        )

        val frontmatter = probe.get("probe/nonfinite")
        assertEquals("Infinity", frontmatter.text("pos"))
        assertEquals("-Infinity", frontmatter.text("neg"))
        assertEquals("NaN", frontmatter.text("nan"))
        // A finite number must stay an unquoted JSON number.
        assertEquals("42", frontmatter.number("finite"))
    }

    // ---- probe -----------------------------------------------------------

    private class Probe(private val port: Int, private val apiKey: String) {
        private val client = HttpClient.newHttpClient()

        fun upsert(id: String, content: String, frontmatter: Map<String, JsonElement>): JsonObject {
            val payload =
                buildJsonObject {
                    put("id", id)
                    put("content", content)
                    put("frontmatter", JsonObject(frontmatter))
                }
            assertEquals(200, post("/v1/documents:upsert", payload).statusCode())
            return get(id)
        }

        fun upsertContent(id: String, content: String) {
            val payload =
                buildJsonObject {
                    put("id", id)
                    put("content", content)
                }
            assertEquals(200, post("/v1/documents:upsert", payload).statusCode())
        }

        fun get(id: String): JsonObject = document(id)["frontmatter"]!!.jsonObject

        /** The canonical Markdown the store holds, which is where a lost quote shows. */
        fun storedContent(id: String): String = document(id)["content"]!!.jsonPrimitive.content

        private fun document(id: String): JsonObject {
            val response = post("/v1/documents:get", buildJsonObject { put("id", id) })
            assertEquals(200, response.statusCode(), response.body())
            // `documents:get` answers with the DocumentDto at the top level.
            return json.parseToJsonElement(response.body()).jsonObject
        }

        /** Ids for a raw query string, so the test states exactly what was requested. */
        fun searchIds(query: String): List<String> {
            val response = search(query)
            assertEquals(200, response.first, response.second)
            return json
                .parseToJsonElement(response.second)
                .jsonObject["hits"]!!
                .jsonArray
                .map { hit -> hit.jsonObject["id"]!!.jsonPrimitive.content }
        }

        /**
         * The `error.message` of a rejected search, decoded from the envelope.
         *
         * Asserted on the decoded field rather than on the raw body because the envelope is
         * what a caller reads, and the body carries the message JSON-escaped — a substring
         * check against it would be asserting on the escaping.
         */
        fun errorMessage(query: String): String {
            val response = search(query)
            assertEquals(400, response.first, response.second)
            val error = json.parseToJsonElement(response.second).jsonObject["error"]!!.jsonObject
            assertEquals("invalid_argument", error["code"]!!.jsonPrimitive.content)
            return error["message"]!!.jsonPrimitive.content
        }

        private fun search(query: String): Pair<Int, String> {
            val response =
                client.send(
                    HttpRequest
                        .newBuilder(URI("http://127.0.0.1:$port/v1/search?$query"))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-API-Key", apiKey)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString()
                )
            return response.statusCode() to response.body()
        }

        private fun post(path: String, body: JsonElement): HttpResponse<String> =
            client.send(
                HttpRequest
                    .newBuilder(URI("http://127.0.0.1:$port$path"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
    }

    private fun probe(): Probe {
        val now = kotlin.time.Instant.fromEpochSeconds(1_700_000_000)
        val facade =
            QueryFacade(
                store = InMemoryContextStore { now },
                lookup = LuceneLookup(),
                clock = Clock { now },
                io = Dispatchers.Unconfined
            )
        val running = StoreApiServer.startEphemeral(facade, apiKey)
        var ready = false
        repeat(100) {
            if (!ready) {
                ready =
                    runCatching {
                        client
                            .send(
                                HttpRequest
                                    .newBuilder(URI("http://127.0.0.1:${running.port}/v1/health"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.ofString()
                            ).statusCode() == 200
                    }.getOrDefault(false)
                if (!ready) Thread.sleep(20)
            }
        }
        check(ready) { "server did not become ready" }
        return Probe(running.port, apiKey)
    }

    private companion object {
        val json = Json

        fun array(vararg items: String): JsonArray = JsonArray(items.map(::JsonPrimitive))

        /**
         * A value's JSON text, so a test asserts on the *type* and not only on the
         * content: `"42"` and `42` carry the same characters and are not the same
         * value, and only one of them is what the caller sent.
         */
        fun JsonObject.number(key: String): String {
            val value = this[key] ?: error("no '$key' in $this")
            assertFalse(value.jsonPrimitive.isString, "'$key' must read back as a JSON number, not a string: $value")
            return value.jsonPrimitive.content
        }

        /** The counterpart of [strings]: asserts the value is a JSON *string*, not a list. */
        fun JsonObject.text(key: String): String {
            val value = this[key] ?: error("no '$key' in $this")
            assertTrue(value.jsonPrimitive.isString, "'$key' must read back as a JSON string, not $value")
            return value.jsonPrimitive.content
        }

        fun JsonObject.strings(key: String): List<String> {
            val value = this[key] ?: error("no '$key' in $this")
            assertTrue(value is JsonArray, "'$key' must read back as a JSON array, not $value")
            return (value as JsonArray).map { (it as JsonPrimitive).content }
        }
    }

    /**
     * Every frontmatter type is matchable, and by writing the value the way the document
     * did.
     *
     * The round trip made `pr: 7` readable; this makes it queryable. The rule is that a
     * filter value is resolved by ordinary scalar rules — because that is what the parser
     * on the write side already did to the same characters — and that quoting is how a
     * caller overrides the resolution. A second grammar of `n:`/`b:` prefixes would have
     * made every existing `fm=` value mean something other than what it meant.
     */
    @Test
    fun `a filter value selects only the value of the type it resolves to`() {
        val probe = probe()

        probe.upsert("probe/typed/int", "body", mapOf("v" to JsonPrimitive(7)))
        probe.upsert("probe/typed/float", "body", mapOf("v" to JsonPrimitive(1.5)))
        probe.upsert("probe/typed/bool", "body", mapOf("v" to JsonPrimitive(true)))
        probe.upsert("probe/typed/text", "body", mapOf("v" to JsonPrimitive("7")))

        assertEquals(listOf("probe/typed/int"), probe.searchIds("fm=v=7"))
        assertEquals(listOf("probe/typed/float"), probe.searchIds("fm=v=1.5"))
        assertEquals(listOf("probe/typed/bool"), probe.searchIds("fm=v=true"))
        // The string "7" is reachable, and reachable on its own: quoting is the answer to
        // the ambiguity rather than a second spelling of the same filter.
        assertEquals(listOf("probe/typed/text"), probe.searchIds("fm=v=%227%22"))
    }

    /**
     * The zero-padding case, which is the one that shows this is parsing.
     *
     * `0042` shares every digit with `42`, so a substring or loose comparison would match
     * it either way. It is also exactly what Kojutsu writes for a string that happens to
     * look like a number — which is why the quoting rule above is not optional.
     */
    @Test
    fun `a zero-padded value is a string and does not reach the number`() {
        val probe = probe()

        probe.upsert("probe/pad/int", "body", mapOf("pr" to JsonPrimitive(42)))
        probe.upsert("probe/pad/text", "body", mapOf("pr" to JsonPrimitive("0042")))

        assertEquals(listOf("probe/pad/int"), probe.searchIds("fm=pr=42"))
        assertEquals(listOf("probe/pad/text"), probe.searchIds("fm=pr=0042"))
        assertEquals(emptyList<String>(), probe.searchIds("fm=pr=042"))
    }

    @Test
    fun `a boolean value is not a string that looks like one`() {
        val probe = probe()

        probe.upsert("probe/bool/yes", "body", mapOf("flag" to JsonPrimitive(true)))
        probe.upsert("probe/bool/text", "body", mapOf("flag" to JsonPrimitive("true")))

        assertEquals(listOf("probe/bool/yes"), probe.searchIds("fm=flag=true"))
        assertEquals(listOf("probe/bool/text"), probe.searchIds("fm=flag=%22true%22"))
        // The write side stores `TRUE` as a string, so the filter reads it as one too.
        probe.upsert("probe/bool/shouty", "body", mapOf("flag" to JsonPrimitive("TRUE")))
        assertEquals(listOf("probe/bool/shouty"), probe.searchIds("fm=flag=TRUE"))
        // And neither string is reachable by the unquoted filter, which is the whole
        // distinction: one request, one type, no prefix to forget.
        assertEquals(listOf("probe/bool/yes"), probe.searchIds("fm=flag=true"))
    }

    @Test
    fun `a value that only looks like a scalar is still a filterable string`() {
        val probe = probe()

        probe.upsert("probe/shape/version", "body", mapOf("ver" to JsonPrimitive("1.2.3")))
        probe.upsert("probe/shape/plus", "body", mapOf("n" to JsonPrimitive("+42")))
        probe.upsert("probe/shape/underscored", "body", mapOf("n" to JsonPrimitive("1_000")))

        // A version is not a number and is not refused for looking like one; rejecting it
        // would break a query that works today to express a doubt about its shape.
        assertEquals(listOf("probe/shape/version"), probe.searchIds("fm=ver=1.2.3"))
        assertEquals(listOf("probe/shape/plus"), probe.searchIds("fm=n=%2B42"))
        assertEquals(listOf("probe/shape/underscored"), probe.searchIds("fm=n=1_000"))
    }

    /**
     * A malformed filter value is refused, by name.
     *
     * Both of these used to be answered with an empty result set, which reads as "no such
     * document" rather than "your parameter is broken" — and the unterminated quote is the
     * dangerous one, because it is the syntax a caller reaches for precisely to select the
     * string. A caller who sent `fm=pr="42` must be told which filter and why, not shown
     * a search that quietly found nothing.
     */
    @Test
    fun `a filter value that cannot be resolved is a named error, not an empty result`() {
        val probe = probe()
        probe.upsert("probe/bad/one", "body", mapOf("pr" to JsonPrimitive("42")))

        assertEquals(
            "frontmatter filter 'pr=' has no value; write \"\" for the empty string",
            probe.errorMessage("fm=pr=")
        )
        assertEquals(
            "frontmatter filter 'pr=\"42' has an unterminated quoted value; close the quote",
            probe.errorMessage("fm=pr=%2242")
        )

        // And the rejection is specific to the broken filter, not to searching at all.
        assertEquals(listOf("probe/bad/one"), probe.searchIds("fm=pr=%2242%22"))
    }

    @Test
    fun `a typed filter combines with a text query and with another filter`() {
        val probe = probe()

        probe.upsert(
            "probe/combo/one",
            "alpha body",
            mapOf("repo" to JsonPrimitive("org/one"), "pr" to JsonPrimitive(7))
        )
        probe.upsert(
            "probe/combo/two",
            "alpha body",
            mapOf("repo" to JsonPrimitive("org/one"), "pr" to JsonPrimitive(8))
        )

        assertEquals(
            listOf("probe/combo/one"),
            probe.searchIds("q=alpha&fm=pr=7"),
            "a number filter must narrow a text query like any other"
        )
        assertEquals(
            listOf("probe/combo/one"),
            probe.searchIds("fm=repo=org/one&fm=pr=7"),
            "two filters must AND"
        )
    }
}
