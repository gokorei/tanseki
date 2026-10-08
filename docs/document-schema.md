# Document schema

How Tanseki reads an Obsidian vault file: the frontmatter contract, the link
and embed syntax, and the non-markdown files a vault carries alongside its notes.

The parser is `core:text` (`MarkdownParser`, `FrontmatterCodec`, `ObsidianParity`)
over the `core:domain` model (`Frontmatter`, `Link`). It performs no I/O.

## Frontmatter

A YAML block between leading `---` lines, parsed as real YAML. The typed view is
a lossy index of the block; the verbatim block is preserved alongside it, and a
save re-emits the verbatim text rather than re-rendering the typed view, so an
edit to the body never rewrites the author's property formatting.

Contract keys (typed fields; never `values` entries, never `fm_*` filterable):

| key            | shape                        |
|----------------|------------------------------|
| `title`        | scalar text                  |
| `author`       | scalar text                  |
| `tags`         | one scalar or a sequence     |
| `aliases`      | one scalar or a sequence     |
| `updated_at`   | instant                      |
| `content_hash` | scalar text                  |

`aliases` is Obsidian's alternate-names key. It reads the same way `tags` does —
`aliases: Foo`, `aliases: [Foo, Bar]`, or a block sequence — because the author
may write any of them and the distinction carries no meaning. A document with no
`aliases` key has an empty list, not a null.

Every other key keeps the shape it was written in (scalar, sequence, or nested
map) under `values`. A key with no value (`summary:`) is absent, not empty. A
block the parser cannot model is kept verbatim with the reason surfaced, so a
save does not delete properties it did not understand.

## Links and embeds

- `[[target]]`, `[[target|label]]`, `[[target#anchor]]`, `[[target#^block]]`.
- Only the first `#` separates document from place; the anchor may itself
  contain one (`[[Deep#A#B]]` → document `Deep`, anchor `A#B`).
- `[[#Section]]` points inside its own note and produces no link: there is no
  other document, and a self-edge would file every intra-note reference as a
  graph cycle.
- Links inside fenced code blocks are not links.
- `![[target]]` is an **embed** (transclusion). It names the same document a
  plain link would — the `!` is a rendering instruction, not a different
  reference — so embeds appear in the link list with `embed = true` and are
  additionally reachable through the parsed document's `embeds` view. Renames
  substitute the target span only, so the `!` marker survives a rewrite.

Resolution accepts the spellings a person writes (`notes/foo`, `notes/foo.md`,
`./notes/foo.md`, `/notes/foo.md`, `foo.MD`, trailing-segment `foo`) and
refuses traversals, empty segments, and unknown targets.

## Attachments

A link target whose final segment carries a non-markdown extension names an
attachment: `assets/photo.png`, `clip.mp3`, `archive.tar.gz`. Notes (`notes/foo`,
`notes/foo.md`) and dotfiles (`.gitignore`) are not attachments. The text layer
classifies the target and hints a media type for common extensions; it never
inspects the bytes, and an unlisted extension yields no hint rather than a
guess.

## Canvases

A path ending in `.canvas` (any case) is a canvas document — Obsidian's JSON
whiteboard format — not a note and not an attachment. The text layer recognises
the kind by suffix; the JSON payload is opaque to it.

## Daily notes and templates

- A daily note is recognised by filename: `YYYY-MM-DD.md` with a real calendar
  date (`2026-13-99.md` matches the shape and names no day). The folder is
  ignored — `daily/2026-10-08.md` and `2026-10-08.md` are both daily notes.
- New daily-note paths are built as the ISO day with a `.md` suffix at the vault
  root, or under a configured folder (`daily/2026-10-08.md`). The format is
  fixed: a custom editor date format is a setting Tanseki does not read.
- Template notes expand `{{key}}` tokens from a variable map. A token with no
  supplied value is left verbatim, because a template may carry placeholders
  for another tool and deleting what is not understood is how a new note loses
  its headings.

## Relationship vocabulary

Tanseki derives a typed graph from documents. This section records the relationship
vocabulary, what each type derives from, and what a `references` edge that
points at nothing means. The code-first OpenAPI spec (`docs/openapi.json`) and
the generated SDK are the enforceable form of the vocabulary; this document is
the explanation.

There are four relationship types, and only four. They are declared in
`RelTypes` (`core/domain/.../Frontmatter.kt`) and published as the `TraverseRel`
enum on `TraverseRequest`/`BacklinksRequest`:

| `rel`        | derives from                              | target                          |
|--------------|-------------------------------------------|---------------------------------|
| `links-to`   | `[[wikilink]]` in the body                | a document, only when it resolves |
| `references` | frontmatter `repo`, `pr`, `jira`         | a document, only when it resolves (see below) |
| `embeds`     | frontmatter `files`                       | a document, only when it resolves |
| `mentions`   | frontmatter `author`                      | a synthetic `author/<name>` id  |

Frontmatter **keys** (`files`, `repo`, `pr`, `jira`, `author`) are not
relationship types: they say where an edge comes from, not what it is.
A traversal asking for one is rejected with a `400` naming the four valid
values. An empty `{"ids":[]}` answer therefore always means "no such edges",
never "no such relationship" — the failure that previously let a caller pass
`rel: "repo"` and read the empty list as "no related documents".

## Decision: `references` edges resolve to documents

A `repo`/`pr`/`jira` value goes through the same resolution as a `files` value
or a wikilink target. When it names a document that exists, the edge points at
that document; when it names nothing, **no edge is derived at all** — there is
no synthetic `repo/<value>` placeholder id.

The alternative — keeping `references` synthetic so that a `repo` edge names a
repository rather than a document — was rejected because it made the type
untraversable: the API layer only returns edges whose target is a real document,
so every synthetic edge was filtered out of every traversal answer, and the
repair path (`EdgeDeriver.repairTarget`, which re-derives referrers when a
target appears) could never fire for these keys.

Consequences of resolving:

- A dangling `references` value is **absent from the graph**, which makes it
  indistinguishable from "no documents yet" by traversing. That is inherent:
  both cases are the absence of an edge.
- The way to tell them apart is a frontmatter filter, not a traversal.
  `fm=repo=org/repo` lists the documents that *carry* the value whether or not
  it resolves, while traversing `references` lists only the values that
  resolved. Sibling discovery ("the other documents from this repo/PR") is a
  filter question; reaching the referenced document itself is a traversal.
- When the referenced document is written *after* the referrer, the referrer's
  edge appears at that point: indexing a document repairs every other document
  whose derivation can now resolve to it.
- `mentions` is the deliberate exception: an author is a name, not a document,
  so its target stays a synthetic `author/<name>` id.
