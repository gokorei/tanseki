package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.Frontmatter

/**
 * Renders a document back to markdown. `MarkdownParser.parse(render(fm, body))`
 * round-trips to the same frontmatter and body.
 */
object MarkdownSerializer {
    fun render(frontmatter: Frontmatter, body: String): String {
        // A verbatim block wins over the typed view. The typed view cannot model
        // every YAML shape — inline lists, non-alphabetical key order, quoting
        // style — so re-serializing it would rewrite properties on every save
        // even when nothing semantically changed.
        val block = frontmatter.rawFrontmatter ?: FrontmatterCodec.serialize(frontmatter)
        if (block.isBlank()) return body
        // Normalise the trailing newline explicitly. `serialize` happens to end
        // with one, but a verbatim block captured by `splitFrontmatter` does
        // not, and concatenating it directly produced `...- bob---` — a block
        // that no longer re-parses, so the note became unreadable after one save.
        return "---\n${block.trimEnd('\n')}\n---\n$body"
    }
}
