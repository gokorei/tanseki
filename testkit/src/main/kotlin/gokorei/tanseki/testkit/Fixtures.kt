package gokorei.tanseki.testkit

import java.nio.file.Files
import java.nio.file.Path

/**
 * Fixture vault used by FileStore / Lookup / indexer tests. The files live under
 * `testkit/src/main/resources/fixtures/vault` and follow the document schema
 * (frontmatter, wikilinks) plus a binary attachment.
 *
 * `obsidianFiles` is the high-fidelity Obsidian-shaped vault under
 * `fixtures/vault-obsidian`: nested property lists and maps, folded scalars,
 * inline `aliases`/`cssclasses`, dataview constructs, callouts, heading and
 * block anchors, embeds, unicode and spaced names, sanitize-shaped names, and
 * an `.obsidian/` directory that must never import.
 */
object Fixtures {
    val files: List<String> =
        listOf(
            "notes/api.md",
            "notes/design.md",
            "glossary.md",
            "attachments/logo.svg"
        )

    val obsidianFiles: List<String> =
        listOf(
            "notes/Index.md",
            "notes/Project Alpha.md",
            "notes/daily/2024-01-15.md",
            "notes/Glossário — café.md",
            "notes/Reference.md",
            "notes/Plain.md",
            "notes/Empty.md",
            "notes/Duplicate Keys.md",
            "notes/UPPER.MD",
            "notes/trailing space .md",
            ".obsidian/app.json",
            ".obsidian/appearance.json",
            "attachments/logo.svg"
        )

    fun copyTo(target: Path) {
        copyFiles("vault", files, target)
    }

    fun copyObsidianTo(target: Path) {
        copyFiles("vault-obsidian", obsidianFiles, target)
    }

    private fun copyFiles(vault: String, relatives: List<String>, target: Path) {
        for (relative in relatives) {
            val resource = Fixtures::class.java.getResourceAsStream("/fixtures/$vault/$relative") ?: continue
            val destination = target.resolve(relative)
            Files.createDirectories(destination.parent)
            resource.use { Files.copy(it, destination) }
        }
    }

    fun read(relative: String): String = readFrom("vault", relative)

    fun readObsidian(relative: String): String = readFrom("vault-obsidian", relative)

    private fun readFrom(vault: String, relative: String): String =
        Fixtures::class.java
            .getResourceAsStream("/fixtures/$vault/$relative")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing fixture: $vault/$relative")
}
