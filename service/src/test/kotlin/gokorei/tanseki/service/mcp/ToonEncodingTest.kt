package gokorei.tanseki.service.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Golden tests pinned to the TOON wire format as `dev.toonformat:jtoon` emits
 * it, so a dependency upgrade that changes the MCP wire format fails here
 * rather than silently in front of a model. TOON is specified and implemented
 * publicly under the MIT license; see `THIRD_PARTY_NOTICES.md` for the required
 * attribution notice.
 */
class ToonEncodingTest {
    @Test
    fun `encodes a list of uniform objects as a tabular array`() {
        val hits =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "a")
                        put("score", 1.0)
                        put("snippet", "hello")
                    }
                )
                add(
                    buildJsonObject {
                        put("id", "b")
                        put("score", 0.5)
                        put("snippet", JsonNull)
                    }
                )
            }

        assertEquals(
            """
            hits[2]{id,score,snippet}:
              a,1,hello
              b,0.5,null
            """.trimIndent(),
            Toon.encode(
                buildJsonObject {
                    put("hits", hits)
                }
            )
        )
    }

    @Test
    fun `encodes nested objects and inline primitive arrays`() {
        val document =
            buildJsonObject {
                put("id", "org/repo/pr-1/e1")
                put("collection", "vault")
                put("path", "org/repo/pr-1/e1.md")
                put("content", "Because X.")
                put("contentHash", "abc")
                put("revision", "abc")
                put("updatedAt", "2026-01-01T00:00:00Z")
                put("deleted", false)
                put(
                    "frontmatter",
                    buildJsonObject {
                        put("title", "Why?")
                        put("tags", buildJsonArray { add("design") })
                        put("repo", "org/repo")
                        put("pr", "42")
                    }
                )
            }

        assertEquals(
            """
            id: org/repo/pr-1/e1
            collection: vault
            path: org/repo/pr-1/e1.md
            content: Because X.
            contentHash: abc
            revision: abc
            updatedAt: "2026-01-01T00:00:00Z"
            deleted: false
            frontmatter:
              title: Why?
              tags[1]: design
              repo: org/repo
              pr: "42"
            """.trimIndent(),
            Toon.encode(document)
        )
    }

    @Test
    fun `encodes inline primitive arrays and scalars`() {
        assertEquals(
            "ids[2]: a,b",
            Toon.encode(
                buildJsonObject {
                    put(
                        "ids",
                        buildJsonArray {
                            add("a")
                            add("b")
                        }
                    )
                }
            )
        )
    }

    @Test
    fun `encodes a non-uniform array of objects as list items`() {
        val history =
            buildJsonObject {
                put(
                    "revisions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("docId", "x")
                                put("revision", "r1")
                                put("author", "mcp")
                                put("message", "upsert")
                                put("createdAt", "t")
                                put("deps", buildJsonArray {})
                            }
                        )
                    }
                )
            }

        assertEquals(
            """
            revisions[1]:
              - docId: x
                revision: r1
                author: mcp
                message: upsert
                createdAt: t
                deps: []
            """.trimIndent(),
            Toon.encode(history)
        )
    }

    @Test
    fun `encodes errors with an error line and details block`() {
        val details =
            buildJsonObject {
                put("code", "not_found")
                put("hint", "use the search tool")
            }

        assertEquals(
            """
            error: no document with id 'x'
            details:
              code: not_found
              hint: use the search tool
            """.trimIndent(),
            Toon.encode(
                buildJsonObject {
                    put("error", "no document with id 'x'")
                    put("details", details)
                }
            )
        )
    }

    @Test
    fun `encodes a nested object inside a list item`() {
        val payload =
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "a")
                                put(
                                    "frontmatter",
                                    buildJsonObject {
                                        put("repo", "r")
                                        put("tags", buildJsonArray { add("x") })
                                    }
                                )
                            }
                        )
                    }
                )
                put("count", 1)
            }

        assertEquals(
            """
            items[1]:
              - id: a
                frontmatter:
                  repo: r
                  tags[1]: x
            count: 1
            """.trimIndent(),
            Toon.encode(payload)
        )
    }

    @Test
    fun `encodes an object whose values share one shape as a keyed tabular array`() {
        // Keyed tabular is TOON's fourth form and the reason this encoder is
        // delegated to the reference library: the previous hand-written
        // implementation could not produce it at all, because it only considered
        // an array tabular when every element held primitives. Locking the
        // output here means a future library bump cannot silently drop the form.
        val environments =
            buildJsonObject {
                put(
                    "environments",
                    buildJsonObject {
                        put(
                            "production",
                            buildJsonObject {
                                put("region", "eu")
                                put("replicas", 6)
                            }
                        )
                        put(
                            "staging",
                            buildJsonObject {
                                put("region", "eu")
                                put("replicas", 2)
                            }
                        )
                    }
                )
            }

        assertEquals(
            """
            environments[2:]{region,replicas}:
              production: eu,6
              staging: eu,2
            """.trimIndent(),
            Toon.encode(environments)
        )
    }

    @Test
    fun `renders an empty array as an empty literal`() {
        assertEquals("deps: []", Toon.encode(buildJsonObject { put("deps", buildJsonArray {}) }))
    }

    @Test
    fun `normalises a number that has no fractional part`() {
        // v1.3 emitted `1.0`; the specification's canonical number formatting
        // drops the trailing zero. Recorded because it is a wire-visible change.
        assertEquals("s: 1", Toon.encode(buildJsonObject { put("s", 1.0) }))
    }
}
