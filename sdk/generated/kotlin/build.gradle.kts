import org.gradle.api.GradleException
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Generated Kotlin client for the Tanseki `/v1` seam, produced by
// `./gradlew generateSdkKotlin` from the code-first spec. It deliberately does
// NOT use the `tanseki.kotlin-library` conventions: the sources are machine-written
// and must not be reformatted or linted. `build.gradle.kts` and `src/test/` are
// preserved across regeneration via `.openapi-generator-ignore`.
//
// What it does take from the conventions is the part that is about *this* build
// script rather than about the generated sources:
//
//  - the JDK 21 toolchain and JVM target, so the client is compiled and tested on
//    the same JVM as the daemon it talks to, whatever JDK launched Gradle. Before
//    this was pinned, the one module a dependent actually consumes was the one
//    module that silently compiled against whatever the host happened to have;
//  - the dependency-boundary rule, restated below, because `:sdk:*` reaches
//    `:service` and the adapters for its tests only and must keep doing so only
//    in a test configuration.
//
// group and version come from the single source in `gradle.properties`
// (`tansekiGroup` / `tansekiVersion`), which also stamps `docs/openapi.json` and the
// generated Python packaging metadata.
group = rootProject.group
version = rootProject.version

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The generated smoke test boots a real `StoreApiServer`; the test JVM must
    // therefore be on the same JDK the distribution targets.
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}

// ---------------------------------------------------------------------------
// Dependency boundary.
//
// The client is a wire-contract module: it may not depend on any other Tanseki
// module to *compile*. It legitimately depends on the daemon and the adapters so
// its integration test can stand up a real server, and that is the only reason
// the dependency is allowed to exist at all — so the rule is enforced on the
// configuration, not merely noted in a comment.
// ---------------------------------------------------------------------------
val modulePath = project.path

// The generated client compiles against the wire only: no non-test configuration
// may depend on a Tanseki module. Realized at configuration time into task inputs,
// so the check stays serializable under the configuration cache.
val checkDependencyRules = tasks.register("checkDependencyRules") {
    group = "verification"
    description = "Fails the build if the generated client depends on Tanseki outside a test configuration."
    val declared =
        provider {
            configurations.associate { configuration ->
                configuration.name to
                    configuration.dependencies
                        .withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                        .map { it.path }
            }
        }
    val testConfigs =
        provider {
            sourceSets
                .matching { it.name == "test" || it.name.startsWith("test") }
                .flatMap { sourceSet ->
                    listOf("Implementation", "CompileOnly", "RuntimeOnly", "AnnotationProcessor")
                        .map { suffix -> "${sourceSet.name}$suffix" }
                }
                .toSet()
        }
    inputs.property("module", modulePath)
    inputs.property("declared", declared)
    inputs.property("testConfigs", testConfigs)
    doLast {
        val properties = inputs.properties
        val module = properties["module"] as String
        @Suppress("UNCHECKED_CAST")
        val declaredMap = properties["declared"] as Map<String, List<String>>
        @Suppress("UNCHECKED_CAST")
        val testSet = properties["testConfigs"] as Set<String>
        val violations =
            declaredMap.flatMap { (configuration, targets) ->
                if (configuration in testSet) {
                    emptyList()
                } else {
                    targets.map { "$module ($configuration) -> $it: the client compiles against the wire only" }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Dependency-rule violations in $module:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkDependencyRules)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(libs.moshi.kotlin)
    implementation(libs.moshi.adapters)
    implementation(libs.okhttp)

    testImplementation(project(":service"))
    testImplementation(project(":core:application"))
    testImplementation(project(":testkit"))
    testImplementation(project(":adapters:lookup-lucene"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.server.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest.attributes(
        "Implementation-Title" to "Tanseki API client",
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "The Tanseki Authors",
        "Implementation-License" to "Apache-2.0"
    )
}
