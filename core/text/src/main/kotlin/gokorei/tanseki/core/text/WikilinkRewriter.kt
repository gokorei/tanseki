package gokorei.tanseki.core.text

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef

/**
 * Resolves the target of a `[[wikilink]]` to the document it names.
 *
 * A link names its target the way a person would — `[[Meeting notes]]`,
 * `[[notes]]`, `[[notes.md]]`, or `[[daily/notes]]` — so resolution accepts the
 * full id, the path with or without its suffix, and the trailing segment of a
 * nested path. An exact id or exact path wins; only then is a trailing-segment
 * match considered.
 *
 * This lives beside [WikilinkRewriter] because the two must agree. The indexer
 * uses it to decide whether a link resolves at all, and a rename uses it to
 * decide which links to re-point. If they each resolved by their own rules, a
 * rename could rewrite links the indexer never resolved, or — worse — leave
 * alone the ones it did, and the graph would rot without an error.
 */
object LinkResolver {
    fun resolve(
        target: String,
        exists: (DocId) -> Boolean,
        refs: List<DocRef>
    ): DocId? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val withoutSuffix = trimmed.linkForm()
        val direct = runCatching { DocId(withoutSuffix) }.getOrNull()
        if (direct != null && exists(direct)) return direct
        val path = "$withoutSuffix.md"
        return refs
            .firstOrNull { ref ->
                ref.id.value == withoutSuffix || ref.path == trimmed || ref.path == path || ref.path.endsWith("/$path")
            }?.id
    }

    /**
     * The document part of a link target, with the shapes Obsidian writes around
     * it removed.
     *
     * The same document is written several ways — `notes/foo`, `notes/foo.md`,
     * `./notes/foo.md`, `/notes/foo.md`, and `notes/foo.MD` on a case-insensitive
     * filesystem — and all of them name it. Only the `.md` suffix was being
     * stripped, and only the lowercase one, so a link written as `./notes/foo.md`
     * resolved to nothing while the document sat right there. A wikilink is prose,
     * not a path expression; the author is not expected to know which of the
     * accepted spellings the resolver happens to prefer.
     *
     * Anything that is not one of those wrappers is left exactly as written. A
     * traversal (`../secret`), an empty segment (`notes//foo`) or a foreign
     * extension must not resolve just because it was normalised into something
     * plausible — an unresolvable link stays unresolvable.
     */
    private fun String.linkForm(): String {
        var form = trim()
        while (form.startsWith(RELATIVE_PREFIX)) form = form.substring(RELATIVE_PREFIX.length)
        form = form.removePrefix(VAULT_ABSOLUTE_PREFIX)
        return if (form.endsWith(MARKDOWN_SUFFIX, ignoreCase = true)) {
            form.dropLast(MARKDOWN_SUFFIX.length)
        } else {
            form
        }
    }

    private const val RELATIVE_PREFIX = "./"
    private const val VAULT_ABSOLUTE_PREFIX = "/"
    private const val MARKDOWN_SUFFIX = ".md"
}

/**
 * Re-points `[[wikilinks]]` at a document's new id, for use by a store that is
 * moving that document.
 *
 * A wikilink names its target as prose — `[[Meeting notes]]`, `[[notes]]`,
 * `[[notes.md]]` — and the resolver decides which document that means, matching
 * on the id, on the path, and on the trailing segment of a nested path. So the
 * test for "does this link point at the document being renamed" has to go
 * through the same resolver the indexer uses. Comparing link text to the id
 * directly would rewrite almost nothing: the links a user actually writes rarely
 * spell the full id.
 *
 * That is also why the replacement is [to]'s full id rather than the old text
 * carried over. Preserving the shape of the old link would leave a link that only
 * resolves while the document happens to sit where the author last expected it;
 * writing the id makes the reference explicit and resolvable from anywhere in
 * the vault.
 *
 * [resolve] must reflect the vault *after* the move for targets other than
 * [from] — an unrelated link must not be rewritten because it briefly resolved
 * to [from] for some other reason.
 */
object WikilinkRewriter {
    fun retarget(
        content: String,
        resolve: (String) -> DocId?,
        from: DocId,
        to: DocId
    ): String =
        MarkdownParser.rewriteWikilinkTargets(content) { target ->
            if (resolve(target) == from) to.value else null
        }
}
