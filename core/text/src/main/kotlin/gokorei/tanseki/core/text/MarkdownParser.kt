@file:Suppress("CyclomaticComplexMethod", "ComplexCondition", "LoopWithTooManyJumpStatements")

package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.Frontmatter
import gokorei.tanseki.core.domain.Link

/** A markdown heading (`#`..`######`). */
data class Heading(val level: Int, val text: String)

/** The parsed form of a markdown document, as consumed by the indexer. */
data class ParsedMarkdown(
    val frontmatter: Frontmatter,
    val body: String,
    val headings: List<Heading>,
    val links: List<Link>,
    /**
     * The frontmatter block exactly as it appeared, before any interpretation.
     *
     * This is what makes a parse-then-render round trip lossless. The typed
     * [frontmatter] is a lossy index of the block — it cannot model every YAML
     * shape and never claimed to — so re-rendering from it would rewrite the
     * user's properties on every save.
     */
    val rawFrontmatter: String? = null,
    /**
     * Why the typed frontmatter is empty or partial, when parsing failed.
     *
     * A block we cannot model is preserved verbatim rather than rejected, which
     * keeps the note readable and its properties intact. That is a degradation,
     * not a success, so the reason is surfaced rather than swallowed — otherwise
     * an index built from `frontmatter` silently loses fields with nothing to
     * explain why.
     */
    val frontmatterError: String? = null
) {
    /**
     * The `![[embed]]` subset of [links].
     *
     * An embed names the same document a link would — the `!` is a rendering
     * instruction ("transclude here"), not a different reference — so embeds are
     * not a separate list at parse time. This view exists so a caller that needs
     * the distinction (edge typing, attachment preloading) does not re-scan the
     * body to recover it. Derived rather than stored, so the two can never
     * disagree.
     */
    val embeds: List<Link> get() = links.filter { it.embed }
}

/**
 * Pure Markdown parser: frontmatter, body, headings, `[[wikilinks]]`, and
 * `![[embeds]]`. Fenced code blocks are skipped so their contents do not
 * produce false headings or links. No I/O, no dependencies beyond `core:domain`.
 */
object MarkdownParser {
    private val FRONTMATTER_DELIMITER = Regex("^---\\s*$")
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private const val FENCE = "```"

    fun parse(text: String): ParsedMarkdown {
        val normalized = text.replace("\r\n", "\n")
        val (frontmatterBlock, body) = splitFrontmatter(normalized)
        val parsedBlock =
            frontmatterBlock
                ?.let {
                    try {
                        (FrontmatterCodec.parse(it).copy(rawFrontmatter = it) to null)
                    } catch (cause: IllegalArgumentException) {
                        // Only genuinely malformed YAML reaches here: FrontmatterCodec
                        // parses with a real engine, so every shape it understands is
                        // modelled rather than refused, and the exceptions it raises
                        // are syntax errors and resource bounds.
                        //
                        // A block we cannot read is still a block we must not destroy.
                        // Keep the typed view empty and carry the text through, so
                        // saving the note does not delete properties it did not
                        // understand — and record why, so the empty view is a stated
                        // degradation rather than a document that looks unadorned.
                        Frontmatter(rawFrontmatter = it) to (cause.message ?: "unreadable frontmatter")
                    }
                }
                ?: (Frontmatter() to null)
        val (frontmatter, frontmatterError) = parsedBlock

        val headings = mutableListOf<Heading>()
        val links = mutableListOf<Link>()
        var inFence = false

        for (line in body.split("\n")) {
            if (line.trimStart().startsWith(FENCE)) {
                inFence = !inFence
                continue
            }
            if (inFence) continue

            HEADING.matchEntire(line)?.let { match ->
                headings += Heading(match.groupValues[1].length, match.groupValues[2].trim())
            }
            parseWikilinks(line, links)
        }

        return ParsedMarkdown(
            frontmatter,
            body,
            headings,
            links,
            rawFrontmatter = frontmatterBlock,
            frontmatterError = frontmatterError
        )
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LoopWithTooManyJumpStatements")
    private fun parseWikilinks(line: String, links: MutableList<Link>) {
        linkSpans(line).forEach { span -> links += Link(span.target, span.label, span.anchor, span.isEmbed) }
    }

    /**
     * Replaces the target of every `[[wikilink]]` in [text] that [rename] maps to
     * a non-null value, leaving the rest of the document byte-identical.
     *
     * This walks the same scanner as [parse] on purpose. A rewriter with its own
     * idea of where a link starts and ends would eventually disagree with the
     * parser about the awkward cases — `[[` inside a `[[`, or `]]` inside a
     * `]]]` — and would then rewrite a link the parser never sees, or miss one
     * it does. Sharing the scan is what keeps the two in step.
     *
     * Only the target is substituted. An aliased link `[[target|label]]` keeps
     * its label, because the label is prose the author wrote for display and the
     * target is the reference that has to follow the document.
     */
    fun rewriteWikilinkTargets(text: String, rename: (String) -> String?): String {
        if (!text.contains("[[")) return text
        var inFence = false
        // Fence tracking is copied from [parse] rather than shared with it, because
        // it is stateful across lines and the two walkers advance differently. If
        // this drifted from the parser, a rename would rewrite a link inside a code
        // fence — an example that merely mentions the old name would be corrupted,
        // and it would corrupt silently, since the fence still renders fine.
        val rewritten =
            text.replace("\r\n", "\n").split("\n").joinToString("\n") { line ->
                if (line.trimStart().startsWith(FENCE)) {
                    inFence = !inFence
                    line
                } else if (inFence) {
                    line
                } else {
                    rewriteLine(line, rename)
                }
            }
        return rewritten
    }

    private fun rewriteLine(line: String, rename: (String) -> String?): String {
        val spans = linkSpans(line)
        if (spans.isEmpty()) return line
        val result = StringBuilder()
        var cursor = 0
        spans.forEach { span ->
            val replacement = rename(span.target) ?: return@forEach
            result.append(line, cursor, span.targetStart).append(replacement)
            cursor = span.targetEnd
        }
        return result.append(line, cursor, line.length).toString()
    }

    /**
     * One link's target, label and anchor, with the *document* target's bounds
     * inside the line.
     *
     * The bounds deliberately exclude the anchor. [rewriteWikilinkTargets] renames
     * documents, and `[[Note#Section]]` must become `[[Renamed#Section]]` — if the
     * span covered the anchor, the caret-side of the link would be renamed too and
     * the heading reference would be silently destroyed.
     */
    private data class LinkSpan(
        val target: String,
        val label: String?,
        val anchor: String?,
        val targetStart: Int,
        val targetEnd: Int,
        /**
         * True for `![[...]]`, Obsidian's embed marker.
         *
         * The `!` sits outside the brackets, so it is outside [targetStart] by
         * construction: a rename substitutes the target span only and the marker
         * survives untouched.
         */
        val isEmbed: Boolean
    )

    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "LoopWithTooManyJumpStatements")
    private fun linkSpans(line: String): List<LinkSpan> {
        val spans = mutableListOf<LinkSpan>()
        var cursor = 0
        while (cursor < line.length - 1) {
            val open = line.indexOf("[[", cursor)
            if (open < 0) break
            if (open > 0 && line[open - 1] == '[') {
                cursor = open + 2
                continue
            }
            val close = line.indexOf("]]", open + 2)
            if (close < 0) break
            if (close + 2 < line.length && line[close + 2] == ']') {
                cursor = close + 2
                continue
            }
            val content = line.substring(open + 2, close)
            val separator = content.indexOf('|')
            val rawTarget = if (separator < 0) content else content.substring(0, separator)
            val target = rawTarget.trim()
            val label = (if (separator < 0) "" else content.substring(separator + 1)).trim().ifEmpty { null }
            // Split on the FIRST `#`: a heading may itself contain one, as in
            // `[[Note#Part 1#Intro]]`, and only the first separates document from
            // place. `^` is Obsidian's block-reference marker, not part of the id.
            val hash = target.indexOf('#')
            val document = if (hash < 0) target else target.substring(0, hash).trim()
            val anchor =
                if (hash < 0) {
                    null
                } else {
                    target
                        .substring(hash + 1)
                        .trim()
                        .removePrefix("^")
                        .trim()
                        .ifEmpty { null }
                }
            // A link with no document target — `[[#Section]]` — points inside the
            // note that holds it. There is no other document to derive an edge to,
            // and inventing a self-edge would put every intra-note reference into
            // the graph as a cycle. It is dropped here rather than at the edge
            // layer so the parser and the rewriter agree on which spans exist.
            if (document.isNotEmpty() && document.none { it == '[' || it == ']' } &&
                label?.none { it == '[' || it == ']' } != false
            ) {
                // The target is `trim`med for matching, so the substituted span has
                // to be located rather than assumed: leading and trailing spaces
                // belong to the source text and must survive the rewrite.
                val targetStart = open + 2
                val leading = rawTarget.length - rawTarget.trimStart().length
                spans +=
                    LinkSpan(
                        target = document,
                        label = label,
                        anchor = anchor,
                        targetStart = targetStart + leading,
                        targetEnd = targetStart + leading + document.length,
                        // `![[` is Obsidian's embed; `[[` is a link. Anything else
                        // directly before the brackets (a letter, a quote) leaves
                        // the span a link — the marker is exactly one `!`.
                        isEmbed = open > 0 && line[open - 1] == '!'
                    )
            }
            cursor = close + 2
        }
        return spans
    }

    private fun splitFrontmatter(text: String): Pair<String?, String> {
        val lines = text.split("\n")
        if (lines.isEmpty() || !FRONTMATTER_DELIMITER.matches(lines.first())) return null to text
        var end = -1
        for (index in 1 until lines.size) {
            if (FRONTMATTER_DELIMITER.matches(lines[index])) {
                end = index
                break
            }
        }
        if (end < 0) return null to text
        val block = lines.subList(1, end).joinToString("\n")
        val body = lines.subList(end + 1, lines.size).joinToString("\n")
        return block to body
    }
}
