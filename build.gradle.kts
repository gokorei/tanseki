import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    // `base` gives the root project a real `check`/`build` lifecycle, so the
    // repository-wide gates below run from `./gradlew build` instead of only
    // when a caller hand-lists them.
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

val tansekiVersion: String = providers.gradleProperty("tansekiVersion").get()
val tansekiGroup: String = providers.gradleProperty("tansekiGroup").get()

allprojects {
    group = tansekiGroup
    version = tansekiVersion
}

val specVersionProperty = "version=$tansekiVersion"
val licenseProperty = "licenseName=Apache-2.0"
val gitHost = providers.gradleProperty("tansekiGitHost").get()
val gitUserId = providers.gradleProperty("tansekiGitUserId").get()
val gitRepoId = providers.gradleProperty("tansekiGitRepoId").get()
val generatorMetadata = listOf(
    specVersionProperty,
    licenseProperty,
    "gitHost=$gitHost",
    "gitUserId=$gitUserId",
    "gitRepoId=$gitRepoId",
    // Without these two the generated package metadata and every generated
    // source header are attributed to the generator's own project and contact
    // address, which misattributes a published artifact to a third party.
    "infoName=${providers.gradleProperty("tansekiInfoName").get()}",
    "infoEmail=${providers.gradleProperty("tansekiInfoEmail").get()}",
).joinToString(",")

// openapi-generator 7.25.0 has no CLI option for `gitUserId`/`gitRepoId`: they
// are only reachable through the programmatic `CodegenConfigurator` setters, so
// `--additional-properties` resolves them to nothing and the generator emits its
// own `GIT_USER_ID`/`GIT_REPO_ID` defaults into the repository URL of the
// package metadata, the README, and `git_push.sh`. Left alone, every published
// artifact carries a dead `https://github.com/GIT_USER_ID/GIT_REPO_ID` link.
// `normalizeTextFile` rewrites them, which runs on both the checked-in and the
// drift tree, so the two stay byte-identical and the drift gate still means
// something.
val unsubstitutedGitIdentity =
    mapOf("GIT_HOST" to gitHost, "GIT_USER_ID" to gitUserId, "GIT_REPO_ID" to gitRepoId)

// Every module is configured by the `tanseki.kotlin-library` convention plugin,
// which also enforces the architecture dependency rules. See build-logic/.

// Aggregate detekt baseline over every module's hand-written main sources.
// Regenerate with `./gradlew detektBaseline` after intentional changes to the
// lint surface.
//
// Generated modules are excluded: `sdk/generated/**` is machine-written, is
// never linted by a module task (it skips the convention plugin on purpose), and
// baselining it would bury the real entries under generator noise.
val detektCliAggregate = configurations.create("detektCliAggregate") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("detektCliAggregate", libs.detekt.cli)
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Regenerates the shared detekt baseline."
    classpath = detektCliAggregate
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    val sourceDirs = subprojects
        .filterNot { it.path.startsWith(":sdk:") }
        .map { it.file("src/main/kotlin") }
        .filter { it.exists() }
        .map { it.absolutePath }
    val configFile = file("config/detekt/detekt.yml")
    val baselineFile = file("config/detekt/baseline.xml")
    args = listOf(
        "--input", sourceDirs.joinToString(","),
        "--config", configFile.absolutePath,
        "--baseline", baselineFile.absolutePath,
        "--build-upon-default-config",
        "--create-baseline",
    )
    // The baseline is shared by every module's detekt pass, so the regenerated
    // file must contain exactly the entries the per-module runs will filter. It
    // is a checked-in source file, so it is never declared as a task output.
    inputs.file(configFile).withPathSensitivity(PathSensitivity.NONE)
}

// ---------------------------------------------------------------------------
// One lint pass per module, reachable through a single aggregate task. Naming
// `ktlintCheck`/`detekt` alongside the lifecycle in a build command scheduled
// the same pass from two directions; the aggregate is the only entry point CI
// and contributors need.
// ---------------------------------------------------------------------------
val lint = tasks.register("lint") {
    group = "verification"
    description = "Runs ktlint and detekt once for every module."
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "lint" } })
}

// ---------------------------------------------------------------------------
// Text hygiene. The generator emits trailing whitespace in generated sources, so
// it is stripped deterministically at generation time; that keeps the
// checked-in output and the drift output byte-identical and keeps the whole
// repository inside the `.editorconfig` `trim_trailing_whitespace = true` rule.
// Markdown is exempt, matching `[*.md] trim_trailing_whitespace = false`.
// ---------------------------------------------------------------------------
val textExtensions =
    setOf(
        "kt", "kts", "sq", "sqm", "py", "json", "yml", "yaml", "toml", "txt", "properties", "sh", "cfg", "ini", "xml",
        "md"
    )

val gatedTextExtensions = textExtensions - "md"

/** Extension-less text files that are still gated, e.g. a container build file. */
val gatedFileNames = setOf("Dockerfile")

// A line that is *only* whitespace is left alone: ktlint accepts it, and it is
// sometimes load-bearing inside a raw string. The rule below matches ktlint's
// `no-trailing-spaces` so the two authorities never disagree.
val trailingWhitespace = Regex("[ \\t]+(?=\\r?\\n)|[ \\t]+$", RegexOption.MULTILINE)

/** The generator ends some files with a blank line; keep one terminating newline. */
val trailingBlankLines = Regex("(?:\\r?\\n)+\\z")

/** Never part of a scanned or compared tree: build output and tool caches. */
val ignoredDirectories =
    setOf(".git", ".gradle", ".kotlin", "build", "sdk-drift", "__pycache__", ".pytest_cache", ".idea", ".venv")

fun normalizeTextFile(file: File) {
    val original = file.readText(Charsets.UTF_8)
    // `\\b` is what keeps the substitution off an identifier that merely starts
    // with a placeholder, and the replacement is a literal so a `$` in the
    // repository identity cannot be read as a capture reference.
    val substituted =
        unsubstitutedGitIdentity.entries.fold(original) { text, (placeholder, identity) ->
            text.replace(Regex("\\b$placeholder\\b"), identity.replace("$", "\\$"))
        }
    val normalized = trailingBlankLines.replace(trailingWhitespace.replace(substituted, ""), "\n")
    if (normalized != original) file.writeText(normalized, Charsets.UTF_8)
}

/** Strips trailing whitespace from generated text so generator output is reproducible. */
fun normalizeGeneratedOutput(root: File) {
    if (!root.isDirectory) return
    root
        .walkTopDown()
        .onEnter { it == root || it.name !in ignoredDirectories }
        .filter { it.isFile && it.extension.lowercase() in textExtensions }
        .forEach(::normalizeTextFile)
}

val whitespaceRoots =
    listOf(
        "src", "docs", "config", "sdk", "gradle", "build-logic", ".github", "adapters", "core", "composition",
        "service", "cli", "testkit", "gradle.properties", "settings.gradle.kts", "build.gradle.kts", ".editorconfig",
        ".gitignore", ".dockerignore"
    )

fun isGatedText(file: File): Boolean {
    if (!file.isFile) return false
    val extension = file.extension.lowercase()
    // `Dockerfile` and friends carry no extension but are still gated text.
    return if (extension.isEmpty()) file.name in gatedFileNames else extension in gatedTextExtensions
}

val checkNoTrailingWhitespace = tasks.register("checkNoTrailingWhitespace") {
    group = "verification"
    description = "Fails when a repository text file has trailing whitespace."
    val scanRoots = whitespaceRoots.map(::file).filter { it.exists() }
    // Declare the gated files themselves rather than the whole trees: a source
    // root also contains other tasks' build output, and declaring those as
    // inputs would both hide real changes and trip Gradle's implicit-dependency
    // validation.
    val gatedFiles =
        scanRoots.flatMap { root ->
            when {
                root.isFile -> listOf(root)
                else ->
                    root
                        .walkTopDown()
                        .onEnter { it == root || it.name !in ignoredDirectories }
                        .filter(::isGatedText)
                        .toList()
            }
        }
    inputs.files(gatedFiles)
    inputs.property("gatedExtensions", gatedTextExtensions)
    inputs.property("gatedFileNames", gatedFileNames)
    val report = layout.buildDirectory.file("reports/checkNoTrailingWhitespace/trailing-whitespace.txt")
    outputs.file(report).withPropertyName("report")
    doLast {
        val offenders = linkedMapOf<String, Int>()
        gatedFiles.forEach { file ->
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val count = text.lineSequence().count { it.isNotBlank() && it.last().isWhitespace() }
            if (count > 0) offenders[file.path] = count
        }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(offenders.entries.joinToString("\n") { "${it.value}\t${it.key}" })
        check(offenders.isEmpty()) {
            "Trailing whitespace in ${offenders.size} file(s). Full list: ${output.absolutePath}\n" +
                offenders.entries.joinToString("\n") { "  ${it.value}  ${it.key}" }
        }
    }
}

// ---------------------------------------------------------------------------
// SDK generation from the code-first OpenAPI seam contract.
//
// The routes are the source of truth. `:service:exportOpenApi` refreshes
// `docs/openapi.json`; the generate tasks below refresh the checked-in clients.
//
// Drift checking is a pure read of the work tree. It generates both clients from
// the *committed* spec into `build/sdk-drift/**` and compares them with the
// checked-in clients, so the only task it can schedule is the generator itself:
// `:service:checkOpenApi` is the separate authority on whether the committed spec
// still matches the routes. Keeping the two apart is what makes the check
// non-mutating — nothing in its graph may write `docs/openapi.json` or a checked-in
// client, which is also why it cannot race a regeneration running in the same
// invocation.
// ---------------------------------------------------------------------------
val openApiGenerator by configurations.creating

dependencies {
    add("openApiGenerator", libs.openapi.generator.cli)
}

val committedSpec = file("docs/openapi.json")

/** One generated client plus everything the drift check needs to mirror it. */
class SdkTarget(
    val taskName: String,
    val driftTaskName: String,
    val generator: String,
    val checkedInDir: String,
    val buildDir: String,
    val tansekiOwned: Set<String>
)

val kotlinTarget =
    SdkTarget(
        taskName = "generateSdkKotlin",
        driftTaskName = "generateSdkKotlinDrift",
        generator = "kotlin",
        checkedInDir = "sdk/generated/kotlin",
        buildDir = "sdk-drift/kotlin",
        tansekiOwned =
            setOf(
                ".openapi-generator-ignore",
                "build",
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "gradlew",
                "gradlew.bat",
                "gradle",
                ".github",
                "src/test"
            )
    )

val sdkTargets = listOf(kotlinTarget)

fun ignoreFileOf(target: SdkTarget): File = file("sdk/generated/${target.generator}/.openapi-generator-ignore")

fun checkedInOf(target: SdkTarget): File = file(target.checkedInDir)

fun driftOf(target: SdkTarget): File = layout.buildDirectory.dir(target.buildDir).get().asFile

/** Path prefixes the ignore file reserves for Tanseki; never pruned, never compared. */
fun preservedPatternsOf(target: SdkTarget): List<String> =
    ignoreFileOf(target)
        .takeIf { it.isFile }
        ?.readLines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") }
        .orEmpty()

fun registerSdkTarget(target: SdkTarget) =
    tasks.register<JavaExec>(target.taskName) {
        group = "build"
        description = "Generates the ${target.generator} client into ${target.checkedInDir}."
        classpath = openApiGenerator
        mainClass.set("org.openapitools.codegen.OpenAPIGenerator")
        dependsOn(":service:exportOpenApi")
        val generated = checkedInOf(target)
        val manifest = generated.resolve(".openapi-generator/FILES")
        val previousManifest = layout.buildDirectory.file("${target.buildDir}/previous-FILES")
        inputs.file(committedSpec).withPathSensitivity(PathSensitivity.NONE)
        inputs.file(ignoreFileOf(target))
        inputs.property("generatorMetadata", generatorMetadata)
        inputs.files(openApiGenerator)
            .withPropertyName("generatorClasspath")
            .withNormalizer(ClasspathNormalizer::class)
        // The directory is not declared as an output: it holds Tanseki-owned files
        // that the generator must not overwrite, so Gradle must never treat it
        // as something it may clean or restore.
        args = listOf(
            "generate",
            "-i", committedSpec.absolutePath,
            "-g", target.generator,
            "-o", generated.absolutePath,
            "--additional-properties", generatorMetadata,
        )
        doFirst {
            if (manifest.isFile) {
                previousManifest.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(manifest.readText())
                }
            }
        }
        doLast {
            // Drop files the previous run generated that the current spec no
            // longer produces, so a removed model cannot linger in the tree.
            // Ignore-listed paths are never touched, so the hand-written files
            // inside the generated tree survive every regeneration.
            val before =
                previousManifest.get().asFile
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.lines()
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            val after =
                manifest.takeIf { it.isFile }?.readText()?.lines()?.filter { it.isNotBlank() }.orEmpty().toSet()
            val preserved = preservedPatternsOf(target).map { it.trimEnd('/') }
            before
                .filterNot { it in after }
                .filterNot { relative -> preserved.any { relative == it || relative.startsWith("$it/") } }
                .forEach { relative ->
                    val stale = generated.resolve(relative)
                    if (stale.isFile) stale.delete()
                }
            previousManifest.get().asFile.delete()
            normalizeGeneratedOutput(generated)
        }
    }

val generateSdkKotlin = registerSdkTarget(kotlinTarget)

tasks.register("generateSdk") {
    group = "build"
    description = "Generates the Kotlin SDK from the code-first OpenAPI spec."
    dependsOn(generateSdkKotlin)
}

// Aliases named after the ticket contracts.
tasks.register("generateKotlinSdk") { group = "build"; dependsOn(generateSdkKotlin) }

// Drift generation reads the committed spec and writes only under `build/`. The
// stale-output wipe is a separate task rather than a `doFirst` on the generator,
// so the generator's declared output is never destroyed by the task that declares
// it — that is what makes the result cacheable and keeps two invocations of the
// task from observing each other's half-written tree.
fun registerSdkDriftTarget(target: SdkTarget): TaskProvider<JavaExec> {
    val clean =
        tasks.register<Delete>("${target.driftTaskName}Clean") {
            group = "build"
            description = "Clears the ${target.generator} drift output before it is regenerated."
            delete(driftOf(target))
        }
    return tasks.register<JavaExec>(target.driftTaskName) {
        group = "verification"
        description = "Generates a temporary ${target.generator} client in the build directory for drift checking."
        classpath = openApiGenerator
        mainClass.set("org.openapitools.codegen.OpenAPIGenerator")
        dependsOn(clean)
        val generatedOutput = driftOf(target)
        inputs.file(committedSpec).withPathSensitivity(PathSensitivity.NONE)
        inputs.file(ignoreFileOf(target))
        inputs.property("generatorMetadata", generatorMetadata)
        inputs.files(openApiGenerator)
            .withPropertyName("generatorClasspath")
            .withNormalizer(ClasspathNormalizer::class)
        outputs.dir(generatedOutput)
        // Belt and braces: the check is already free of any spec writer, so this
        // can only ever matter when a developer schedules a regeneration and a
        // check in one invocation.
        mustRunAfter(project(":service").tasks.named("exportOpenApi"))
        doFirst {
            generatedOutput.mkdirs()
            ignoreFileOf(target).copyTo(generatedOutput.resolve(".openapi-generator-ignore"))
        }
        args = listOf(
            "generate",
            "-i", committedSpec.absolutePath,
            "-g", target.generator,
            "-o", generatedOutput.absolutePath,
            "--additional-properties", generatorMetadata,
        )
        doLast {
            // The same normalization as the checked-in target; without it every
            // generated file would differ from its committed twin by trailing
            // whitespace and the comparison would be meaningless.
            normalizeGeneratedOutput(generatedOutput)
        }
    }
}

val generateSdkKotlinDrift = registerSdkDriftTarget(kotlinTarget)

fun generatedTree(root: File, excluded: Set<String>): FileTree {
    val tree = fileTree(root)
    tree.exclude(excluded.map { "**/$it/**" })
    tree.exclude(excluded.map { "**/$it" })
    return tree
}

fun generatedFiles(root: File, excluded: Set<String>): Map<String, ByteArray> {
    if (!root.isDirectory) return emptyMap()
    // An owned path may be a single segment (`build`), a nested path
    // (`src/test`), a glob on the first segment (`*.egg-info`) or carry a
    // trailing slash (`test/`); all of them must keep the Tanseki-owned files out
    // of the drift comparison.
    val owned = excluded.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.toSet()
    fun isOwned(relativePath: String): Boolean {
        val segments = relativePath.split('/')
        return owned.any { pattern ->
            when {
                pattern.startsWith("*") -> segments.any { it.endsWith(pattern.removePrefix("*")) }
                else ->
                    relativePath == pattern ||
                        relativePath.startsWith("$pattern/") ||
                        pattern in segments
            }
        }
    }
    return root
        .walkTopDown()
        .onEnter { it == root || it.name !in ignoredDirectories }
        .filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .filterNot(::isOwned)
        .associateWith { path -> root.resolve(path).readBytes() }
}

/**
 * Compares a freshly generated client against the checked-in one. `fresh` is
 * what the generator produces right now, so anything the committed tree has
 * that the generator no longer emits (`extra`) is as much drift as a missing
 * file: both mean `./gradlew generateSdk` has not been run and committed.
 */
class SdkDrift(
    val missing: List<String>,
    val extra: List<String>,
    val changed: List<String>
) {
    val clean: Boolean get() = missing.isEmpty() && extra.isEmpty() && changed.isEmpty()

    fun describe(): String = "(missing=$missing, extra=$extra, changed=$changed)"
}

fun driftBetween(
    checkedIn: File,
    fresh: File,
    target: SdkTarget
): SdkDrift {
    val before = generatedFiles(checkedIn, target.tansekiOwned)
    val after = generatedFiles(fresh, target.tansekiOwned)
    return SdkDrift(
        missing = (after.keys - before.keys).sorted(),
        extra = (before.keys - after.keys).sorted(),
        changed =
            after.keys
                .intersect(before.keys)
                .filter { !after.getValue(it).contentEquals(before.getValue(it)) }
                .sorted()
    )
}

val checkSdkGenerated = tasks.register("checkSdkGenerated") {
    group = "verification"
    description = "Fails when the checked-in generated SDKs differ from the committed OpenAPI spec."
    dependsOn(generateSdkKotlinDrift)
    // Every input the verdict depends on is declared: the spec the clients were
    // generated from, the generator itself, the ignore file that decides which
    // paths Tanseki owns, the freshly generated output, and — the part that is easy
    // to forget — the checked-in output under judgement. Without that last one a
    // hand-edit of a generated file would pass a stale up-to-date check.
    inputs.file(committedSpec).withPathSensitivity(PathSensitivity.NONE)
    inputs.files(openApiGenerator)
        .withPropertyName("generatorClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    sdkTargets.forEach { target ->
        inputs.file(ignoreFileOf(target)).withPropertyName("ignore-${target.generator}")
        // Unfiltered `inputs.dir` would include `build/`, `__pycache__/` and
        // `*.egg-info`, making this task permanently out of date.
        inputs.files(generatedTree(checkedInOf(target), target.tansekiOwned))
            .withPropertyName("checkedIn-${target.generator}")
        inputs.files(generatedTree(driftOf(target), target.tansekiOwned))
            .withPropertyName("drift-${target.generator}")
    }
    val report = layout.buildDirectory.file("reports/checkSdkGenerated/drift.txt")
    outputs.file(report).withPropertyName("driftReport")
    outputs.upToDateWhen { report.get().asFile.isFile }
    // Nothing above this gate writes to the work tree, so `mustRunAfter` is a
    // belt-and-braces ordering guard rather than a correctness requirement: it
    // only bites when a developer schedules `:service:exportOpenApi` and this
    // check in the same invocation. It is deliberately not a `dependsOn` —
    // depending on the writer is exactly the mutation this gate must not have.
    mustRunAfter(project(":service").tasks.named("exportOpenApi"))
    doLast {
        val failures = mutableListOf<String>()
        sdkTargets.forEach { target ->
            val name = target.generator.replaceFirstChar { it.uppercase() }
            val drift = driftBetween(checkedInOf(target), driftOf(target), target)
            if (!drift.clean) {
                failures += "$name generated output drift; run ./gradlew generateSdk ${drift.describe()}"
            }
        }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(if (failures.isEmpty()) "no drift\n" else failures.joinToString("\n") + "\n")
        check(failures.isEmpty()) {
            "Generated SDK output drifted; run ./gradlew generateSdk and commit the result.\n" +
                failures.joinToString("\n") { "  $it" }
        }
    }
}

val checkOpenApiAndSdkDrift = tasks.register("checkOpenApiAndSdkDrift") {
    group = "verification"
    description = "Verifies the committed OpenAPI spec and both generated SDKs against the routes."
    // The two halves answer different questions and both are required: does the
    // committed spec still match the routes, and do the committed clients match
    // the committed spec. Running only the second would let a stale spec hide
    // behind a consistently stale client.
    dependsOn(":service:checkOpenApi", checkSdkGenerated)
}



/** Reads a single value from a file that may carry `#` comment lines. */
fun File.readBudgetValue(): Int =
    readLines()
        .map { it.substringBefore('#').trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.toIntOrNull()
        ?: error("$path must contain a single integer value")

val detektBaselineBudget = tasks.register("detektBaselineBudget") {
    group = "verification"
    description = "Fails when the detekt baseline exceeds its budget or hides generated sources."
    val baseline = file("config/detekt/baseline.xml")
    val budgetFile = file("config/detekt/baseline-budget.txt")
    inputs.file(baseline)
    inputs.file(budgetFile)
    val report = layout.buildDirectory.file("reports/detektBaselineBudget/baseline.txt")
    outputs.file(report).withPropertyName("report")
    outputs.upToDateWhen { report.get().asFile.isFile }
    doLast {
        val budget = budgetFile.readBudgetValue()
        val entries = baseline.readLines().map { it.trim() }.filter { it.startsWith("<ID>") }
        val issues = entries.size
        check(issues <= budget) {
            "Detekt baseline has $issues issues; budget is $budget. Remove or re-budget the baseline deliberately."
        }
        // Generated sources are never analysed, so a baseline entry for one is
        // invisible debt that inflates the budget and hides real findings.
        val generated = entries.filter { it.contains("sdk/generated") }
        check(generated.isEmpty()) {
            "Detekt baseline must not cover generated SDK sources: ${generated.size} entry/entries. " +
                "Exclude ':sdk:' modules from the detektBaseline inputs."
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText("issues=$issues budget=$budget\n" + entries.joinToString("\n") + "\n")
        }
    }
}

// ---------------------------------------------------------------------------
// Apache-2.0, kept synchronized across every surface that restates it.
//
// The license is not one file: it is restated in the root `LICENSE`, in the
// packaging metadata of both generated SDKs, and in the OpenAPI document's
// `info.license`. Those copies drift silently — a regenerated client rewrites two
// of them from generator defaults — so the gate reads all of them and requires
// them to agree with each other and with the canonical `LICENSE` text.
// ---------------------------------------------------------------------------
val APACHE_2_0 = "Apache-2.0"
val APACHE_2_0_URL = "https://www.apache.org/licenses/LICENSE-2.0"

// ---------------------------------------------------------------------------
// Container contract.
//
// The image is the deployment artifact, and `trivy` in CI can only report on
// what the image actually contains. Two failure modes are cheaper to prevent
// than to scan for, so they are checked here instead:
//
//  - a floating base-image tag means the CVE gate silently re-scans a different
//    set of packages on every run, and a `latest`/`21` tag is not reproducible
//    at all. Every `FROM` must be pinned by digest.
//  - anything that reaches the build context is also sent to the daemon and kept
//    in a layer, so `.dockerignore` has to cover the sensitive shapes even on a
//    developer machine where those files exist. The required patterns are listed
//    here, so adding a new kind of local state without ignoring it fails the
//    build.
// ---------------------------------------------------------------------------
val containerFile = file("service/Dockerfile")
val dockerIgnoreFile = file(".dockerignore")

/** Shapes that must never enter the build context, however they are named. */
val requiredDockerIgnorePatterns =
    listOf(
        ".git", "**/.git", ".github", "**/build", ".gradle", "**/.gradle", ".kotlin",
        "**/__pycache__", ".venv", "**/.venv", ".python-sdk-venv", "**/*.egg-info",
        "**/*.db", "**/*.sqlite", "**/*.sqlite-*", "**/*.sock",
        // Derived vault state must never enter the image build context.
        "**/.tanseki", "**/logs",
        "**/*.log", ".env", "**/.env", "**/.env.*", "**/.netrc", "*.pem", "*.key",
        "*.p12", "*.jks", "secrets.properties", "local.properties", "**/.pijul", ".ci"
    )

val checkContainerContract = tasks.register("checkContainerContract") {
    group = "verification"
    description = "Fails when the container definition is unpinned, runs as root, or lets local state into the build context."
    inputs.file(containerFile)
    inputs.file(dockerIgnoreFile)
    val composeFile = layout.projectDirectory.file("compose.yaml")
    inputs.file(composeFile).withPropertyName("compose")
    inputs.property("requiredDockerIgnorePatterns", requiredDockerIgnorePatterns)
    val report = layout.buildDirectory.file("reports/checkContainerContract/container.txt")
    outputs.file(report).withPropertyName("report")
    outputs.upToDateWhen { report.get().asFile.isFile }
    doLast {
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        check(containerFile.isFile) { "service/Dockerfile is missing" }
        val dockerfile = containerFile.readText()
        val stages = Regex("""(?im)^\s*FROM\s+(\S+)(.*)$""").findAll(dockerfile).toList()
        check(stages.isNotEmpty()) { "service/Dockerfile declares no FROM" }
        stages.forEach { match ->
            val reference = match.groupValues[1]
            val stage = match.groupValues[2].substringBefore(" AS ").trim()
            if ("@" !in reference) {
                failures += "FROM $reference is not pinned by digest; a floating tag makes the CVE gate non-reproducible"
            } else if (!Regex("""@sha256:[0-9a-f]{64}$""").containsMatchIn(reference)) {
                failures += "FROM $reference does not end in a full sha256 digest"
            }
            notes += "base image: $reference${if (stage.isEmpty()) "" else " ($stage)"}"
        }
        val userDirective = Regex("""(?im)^\s*USER\s+(\S+)\s*$""").findAll(dockerfile).map { it.groupValues[1] }.toList()
        check(userDirective.isNotEmpty()) { "service/Dockerfile never drops root" }
        if (userDirective.last() == "root" || userDirective.last() == "0") {
            failures += "the final stage runs as ${userDirective.last()}; the image must end on a non-root USER"
        }
        if (!Regex("""(?im)^\s*HEALTHCHECK\b""").containsMatchIn(dockerfile)) {
            failures += "service/Dockerfile declares no HEALTHCHECK"
        }
        if (Regex("""(?im)^\s*ADD\s+https?://""").containsMatchIn(dockerfile)) {
            failures += "ADD of a remote URL bypasses the build context; use COPY with a pinned source"
        }
        if (Regex("""(?i)curl[^\n]*\|\s*(ba)?sh""").containsMatchIn(dockerfile)) {
            failures += "a remote script is piped into a shell; the command and its checksum must be pinned"
        }

        check(dockerIgnoreFile.isFile) { ".dockerignore is missing; the build context must be filtered" }
        val ignored =
            dockerIgnoreFile
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        requiredDockerIgnorePatterns.filterNot { it in ignored }.forEach {
            failures += ".dockerignore does not exclude '$it'"
        }

        // The compose stack inherits the Dockerfile's pinning rule: an image
        // referenced by a floating tag makes the local stack a different build from
        // the one CI containerised-tested, which is the whole reason the digests
        // above are pinned.
        if (composeFile.asFile.isFile) {
            val compose = composeFile.asFile.readText()
            val images =
                Regex("""(?m)^\s*image:\s*(\S+)""")
                    .findAll(compose)
                    .map { it.groupValues[1] }
                    .toList()
            check(images.isNotEmpty()) { "compose.yaml declares no services with an image" }
            images.filter { it.startsWith("tanseki-daemon") }.forEach {
                notes += "compose builds the daemon image from the Dockerfile: $it"
            }
            images.filterNot { it.startsWith("tanseki-daemon") }.forEach { reference ->
                if ("@" !in reference) {
                    failures += "compose.yaml image $reference is not pinned by digest"
                } else if (!Regex("""@sha256:[0-9a-f]{64}$""").containsMatchIn(reference)) {
                    failures += "compose.yaml image $reference does not end in a full sha256 digest"
                }
            }
            notes += "compose images: ${images.size} (${images.count { "@sha256:" in it }} digest-pinned)"
        } else {
            failures += "compose.yaml is missing"
        }

        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            (notes + "user: ${userDirective.last()}" + "dockerignore patterns: ${ignored.size}").joinToString("\n") + "\n"
        )
        check(failures.isEmpty()) {
            "Container contract violated:\n" + failures.joinToString("\n") { "  $it" }
        }
    }
}

val checkLicense = tasks.register("checkLicense") {
    group = "verification"
    description = "Fails when the Apache-2.0 LICENSE is missing, incomplete, or unclaimed, or out of step with the packaging metadata."
    val license = file("LICENSE")
    val kotlinMetadata = file("sdk/generated/kotlin/build.gradle.kts")
    inputs.file(license)
    inputs.file(kotlinMetadata)
    inputs.file(committedSpec).withPathSensitivity(PathSensitivity.NONE)
    doLast {
        check(license.isFile) { "LICENSE is missing" }
        val text = license.readText()
        check("Apache License" in text && "Version 2.0, January 2004" in text) {
            "LICENSE is not the Apache License 2.0"
        }
        check("Copyright [yyyy] [name of copyright owner]" !in text) {
            "LICENSE still contains the unfilled Apache-2.0 copyright placeholder"
        }
        val copyright = Regex("""(?m)^   Copyright (.+)$""").find(text)?.groupValues?.get(1)?.trim()
        check(!copyright.isNullOrBlank()) { "LICENSE has no copyright line" }
        // The appendix of the license text states the terms the grant is made
        // under; a LICENSE that dropped or altered them is not the Apache-2.0
        // text even if the title line is intact.
        check("Licensed under the Apache License, Version 2.0" in text) {
            "LICENSE is missing the Apache-2.0 grant in its appendix"
        }
        check(kotlinMetadata.isFile && APACHE_2_0 in kotlinMetadata.readText()) {
            "${kotlinMetadata.path} does not declare the $APACHE_2_0 license"
        }
        // The generated Kotlin jar advertises the license in its manifest; the
        // metadata above only proves the build script mentions it.
        val jarManifest = file("sdk/generated/kotlin/build.gradle.kts").readText()
        check("Implementation-License" in jarManifest && APACHE_2_0 in jarManifest) {
            "sdk/generated/kotlin/build.gradle.kts does not stamp the license into the jar manifest"
        }
        // The OpenAPI document is the contract a dependent reads; a spec that
        // names a different license contradicts the repository's own LICENSE.
        val spec = committedSpec.takeIf { it.isFile }?.readText().orEmpty()
        if (spec.isNotEmpty()) {
            check("\"name\": \"$APACHE_2_0\"" in spec || "\"$APACHE_2_0\"" in spec) {
                "docs/openapi.json does not declare the $APACHE_2_0 license in info.license"
            }
            check(APACHE_2_0_URL in spec) {
                "docs/openapi.json does not point info.license.url at $APACHE_2_0_URL"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One release identity.
//
// `gradle.properties:tansekiVersion` is the only editable copy. The HTTP schema,
// the generated client, and the MCP `initialize` response all restate it, and
// each restatement can silently go
// stale: a hard-coded literal survives a version bump, and a regenerated client
// rewrites its own copy from generator defaults. This gate reads every surface
// and requires all of them to equal the single source.
// ---------------------------------------------------------------------------
val checkVersionSource = tasks.register("checkVersionSource") {
    group = "verification"
    description = "Fails when a published version string has drifted from gradle.properties:tansekiVersion."
    val expected = tansekiVersion
    inputs.property("expectedVersion", expected)
    inputs.file(committedSpec).withPathSensitivity(PathSensitivity.NONE)
    inputs.files(fileTree("service/src/main/kotlin") { include("**/*.kt") })
        .withPropertyName("serviceSources")
    val report = layout.buildDirectory.file("reports/checkVersionSource/versions.txt")
    outputs.file(report).withPropertyName("report")
    outputs.upToDateWhen { report.get().asFile.isFile }
    doLast {
        val failures = mutableListOf<String>()
        val observed = linkedMapOf<String, String>()

        fun expect(surface: String, actual: String?, hint: String) {
            val value = actual ?: "<missing>"
            observed[surface] = value
            if (value != expected) failures += "$surface reports '$value', expected '$expected'. $hint"
        }

        val spec = committedSpec.takeIf { it.isFile }?.readText().orEmpty()
        if (spec.isNotEmpty()) {
            expect(
                "docs/openapi.json info.version",
                Regex(""""version"\s*:\s*"([^"]+)"""").find(spec)?.groupValues?.get(1),
                "Run ./gradlew :service:exportOpenApi"
            )
        }

        // A literal in the MCP server's `serverInfo` call is the one place a
        // second version source can hide, because the MCP handshake publishes it
        // to every model that connects.
        val hardCoded =
            fileTree("service/src/main/kotlin") { include("**/*.kt") }
                .filter { it.isFile }
                .flatMap { source ->
                    Regex("""serverInfo\(\s*"[^"]+"\s*,\s*"([^"]+)"\s*\)""")
                        .findAll(source.readText())
                        .map { match -> "${source.relativeTo(projectDir)}:${match.groupValues[1]}" }
                }
                .toList()
        hardCoded.forEach {
            failures += "$it hard-codes the MCP server version; read it from gokorei.tanseki.service.TansekiVersion instead"
        }

        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("source=gradle.properties:tansekiVersion=$expected")
                observed.forEach { (surface, value) -> appendLine("$surface=$value") }
            }
        )
        check(failures.isEmpty()) {
            "Release version drift; set gradle.properties:tansekiVersion and regenerate:\n" +
                failures.joinToString("\n") { "  $it" }
        }
    }
}


// Integration suites that need Docker are optional by default so `./gradlew
// build` works on a laptop, and mandatory in the dedicated CI job, which passes
// `-Ptanseki.runIntegrationTests=true`.
val checkUnexpectedTestSkips =
    tasks.register("checkUnexpectedTestSkips") {
        group = "verification"
        description =
            "Fails when a test suite skipped entirely without being declared in " +
                "config/quality/expected-test-skips.csv."
        // Not every subproject is a JVM module: `:adapters` and friends are
        // containers, and asking them for a `test` task fails configuration outright.
        val testTasks = subprojects.filter { it.tasks.findByName("test") != null }.map { "${it.path}:test" }
        dependsOn(testTasks)
        val ledger = layout.projectDirectory.file("config/quality/expected-test-skips.csv")
        inputs.file(ledger).withPropertyName("ledger")
        val report = layout.buildDirectory.file("reports/checkUnexpectedTestSkips/skips.csv")
        outputs.file(report).withPropertyName("report")
        outputs.upToDateWhen { report.get().asFile.isFile }
        doLast {
            val expected =
                ledger.asFile
                    .readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .associate { line ->
                        val suite = line.substringBefore(',').trim()
                        val reason = line.substringAfter(',', "").trim()
                        require(suite.isNotEmpty() && reason.isNotEmpty()) {
                            "config/quality/expected-test-skips.csv: `$line` needs a suite and a reason"
                        }
                        suite to reason
                    }

            // Every suite that produced results, and how many of its tests ran. A
            // suite where every test skipped did not run, whatever it is called.
            val ran = mutableMapOf<String, Int>()
            val skippedEntirely = mutableSetOf<String>()
            subprojects.forEach { module ->
                val results = module.layout.buildDirectory.dir("test-results/test").get().asFile
                if (!results.isDirectory) return@forEach
                results.listFiles { file -> file.extension == "xml" }?.forEach { result ->
                    val text = result.readText()
                    val suite =
                        Regex("""name="([^"]+)"""")
                            .find(text)
                            ?.groupValues
                            ?.get(1)
                            ?: return@forEach
                    val tests = Regex("""tests="(\d+)"""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val skipped =
                        Regex("""skipped="(\d+)"""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (tests == 0) return@forEach
                    if (skipped >= tests) skippedEntirely += suite else ran.merge(suite, tests, Int::plus)
                }
            }

            val unexpected = skippedEntirely - expected.keys
            val stale = expected.keys.filter { it in ran }

            val output = report.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                buildString {
                    appendLine("suite,state,reason")
                    expected.keys.sorted().forEach {
                        appendLine("$it,${if (it in stale) "declared-but-ran" else "skipped-as-declared"}," + expected.getValue(it).replace(',', ';'))
                    }
                    unexpected.sorted().forEach { appendLine("$it,unexpectedly-skipped,") }
                }
            )

            check(unexpected.isEmpty()) {
                "These suites skipped entirely and are not declared in " +
                    "config/quality/expected-test-skips.csv: ${unexpected.sorted()}. " +
                    "Either the suite gained an assumption or a @Disabled and now " +
                    "silently runs nothing, or it genuinely needs an external " +
                    "dependency and belongs in the ledger with a reason."
            }
            if (stale.isNotEmpty()) {
                logger.lifecycle(
                    "checkUnexpectedTestSkips: ${stale.size} declared skip(s) ran anyway " +
                        "(${stale.sorted()}); they can be removed from the ledger."
                )
            }
            logger.lifecycle(
                "checkUnexpectedTestSkips: ${skippedEntirely.size} suite(s) skipped, all declared; " +
                    "${ran.size} suite(s) ran."
            )
        }
    }

val checkRequiredIntegrationCoverage = tasks.register("checkRequiredIntegrationCoverage") {
    group = "verification"
    description = "Fails when a required Postgres/Meilisearch integration suite did not run."
    val required = mapOf(
        "gokorei.tanseki.adapters.postgres.PostgresContextStoreContractTest" to ":adapters:context-postgres",
        "gokorei.tanseki.adapters.postgres.PostgresContextStoreRetentionTest" to ":adapters:context-postgres",
        "gokorei.tanseki.adapters.postgres.PostgresContextStorePathTest" to ":adapters:context-postgres",
        "gokorei.tanseki.adapters.postgres.PostgresSchemaMigrationTest" to ":adapters:context-postgres",
        // `MeiliLookupContractTest` *is* the Meilisearch container coverage: it
        // boots a pinned `getmeili/meilisearch` container and runs the shared
        // Lookup contract against it. Listing a second class here would name a
        // suite that does not exist, and the gate would then fail on every run
        // with integration tests enforced rather than on a real coverage gap.
        "gokorei.tanseki.adapters.meili.MeiliLookupContractTest" to ":adapters:lookup-meili",
        "gokorei.tanseki.composition.ServerCompositionTest" to ":composition",
        "gokorei.tanseki.cli.transfer.CrossProfileTransferE2eTest" to ":cli",
        "gokorei.tanseki.service.api.ServerProfileE2eTest" to ":service",
    )
    val enforced = providers.gradleProperty("tanseki.runIntegrationTests").getOrElse("false").toBoolean()
    val testTasks = required.values.distinct().map { "$it:test" }
    dependsOn(testTasks)
    inputs.property("requiredSuites", required.keys.sorted().joinToString(","))
    inputs.property("enforced", enforced)
    val report = layout.buildDirectory.file("reports/checkRequiredIntegrationCoverage/suites.txt")
    outputs.file(report).withPropertyName("report")
    outputs.upToDateWhen { report.get().asFile.isFile }
    doLast {
        val executed = mutableMapOf<String, Int>()
        subprojects.forEach { module ->
            val results = module.layout.buildDirectory.dir("test-results/test").get().asFile
            if (!results.isDirectory) return@forEach
            results.listFiles { file -> file.extension == "xml" }?.forEach { result ->
                val text = result.readText()
                val suite = Regex("""name="([^"]+)"""").find(text)?.groupValues?.get(1) ?: return@forEach
                val tests = Regex("""tests="(\d+)"""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val skipped = Regex("""skipped="(\d+)"""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (skipped >= tests && tests > 0) return@forEach
                executed.merge(suite, tests, Int::plus)
            }
        }
        val missing = required.keys.filter { (executed[it] ?: 0) == 0 }
        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(required.keys.sorted().joinToString("\n") { "$it,${executed[it] ?: 0}" })
        if (enforced) {
            check(missing.isEmpty()) {
                "Required integration coverage is missing: ${missing.sorted()}. " +
                    "Run with -Ptanseki.runIntegrationTests=true on a host with Docker; these suites must not be skipped."
            }
        } else {
            logger.lifecycle(
                "checkRequiredIntegrationCoverage: not enforced; ${missing.size} suite(s) skipped. " +
                    "Pass -Ptanseki.runIntegrationTests=true to require them."
            )
        }
    }
}

// ---------------------------------------------------------------------------
// JVM coverage. JaCoCo is wired centrally so every JVM module reports with the
// same pinned tool version, including the generated SDK, which deliberately
// skips the convention plugin. `coverageBudget` then enforces the per-module
// line-coverage floors recorded in config/quality/jvm-coverage-budget.csv.
//
// The budget is module-aware in three ways: every JVM module with main sources
// must be listed (a missing row is a failure, not a silent pass), each row
// carries its own floor, and container-backed modules are marked `integration`
// so their floor only applies when the Docker suites actually ran.
// ---------------------------------------------------------------------------
val jacocoVersion = file("config/quality/jacoco-version.txt").readText().trim()
val coverageBudgetFile = file("config/quality/jvm-coverage-budget.csv")

/** One row of the coverage budget. */
class CoverageRule(
    val module: String,
    val floor: Int,
    val integrationOnly: Boolean
)

val coverageRules =
    coverageBudgetFile
        .readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val cells = line.split(',').map { it.trim() }
            require(cells.size in 2..3) { "Malformed coverage budget row: '$line'" }
            CoverageRule(
                module = cells[0],
                floor = cells[1].toInt(),
                integrationOnly = cells.getOrNull(2)?.equals("integration", ignoreCase = true) ?: false
            )
        }

val coverageModules = mutableListOf<String>()
val integrationEnforced = providers.gradleProperty("tanseki.runIntegrationTests").getOrElse("false").toBoolean()

/** Locale-independent decimal formatting; a comma here would corrupt the CSV report. */
fun Double.format2(): String = String.format(java.util.Locale.ROOT, "%.2f", this)

subprojects {
    plugins.withId("java") {
        if (file("src/main/kotlin").isDirectory) coverageModules += path
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> { toolVersion = jacocoVersion }
        tasks.named<JacocoReport>("jacocoTestReport") {
            // Reading `classDirectories.files` below realizes the collection at
            // configuration time, and Gradle 9 then rejects the task for consuming
            // processResources' output with no declared dependency on it. It cannot
            // infer one, because the realization happens before the task graph exists.
            // Declaring it here is what the inference would have concluded anyway.
            dependsOn("classes")
            // A shared contract suite is exercised by the adapters that extend it,
            // not by the module that hosts it, so measuring its coverage from the
            // host charges the module for lines its own tests cannot run. The
            // current value is snapshotted to plain files first: setFrom mutates
            // the live collection it is reading from.
            val snapshot = classDirectories.files
            classDirectories.setFrom(
                files(
                    snapshot.map { dir ->
                        fileTree(dir) { exclude("**/*Contract*", "**/*Contract.*") }
                    }
                )
            )
            reports {
                xml.required.set(false)
                html.required.set(false)
                csv.required.set(true)
                csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.csv"))
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn("jacocoTestReport") }
    }
}

/** Line coverage of a module, from its pinned JaCoCo CSV. `null` when there is no report yet. */
fun File.readLineCoveragePercent(): Double? {
    if (!isFile) return null
    val lines = readLines().filter { it.isNotBlank() }
    if (lines.size < 2) return null
    val header = lines.first().split(',')
    val missed = header.indexOf("LINE_MISSED")
    val covered = header.indexOf("LINE_COVERED")
    require(missed >= 0 && covered >= 0) { "$path is not a JaCoCo CSV report" }
    var lineMissed = 0L
    var lineCovered = 0L
    lines.drop(1).forEach { row ->
        val cells = row.split(',')
        lineMissed += cells[missed].toLong()
        lineCovered += cells[covered].toLong()
    }
    val total = lineMissed + lineCovered
    return if (total == 0L) 0.0 else lineCovered * 100.0 / total
}

val coverageBudget = tasks.register("coverageBudget") {
    group = "verification"
    description = "Fails when a module's line coverage falls below its recorded floor."
    coverageRules.forEach { rule -> dependsOn("${rule.module}:jacocoTestReport") }
    inputs.file(coverageBudgetFile)
    inputs.property("jacocoVersion", jacocoVersion)
    inputs.property("integrationEnforced", integrationEnforced)
    inputs.property("coveredModules", provider { coverageModules.sorted().joinToString(",") })
    val report = layout.buildDirectory.file("reports/coverageBudget/coverage.csv")
    outputs.file(report).withPropertyName("coverageReport")
    outputs.upToDateWhen { report.get().asFile.isFile }
    doLast {
        val failures = mutableListOf<String>()
        val rows = mutableListOf<String>()

        // Every JVM module that produces classes must carry a budget row, and
        // every row must name a real module: both directions fail, so the file
        // cannot rot into a list of modules nobody looks at.
        val listed = coverageRules.map { it.module }.toSet()
        (coverageModules.sorted() - listed).forEach { module ->
            failures += "$module has main sources but no row in ${coverageBudgetFile.name}"
        }
        (listed - coverageModules.toSet()).forEach { module ->
            failures += "$module has a row in ${coverageBudgetFile.name} but reports no JaCoCo coverage"
        }

        coverageRules.sortedBy { it.module }.forEach { rule ->
            val csv = project(rule.module).layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.csv").get().asFile
            val percent = csv.readLineCoveragePercent()
            if (percent == null) {
                failures += "no JaCoCo report for ${rule.module}; run ./gradlew ${rule.module}:test ${rule.module}:jacocoTestReport"
                return@forEach
            }
            val gated = !rule.integrationOnly || integrationEnforced
            val status = if (gated) "gated" else "reported (integration suites not required here)"
            rows +=
                "${rule.module},${percent.format2()},${rule.floor},${if (rule.integrationOnly) "integration" else "always"},$status"
            if (gated && percent + 0.005 < rule.floor) {
                failures +=
                    "${rule.module} line coverage ${percent.format2()}% is below its ${rule.floor}% floor"
            }
        }

        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "module,lineCoveragePercent,floorPercent,mode,status\n" + rows.joinToString("\n") + "\n"
        )
        check(failures.isEmpty()) {
            "JVM coverage budget not met (JaCoCo $jacocoVersion):\n" + failures.joinToString("\n") { "  $it" }
        }
    }
}

tasks.named("check") {
    group = "verification"
    description =
        "Runs repository verification: spec/SDK drift, lint, license, version, coverage, " +
            "test-skip accounting."
    dependsOn(
        checkOpenApiAndSdkDrift,
        checkContainerContract,
        checkLicense,
        checkVersionSource,
        checkRequiredIntegrationCoverage,
        checkUnexpectedTestSkips,
        detektBaselineBudget,
        checkNoTrailingWhitespace,
        coverageBudget,
        lint,
    )
}
