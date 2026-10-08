package gokorei.tanseki.service.api

import gokorei.tanseki.core.domain.InvalidInputException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A contract key is dropped silently, or not at all.
 *
 * `title`, `author`, `updated_at`, `content_hash` and `tags` each have a typed
 * field on `Frontmatter`, so a value of the wrong JSON shape cannot be carried.
 * The earlier reader took each key with `takeIf { it.isString }` and moved on, so
 * `title: 123` and `tags: "a,b,c"` became null and empty: the upsert returned
 * `200`, the document rendered with no frontmatter block at all, and nothing
 * anywhere said so. A silently removed key is worse than a refused one, because
 * the caller cannot learn that it happened.
 *
 * The rule these assert: carry what can be carried, refuse the rest with a
 * reason. A scalar is carried as its text — that is the value the caller sent, not
 * a guess — and a shape with no field to hold it is refused by name.
 */
class ContractKeyShapeTest {
    private fun frontmatter(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        mapOf(*pairs).toFrontmatter()

    private fun reasonFor(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): String =
        assertThrows(InvalidInputException::class.java) { frontmatter(*pairs) }.message!!

    @Test
    fun `a scalar contract key is carried as its text whatever its JSON type`() {
        assertEquals("123", frontmatter("title" to JsonPrimitive(123)).title)
        assertEquals("true", frontmatter("title" to JsonPrimitive(true)).title)
        assertEquals("7", frontmatter("author" to JsonPrimitive(7)).author)
        assertEquals("9", frontmatter("content_hash" to JsonPrimitive(9)).contentHash)
    }

    @Test
    fun `a string contract key is unchanged`() {
        assertEquals("ok", frontmatter("title" to JsonPrimitive("ok")).title)
    }

    @Test
    fun `an absent contract key stays absent rather than becoming empty text`() {
        assertNull(frontmatter("author" to JsonPrimitive("dev")).title)
        assertEquals("dev", frontmatter("author" to JsonPrimitive("dev")).author)
    }

    @Test
    fun `a contract key sent as an array is refused by name`() {
        val reason = reasonFor("title" to JsonArray(listOf(JsonPrimitive("a"))))
        assertTrue(reason.contains("'title'") && reason.contains("array"), reason)
    }

    @Test
    fun `a contract key sent as an object is refused by name`() {
        val reason = reasonFor("author" to JsonObject(mapOf("n" to JsonPrimitive("x"))))
        assertTrue(reason.contains("'author'") && reason.contains("object"), reason)
    }

    @Test
    fun `tags as a string is refused rather than split on a comma`() {
        // Splitting "a,b,c" would be a guess, and it is wrong for any tag that
        // contains a comma. The array form carries the meaning exactly.
        val reason = reasonFor("tags" to JsonPrimitive("a,b,c"))
        assertTrue(reason.contains("'tags'") && reason.contains("array of strings"), reason)
    }

    @Test
    fun `tags as an array of strings is carried`() {
        assertEquals(
            listOf("a", "b"),
            frontmatter("tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))).tags
        )
    }

    @Test
    fun `tags as an array with a non-string is refused`() {
        val reason = reasonFor("tags" to JsonArray(listOf(JsonPrimitive(1))))
        assertTrue(reason.contains("'tags'") && reason.contains("strings only"), reason)
    }

    @Test
    fun `an unparseable updated_at is refused rather than becoming null`() {
        val reason = reasonFor("updated_at" to JsonPrimitive("not-a-date"))
        assertTrue(reason.contains("'updated_at'") && reason.contains("not an instant"), reason)
    }

    @Test
    fun `a valid updated_at is carried`() {
        assertEquals(
            kotlin.time.Instant.parse("2026-01-02T03:04:05Z"),
            frontmatter("updated_at" to JsonPrimitive("2026-01-02T03:04:05Z")).updatedAt
        )
    }

    @Test
    fun `an explicit null contract key is absent, not an error`() {
        assertNull(frontmatter("title" to kotlinx.serialization.json.JsonNull).title)
        assertEquals(emptyList<String>(), frontmatter("tags" to kotlinx.serialization.json.JsonNull).tags)
    }

    @Test
    fun `a non-contract key keeps its shape whatever it is`() {
        val parsed =
            frontmatter(
                "files" to JsonArray(listOf(JsonPrimitive("a.py"), JsonPrimitive("b.py"))),
                "meta" to JsonObject(mapOf("owner" to JsonPrimitive("me"))),
                "n" to JsonPrimitive(42)
            )

        assertEquals(listOf("a.py", "b.py"), parsed.texts("files"))
        assertEquals(
            "me",
            (parsed.value("meta") as gokorei.tanseki.core.domain.MappingValue).entries["owner"]?.asText()
        )
        assertEquals(
            gokorei.tanseki.core.domain
                .NumberValue("42"),
            parsed.value("n")
        )
    }
}
