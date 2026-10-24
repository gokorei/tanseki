package gokorei.tanseki.core.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Which kind of vault file a path names.
 *
 * A vault holds more than markdown: `.canvas` files are JSON documents Obsidian
 * renders as infinite whiteboards, and everything else with an extension is an
 * attachment (images, PDFs, audio) addressed by path rather than by document id.
 * The text layer classifies; it does not read the bytes.
 */
enum class DocumentKind {
    MARKDOWN,
    CANVAS,
    ATTACHMENT
}

/**
 * The Obsidian conventions Tanseki honours at the text layer, in one place.
 *
 * Each of these used to be answered ad hoc wherever a caller needed it — usually
 * by checking a suffix inline, usually slightly differently. A vault path ending
 * in `.MD` was markdown in one place and an attachment in another, and a daily
 * note was whatever string the caller happened to build. Centralising the rules
 * is what keeps those answers the same answer.
 *
 * No I/O: every function here is a pure function of its arguments.
 */
object ObsidianParity {
    const val MARKDOWN_SUFFIX = ".md"
    const val CANVAS_SUFFIX = ".canvas"

    private val TEMPLATE_TOKEN = Regex("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}")
    private val EXTENSION = Regex("[A-Za-z0-9]{1,10}")
    private val ISO_DAY = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * The kind of vault file [path] names, by suffix alone.
     *
     * Matching is case-insensitive because the filesystems Obsidian runs on
     * largely are: `NOTE.MD` opens as a note there, so it is one here.
     * A path with no recognised suffix is an attachment rather than an error —
     * a vault may hold any file, and "not a document" is a classification, not
     * a failure.
     */
    fun kindForPath(path: String): DocumentKind {
        val name = path.trim()
        return when {
            name.endsWith(CANVAS_SUFFIX, ignoreCase = true) -> DocumentKind.CANVAS
            name.endsWith(MARKDOWN_SUFFIX, ignoreCase = true) -> DocumentKind.MARKDOWN
            else -> DocumentKind.ATTACHMENT
        }
    }

    /** True when [path] names a canvas document (`*.canvas`, any case). */
    fun isCanvasPath(path: String): Boolean = kindForPath(path) == DocumentKind.CANVAS

    /**
     * True when a link [target] names an attachment rather than a note.
     *
     * The rule is deliberately narrow: the final path segment must carry a
     * non-markdown extension. `notes/foo` has no extension and `notes/foo.md`
     * is a note; `assets/photo.png` and `archive.tar.gz` are attachments.
     * A leading-dot file (`.gitignore`) has no extension, and neither does a
     * trailing dot (`draft.`), so neither is one.
     *
     * The [target] is the document part of a link — anchor and label already
     * split off — so `#` and `|` never reach here.
     */
    fun isAttachmentTarget(target: String): Boolean {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return false
        val stripped = trimmed.removePrefix("./").removePrefix("/")
        val name = stripped.substringAfterLast('/')
        if (name.isEmpty()) return false
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        val extension = name.substring(dot + 1)
        if (!EXTENSION.matches(extension)) return false
        return !extension.equals("md", ignoreCase = true)
    }

    /**
     * A content-type hint for an attachment [extension], without the leading dot.
     *
     * This is a preview hint, not a detector: the bytes are never inspected, and
     * an unlisted extension yields `null` rather than a guess. Case-insensitive,
     * because vaults are.
     */
    fun mediaTypeForExtension(extension: String): String? = MEDIA_TYPES[extension.lowercase()]

    /**
     * Attachment extensions whose bytes are plain text the Lookup can index.
     *
     * Deliberately narrow: only formats readable without a vendored decoder.
     * `txt` and `csv` are text by definition, `json` is the format vault
     * sidecars already use, and `svg` is XML text (indexed raw, markup
     * included — stripping tags would be a renderer, not a classifier).
     * Everything else a vault holds — images, audio, video, PDFs, archives —
     * needs a decoder Tanseki does not ship, so it is skipped rather than
     * indexed as noise. See [indexableContent].
     */
    val SEARCHABLE_ATTACHMENT_EXTENSIONS: Set<String> = setOf("txt", "csv", "json", "svg")

    /**
     * True when [path] names an attachment whose bytes are plain text.
     *
     * Markdown and canvas paths are never attachments, whatever their bytes —
     * they have their own indexing rules — so this is false for them.
     */
    fun isSearchableAttachment(path: String): Boolean {
        if (kindForPath(path) != DocumentKind.ATTACHMENT) return false
        val extension = path.trim().substringAfterLast('.', "")
        return extension.isNotEmpty() && extension.lowercase() in SEARCHABLE_ATTACHMENT_EXTENSIONS
    }

    /**
     * The text the Lookup should index for a vault file, or `null` when there
     * is deliberately nothing to index.
     *
     * - markdown: [content] unchanged — it is already text;
     * - canvas: the node text [canvasSearchText] extracts;
     * - a searchable attachment ([isSearchableAttachment]): [content] unchanged;
     * - anything else: `null`. A PNG or MP3 has no text without a decoder, and
     *   indexing its bytes would file binary noise under searchable terms.
     *   The document is still indexed (id, path, frontmatter survive) — only
     *   its full text is absent, which is what makes the skip list a recall
     *   boundary rather than a hole in the index.
     */
    fun indexableContent(path: String, content: String): String? =
        when (kindForPath(path)) {
            DocumentKind.MARKDOWN -> content
            DocumentKind.CANVAS -> canvasSearchText(content)
            DocumentKind.ATTACHMENT -> if (isSearchableAttachment(path)) content else null
        }

    /**
     * The searchable text of a `.canvas` payload: the `text` of every text
     * node, in document order, joined by blank lines.
     *
     * Only text nodes contribute. A `file` or `link` node names a reference —
     * that is a graph concern (edge derivation), not full text — and a canvas
     * edge connects two cards inside the board, not two vault documents, so
     * neither is surfaced here. A payload that is not a canvas object yields
     * [fallback] unchanged: an unreadable board must stay findable by its raw
     * bytes rather than vanish from the index.
     */
    fun canvasSearchText(fallback: String): String {
        val texts = mutableListOf<String>()
        val parsed =
            runCatching {
                val root = CANVAS_JSON.parseToJsonElement(fallback).jsonObject
                val nodes = root["nodes"]?.jsonArray ?: return@runCatching null
                nodes.forEach { node ->
                    // One odd card must not sink the board: a non-object node,
                    // a missing type, or a non-string text is skipped, not fatal.
                    val card = node as? JsonObject ?: return@forEach
                    val type = card["type"] as? JsonPrimitive ?: return@forEach
                    if (!type.isString || type.content != "text") return@forEach
                    val text = card["text"] as? JsonPrimitive ?: return@forEach
                    if (text.isString) texts += text.content
                }
                true
            }.getOrNull()
        return if (parsed == null) fallback else texts.joinToString("\n\n")
    }

    /**
     * Preview hints for the attachment kinds a vault commonly holds.
     *
     * A map rather than a branch: same answers, no complexity budget spent.
     */
    private val MEDIA_TYPES =
        mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "webp" to "image/webp",
            "pdf" to "application/pdf",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "mp4" to "video/mp4",
            "mov" to "video/quicktime",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "json" to "application/json"
        )

    /**
     * Lenient JSON reader for canvas payloads: unknown keys are ignored, and a
     * text node missing its `text` is a card without words, not a corrupt
     * board. Vault bytes are foreign input, so strictness here would turn one
     * plugin's extra field into an unsearchable canvas.
     */
    private val CANVAS_JSON = Json { ignoreUnknownKeys = true }

    /**
     * The vault path for [date]'s daily note.
     *
     * Obsidian's default daily-note format is the ISO day (`2026-10-08.md`) at
     * the vault root; [folder] relocates it when the vault configures one
     * (`daily/2026-10-08.md`). The format is fixed rather than configurable
     * here: a custom date format is an editor setting Tanseki does not read, so
     * accepting one would be a promise the path cannot keep.
     */
    fun dailyNotePath(date: LocalDate, folder: String? = null): String {
        val file = "${date.format(ISO_DAY)}$MARKDOWN_SUFFIX"
        val parent = folder?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (parent == null) file else "$parent/$file"
    }

    /**
     * True when [path]'s filename is a daily-note day (`YYYY-MM-DD.md`).
     *
     * The date must be a real one: `2026-13-99.md` matches the shape and names
     * no day, so it is not a daily note. The folder is ignored — a daily note
     * is recognised by its filename wherever it sits.
     */
    fun isDailyNotePath(path: String): Boolean {
        val file = path.trim().substringAfterLast('/')
        if (!file.endsWith(MARKDOWN_SUFFIX, ignoreCase = true)) return false
        val stem = file.dropLast(MARKDOWN_SUFFIX.length)
        return runCatching { LocalDate.parse(stem, ISO_DAY) }.getOrNull() != null
    }

    /**
     * Expands `{{key}}` tokens in an Obsidian template note.
     *
     * A token the [variables] map names is replaced; one it does not name is
     * left verbatim, because a template may carry placeholders for another tool
     * (`{{date}}` for a plugin that fills it later) and deleting what is not
     * understood is how a daily note loses its headings. Surrounding whitespace
     * inside the braces is ignored (`{{ title }}` works).
     */
    fun applyTemplate(template: String, variables: Map<String, String>): String =
        TEMPLATE_TOKEN.replace(template) { match ->
            variables[match.groupValues[1]] ?: match.value
        }
}
