package gokorei.tanseki.cli.transfer

import gokorei.tanseki.core.domain.DocId
import gokorei.tanseki.core.domain.DocRef
import gokorei.tanseki.core.text.LinkResolver
import gokorei.tanseki.core.text.MarkdownParser
import gokorei.tanseki.core.domain.Collection as DocCollection

/**
 * Importer-side filename sanitize policy.
 *
 * Some Obsidian-legal filenames cannot become a [DocId]: [DocId] stays strict
 * and the vault scanner only tolerates them (skip-and-report), so an import
 * that dropped them would lose notes and one that mangled them silently would
 * leave links pointing nowhere. This object is the explicit policy that sits
 * between those two: every foreign name maps deterministically onto a valid
 * id, every mapping is reported, and collisions never overwrite.
 *
 * The three known rejections (from the listing-tolerance ticket):
 * - **W / surrounding whitespace** (`notes/trailing space .md`): each path
 *   segment is trimmed. Inner spacing is preserved; only the edges move.
 * - **C / embedded controls** (`notes/tab\there.md`): every ISO-control
 *   character (plus `\`, which [DocId] rejects as a separator) becomes `_`.
 *   Replacement — not deletion — keeps `a\tb` and `ab` distinct.
 * - **E / non-lowercase `.md` extension** (`notes/UPPER.MD`): the suffix is
 *   matched case-insensitively and normalised to lowercase `.md`. The stem
 *   keeps its case: `UPPER.MD` becomes id `UPPER`, not `upper`.
 *
 * Identity consequences: a rename breaks every inbound `[[wikilink]]` that
 * named the old file (edges are derived from link text on every index, so a
 * stale link silently drops its edge). Content is left byte-identical — links
 * are **not** rewritten — and the breakage is reported instead via
 * [WikilinkInvalidation], alongside the `source path -> target id` mapping the
 * consumer needs to repair links itself. The one exception is a pure
 * extension-case rename, whose links keep resolving (the resolver strips
 * `.md` case-insensitively), so no invalidation is reported for it.
 */
enum class SanitizeReason(val label: String) {
    SURROUNDING_WHITESPACE("surrounding-whitespace"),
    CONTROL_CHARACTERS("control-characters"),
    EXTENSION_CASE("extension-case")
}

/** One source filename mapped onto the id the import stores it under. */
data class SanitizedFile(
    val sourcePath: String,
    val targetPath: String,
    val targetId: DocId,
    val reasons: List<SanitizeReason>,
    val wasRenamed: Boolean
)

/** A rename as the operator sees it: source path -> target id. */
data class SanitizeRename(
    val sourcePath: String,
    val targetId: DocId,
    val targetPath: String,
    val reasons: List<SanitizeReason>
)

/** Two or more sources that sanitize onto one target id. The first (in sorted
 * source order) wins; the rest are reported and never written. */
data class SanitizeCollision(
    val targetId: DocId,
    val targetPath: String,
    val sources: List<String>,
    val winner: String
)

/**
 * A `[[wikilink]]` in [referrerSource] that named a renamed file's old
 * identity and no longer resolves to it post-import. Content is unchanged by
 * the import, so this link now dangles (or resolves elsewhere); the mapping in
 * [SanitizeRename] is what the consumer uses to repair it. Bare-name links
 * that still resolve through trailing-segment match are not invalidations and
 * are never reported here.
 */
data class WikilinkInvalidation(
    val referrerSource: String,
    val linkTarget: String,
    val oldTargetPath: String,
    val newTargetId: DocId
)

/** The deterministic outcome of sanitizing a batch of source paths. */
data class SanitizePlan(
    val files: List<SanitizedFile>,
    val renames: List<SanitizeRename>,
    val collisions: List<SanitizeCollision>,
    /** Sanitized target id by source path; colliding losers are absent. */
    val winners: Map<String, SanitizedFile>
)

object FilenameSanitizer {
    /**
     * Maps one Obsidian-legal relative path onto a valid [DocId].
     *
     * Pure and deterministic: the same [sourcePath] always yields the same
     * target. A name that already satisfies [DocId] is returned untouched
     * ([SanitizedFile.wasRenamed] is false and [SanitizedFile.reasons] is
     * empty).
     */
    fun sanitizeOne(sourcePath: String): SanitizedFile {
        val normalized = sourcePath.replace('\\', '/')
        val (stem, extensionCase) = stripMarkdownSuffix(normalized)
        val reasons = linkedSetOf<SanitizeReason>()
        if (extensionCase) reasons += SanitizeReason.EXTENSION_CASE
        if (normalized != sourcePath) reasons += SanitizeReason.CONTROL_CHARACTERS
        val cleaned = stem.split('/').map { cleanSegment(it, reasons) }.joinToString("/")
        val candidate = settleCandidate(cleaned.ifEmpty { "_" }, reasons)
        val targetId = DocId(candidate)
        val targetPath = "$candidate.md"
        // A path that round-trips byte-identical with no reason recorded is
        // clean even if the string comparison above disagrees on separators:
        // Windows separators were already normalised, so re-check against the
        // DocId validity the scanner enforces.
        val actuallyClean = targetPath == sourcePath && isValidIdPath(sourcePath)
        val renamed = (targetPath != sourcePath || reasons.isNotEmpty()) && !actuallyClean
        return SanitizedFile(
            sourcePath = sourcePath,
            targetPath = targetPath,
            targetId = targetId,
            reasons = if (actuallyClean) emptyList() else reasons.toList(),
            wasRenamed = renamed
        )
    }

    private fun cleanSegment(segment: String, reasons: MutableSet<SanitizeReason>): String {
        val trimmed = segment.trim()
        if (trimmed != segment) reasons += SanitizeReason.SURROUNDING_WHITESPACE
        val swept = trimmed.map { c -> if (c.isISOControl() || c == '\\') '_' else c }.joinToString("")
        if (swept != trimmed) reasons += SanitizeReason.CONTROL_CHARACTERS
        if (swept.isEmpty() || swept == "." || swept == "..") {
            if (!(swept.isEmpty() && trimmed.isEmpty())) reasons += SanitizeReason.CONTROL_CHARACTERS
            return "_"
        }
        return swept
    }

    private fun settleCandidate(cleaned: String, reasons: MutableSet<SanitizeReason>): String {
        var candidate = cleaned
        repeat(8) {
            val valid = runCatching { DocId(candidate) }.isSuccess
            if (valid) return candidate
            candidate =
                if (candidate.endsWith(".md", ignoreCase = true)) {
                    reasons += SanitizeReason.EXTENSION_CASE
                    candidate.dropLast(3) + "_md"
                } else {
                    candidate + "_"
                }
        }
        return DocId(candidate).value
    }

    /**
     * Sanitizes a batch, grouping post-sanitize collisions.
     *
     * The winner for a colliding target is the lexicographically first source,
     * so the outcome is stable across runs and filesystems.
     */
    fun sanitizeAll(sourcePaths: Collection<String>): SanitizePlan {
        val files = sourcePaths.sorted().map(::sanitizeOne)
        val byTarget = files.groupBy { it.targetId.value }
        val collisions = mutableListOf<SanitizeCollision>()
        val winners = linkedMapOf<String, SanitizedFile>()
        byTarget.values.forEach { group ->
            if (group.size == 1) {
                winners[group.single().sourcePath] = group.single()
            } else {
                val ordered = group.map { it.sourcePath }.sorted()
                val winner = ordered.first()
                collisions +=
                    SanitizeCollision(
                        targetId = group.first().targetId,
                        targetPath = group.first().targetPath,
                        sources = ordered,
                        winner = winner
                    )
                winners[winner] = group.first { it.sourcePath == winner }
            }
        }
        val renames =
            files
                .filter { it.wasRenamed }
                .map { SanitizeRename(it.sourcePath, it.targetId, it.targetPath, it.reasons) }
                .sortedBy { it.sourcePath }
        return SanitizePlan(
            files = files,
            renames = renames,
            collisions = collisions.sortedBy { it.targetId.value },
            winners = winners
        )
    }

    /**
     * Finds inbound `[[wikilinks]]` a rename invalidates.
     *
     * [contentsBySource] maps source path -> file text for every scanned file
     * (imported or not). A link counts as invalidated when it names a renamed
     * file's old identity but not its new one — compared in link form (the way
     * the resolver matches: trimmed, wrapper-stripped, suffix-insensitive) —
     * and it no longer resolves to the renamed note post-import. A pure
     * extension-case rename (`UPPER.MD` -> `UPPER.md`) reports nothing, and
     * neither do bare-name links (`[[UPPER]]`, `[[trailing space]]`) that still
     * resolve through trailing-segment match; only the exact-old-path link
     * (`[[notes/trailing space .md]]`) genuinely dangles and is reported.
     *
     * When [resolve] is supplied it must reflect the post-import store (the
     * caller in [DirectoryImporter] passes an `EdgeDeriver` over the target
     * plus planned documents, so pre-existing notes that could steal a
     * trailing-segment match are visible). When omitted, resolution is checked
     * against the plan's winners alone, which is exact for a fresh import.
     */
    fun findInvalidatedWikilinks(
        contentsBySource: Map<String, String>,
        plan: SanitizePlan,
        resolve: ((String) -> DocId?)? = null
    ): List<WikilinkInvalidation> {
        if (plan.renames.isEmpty()) return emptyList()
        val oldForms = oldFormIndex(plan.renames)
        val postImportResolve = resolve ?: winnersResolve(plan)
        val out = mutableListOf<WikilinkInvalidation>()
        contentsBySource.entries.sortedBy { it.key }.forEach { (source, content) ->
            out += invalidationsIn(source, content, oldForms, postImportResolve)
        }
        return out.sortedWith(compareBy({ it.referrerSource }, { it.linkTarget }, { it.oldTargetPath }))
    }

    private fun winnersResolve(plan: SanitizePlan): (String) -> DocId? {
        val refs =
            plan.winners.values.map { sanitized ->
                DocRef(sanitized.targetId, DocCollection("vault"), sanitized.targetPath)
            }
        val ids = refs.mapTo(mutableSetOf()) { it.id }
        return { target -> LinkResolver.resolve(target, exists = { it in ids }, refs = refs) }
    }

    private fun oldFormIndex(renames: List<SanitizeRename>): Map<String, List<SanitizeRename>> {
        val index = mutableMapOf<String, MutableList<SanitizeRename>>()
        renames.forEach { rename ->
            oldIdentityForms(rename.sourcePath).forEach { form ->
                index.getOrPut(form) { mutableListOf() } += rename
            }
        }
        return index
    }

    private fun invalidationsIn(
        source: String,
        content: String,
        oldForms: Map<String, List<SanitizeRename>>,
        resolve: (String) -> DocId?
    ): List<WikilinkInvalidation> {
        val links = runCatching { MarkdownParser.parse(content).links }.getOrDefault(emptyList())
        return links.map { it.target }.sorted().flatMap { target -> invalidationFor(source, target, oldForms, resolve) }
    }

    private fun invalidationFor(
        source: String,
        target: String,
        oldForms: Map<String, List<SanitizeRename>>,
        resolve: (String) -> DocId?
    ): List<WikilinkInvalidation> {
        val form = linkForm(target)
        if (form.isEmpty()) return emptyList()
        return oldForms[form]
            .orEmpty()
            .sortedBy { it.sourcePath }
            .filter { breaks(it, form) }
            .filter { rename ->
                resolve(target) != rename.targetId
            }.map { rename ->
                WikilinkInvalidation(
                    referrerSource = source,
                    linkTarget = target,
                    oldTargetPath = rename.sourcePath,
                    newTargetId = rename.targetId
                )
            }
    }
}

private fun stripMarkdownSuffix(path: String): Pair<String, Boolean> =
    if (path.endsWith(".md")) {
        path.dropLast(3) to false
    } else if (path.endsWith(".md", ignoreCase = true)) {
        path.dropLast(3) to true
    } else {
        path to false
    }

private fun isValidIdPath(relativePath: String): Boolean =
    runCatching {
        require(relativePath.endsWith(".md")) { "must end in .md" }
        DocId(relativePath.removeSuffix(".md"))
        true
    }.getOrDefault(false)

/**
 * The old identities a `[[wikilink]]` author could have written for
 * [sourcePath]: the full path, the stem, and the trailing segment, each in
 * link form. Kept beside [linkForm] (the resolver's normalisation) so the
 * two agree on what names what.
 */
private fun oldIdentityForms(sourcePath: String): Set<String> =
    buildSet {
        val stem = stripMarkdownSuffix(sourcePath.replace('\\', '/')).first
        listOf(sourcePath, stem, stem.substringAfterLast('/')).forEach { variant ->
            linkForm(variant).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

private fun linkForm(target: String): String {
    var form = target.trim()
    while (form.startsWith("./")) form = form.substring(2)
    form = form.removePrefix("/")
    return if (form.endsWith(".md", ignoreCase = true)) form.dropLast(3) else form
}

private fun breaks(rename: SanitizeRename, form: String): Boolean =
    linkForm(rename.targetId.value) != form && linkForm(rename.targetPath) != form
