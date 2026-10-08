import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import tanseki.buildlogic.CheckDependencyRulesTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// One toolchain for every module. Compilation, test execution, and the Docker
// image all target JDK 21 regardless of the JVM that launches Gradle; Gradle
// provisions it when the host has no matching JDK.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    add("testImplementation", platform(catalog.findLibrary("junit-bom").get()))
    add("testImplementation", catalog.findLibrary("junit-jupiter").get())
    add("testRuntimeOnly", catalog.findLibrary("junit-platform-launcher").get())
}

// Postgres and Meilisearch suites are opt-in so a Docker-less laptop stays
// usable, but CI must run them, and `checkRequiredIntegrationCoverage` fails if
// they were skipped there.
//
// The real-pijul suite is deliberately NOT folded into that property: it needs a
// `pijul` binary and a signing identity rather than Docker, and the integration
// job installs neither, so binding it here would turn "no binary" into a failure
// there. It is enabled by `TANSEKI_RUN_PIJUL_INTEGRATION=true`, which the dedicated
// `pijul` CI job sets after installing the pinned release.
val runIntegrationTests =
    providers.gradleProperty("tanseki.runIntegrationTests").map(String::toBoolean).getOrElse(false)

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (runIntegrationTests) {
        environment("TANSEKI_RUN_MEILI_INTEGRATION", "true")
    }
}

// ---------------------------------------------------------------------------
// Architecture dependency rules
//
//  - core:domain        -> (none)
//  - core:ports         -> core:domain
//  - core:application   -> core:domain, core:ports
//  - adapters:*         -> core:domain, core:ports, core:text
//    (never core:application: orchestration lives above adapters; generic
//    retry/error utilities live in core:ports so adapters stay replaceable)
//  - testkit            -> core:ports
//  - sdk:*              -> (none; wire contract only)
//  - service / cli      -> core:* + adapters:*
//  - nothing may depend on service/cli
//  - testkit may only be used from test configurations
// ---------------------------------------------------------------------------
val modulePath = project.path

fun adapterPaths(): Set<String> =
    rootProject.allprojects
        .map { it.path }
        .filter { it.startsWith(":adapters:") }
        .toSet()

fun allowedProjects(path: String): Set<String> = when {
    path == ":core:domain" -> emptySet()
    path == ":core:text" -> setOf(":core:domain")
    path == ":core:ports" -> setOf(":core:domain")
    path == ":core:application" -> setOf(":core:domain", ":core:ports", ":core:text")
    path.startsWith(":adapters:") ->
        setOf(":core:domain", ":core:ports", ":core:text")
    path == ":testkit" -> setOf(":core:ports", ":core:text")
    path.startsWith(":sdk:") -> emptySet()
    path == ":composition" ->
        setOf(":core:domain", ":core:ports", ":core:text") + adapterPaths()
    path == ":service" || path == ":cli" ->
        setOf(":core:domain", ":core:ports", ":core:application", ":core:text", ":composition") + adapterPaths()
    else -> emptySet()
}

/**
 * Declaration configurations Gradle derived for the project's test-capable
 * source sets (`test`, plus `testFixtures` where `java-test-fixtures` is used).
 *
 * Computed from the real source-set names rather than pattern-matching arbitrary
 * configuration names (the old regex could swallow any configuration whose name
 * merely started with "test", and could not see source sets created by plugins).
 * Only declaration configurations carry project dependencies directly, so this is
 * exactly the set the dependency rules need to classify as test-only.
 */
val testDeclarationConfigurations: Set<String> by lazy {
    sourceSets
        .matching { it.name == "test" || it.name.startsWith("test") }
        .flatMap { sourceSet ->
            listOf("Implementation", "CompileOnly", "RuntimeOnly", "AnnotationProcessor")
                .map { suffix -> "${sourceSet.name}$suffix" }
        }
        .toSet()
}

// Everything the check reads is a typed input on a real task type — populated
// here once all projects have configured, when the architecture rules and the
// full project/configuration set are final — so the task action never touches a
// build-script object and stays serializable under the configuration cache.
val checkDependencyRules = tasks.register<CheckDependencyRulesTask>("checkDependencyRules") {
    group = "verification"
    description = "Fails the build if a project dependency violates the architecture rules."
}

gradle.projectsEvaluated {
    checkDependencyRules.configure {
        module.set(modulePath)
        allowed.set(allowedProjects(modulePath))
        testConfigurations.set(testDeclarationConfigurations)
        val known = rootProject.allprojects.map { it.path }.toSet()
        dependenciesByConfiguration.set(
            configurations.associate { configuration ->
                configuration.name to
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .map { it.path }
                        .filter { it in known }
            }
        )
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkDependencyRules)
}

// ---------------------------------------------------------------------------
// ktlint + detekt via their standalone CLIs.
//
// Using the CLIs (JavaExec) instead of the Gradle plugins keeps the build
// independent of plugin/Gradle compatibility and runs the same tools as CI.
//
// `lint` is the only entry point: it schedules each pass once per module, and
// `check` depends on `lint` rather than on the passes. Naming `ktlintCheck` or
// `detekt` next to `build` schedules the same work from a second direction and
// makes a CI log look like two passes ran when only one did.
// ---------------------------------------------------------------------------
val ktlintCliConf = configurations.create("ktlintCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val detektCliConf = configurations.create("detektCli") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    add("ktlintCli", catalog.findLibrary("ktlint-cli").get())
    add("detektCli", catalog.findLibrary("detekt-cli").get())
}

fun kotlinSourceDirs(): List<File> =
    listOf("src/main/kotlin", "src/test/kotlin").map(::file).filter { it.exists() }

/**
 * Runs a lint CLI exactly once per module and records a stamp so a rerun with
 * unchanged sources, arguments, and tool skips the pass instead of repeating it.
 * The stamp is written only after the CLI exits successfully, so a failing lint
 * run is never cached as a pass, and the arguments are an input because the
 * detekt pass embeds the shared baseline path.
 */
fun registerLint(
    taskName: String,
    taskDescription: String,
    toolClasspath: Configuration,
    toolMainClass: String,
    sourceDirs: () -> List<File>,
    baseArguments: () -> List<String>
) = tasks.register<JavaExec>(taskName) {
    group = "verification"
    description = taskDescription
    classpath = toolClasspath
    mainClass.set(toolMainClass)
    val stamp = layout.buildDirectory.file("reports/$taskName/$taskName.stamp")
    val sources = sourceDirs()
    onlyIf { sources.isNotEmpty() }
    inputs.files(sources)
    inputs.files(toolClasspath)
        .withPropertyName("toolClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    inputs.property("arguments", provider { baseArguments() })
    outputs.file(stamp).withPropertyName("stamp")
    outputs.upToDateWhen { stamp.get().asFile.isFile }
    args = baseArguments()
    doLast {
        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

val ktlintCheck =
    registerLint(
        taskName = "ktlintCheck",
        taskDescription = "Checks Kotlin formatting with ktlint.",
        toolClasspath = ktlintCliConf,
        toolMainClass = "com.pinterest.ktlint.Main",
        sourceDirs = ::kotlinSourceDirs,
        baseArguments = { kotlinSourceDirs().map { it.absolutePath } }
    )

tasks.register<JavaExec>("ktlintFormat") {
    group = "verification"
    description = "Formats Kotlin sources with ktlint."
    classpath = ktlintCliConf
    mainClass.set("com.pinterest.ktlint.Main")
    val dirs = kotlinSourceDirs()
    onlyIf { dirs.isNotEmpty() }
    args = dirs.map { it.absolutePath } + listOf("-F")
}

val detekt =
    registerLint(
        taskName = "detekt",
        taskDescription = "Runs detekt static analysis.",
        toolClasspath = detektCliConf,
        toolMainClass = "io.gitlab.arturbosch.detekt.cli.Main",
        sourceDirs = { listOf(file("src/main/kotlin")).filter { it.exists() } },
        baseArguments = {
            listOf(
                "--input", file("src/main/kotlin").absolutePath,
                "--config", rootProject.file("config/detekt/detekt.yml").absolutePath,
                "--baseline", rootProject.file("config/detekt/baseline.xml").absolutePath,
                "--build-upon-default-config"
            )
        }
    )

// One aggregate entry point per module so callers never hand-list lint tasks and
// never name the same pass twice in a single invocation.
val lint = tasks.register("lint") {
    group = "verification"
    description = "Runs ktlint and detekt once for this module."
    dependsOn(ktlintCheck, detekt)
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(lint)
}
