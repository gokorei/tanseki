plugins {
    id("tanseki.kotlin-serialization")
    application
}

application {
    applicationName = "tanseki-daemon"
    mainClass.set("gokorei.tanseki.service.DaemonMainKt")
}

// Second entry point in the same distribution: `tanseki-mcp` (stdio MCP server,
// logs to stderr). Shares the daemon's runtime classpath.
val mcpStartScripts = tasks.register<org.gradle.jvm.application.tasks.CreateStartScripts>("mcpStartScripts") {
    applicationName = "tanseki-mcp"
    mainClass.set("gokorei.tanseki.service.mcp.McpMainKt")
    outputDir = layout.buildDirectory.dir("mcp-scripts").get().asFile
    classpath = tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts").get().classpath
}

// Apache-2.0 requires the licence text to accompany the distributed binary,
  // and the MIT notice for the TOON library it embeds is a condition of that
  // grant. Both are copied into the distribution root so they ship with the
  // tarball, the zip, and `installDist` rather than only in the source tree.
  val legalNotices = listOf(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))

  distributions {
      main {
          contents {
              from(mcpStartScripts) { into("bin") }
              from(legalNotices)
          }
      }
  }

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:text"))
    implementation(project(":composition"))
    implementation(project(":adapters:pijul-cli"))
    implementation(project(":adapters:context-file"))
    implementation(project(":adapters:lookup-lucene"))
    implementation(project(":adapters:watcher-os"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.openapi)
    implementation(libs.schema.kenerator.core)
    implementation(libs.schema.kenerator.swagger)
    implementation(libs.mcp)
    implementation(libs.jtoon)

    // Patched BOMs for the HIGH/CRITICAL CVEs the image scan enforces (Trivy,
    // severity HIGH,CRITICAL with exit-code 1). The vulnerable lines arrive
    // transitively — jackson 2.x and netty via ktor-openapi/swagger, jackson
    // 3.x via jtoon — so the platforms lift them to their fixed releases
    // without touching any direct declaration. Plain platforms, not enforced
    // ones: conflict resolution takes the highest version, which is the fix.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.7"))
    implementation(platform("tools.jackson:jackson-bom:3.2.3"))
    implementation(platform("io.netty:netty-bom:4.2.17.Final"))

    testImplementation(project(":testkit"))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}

// ---------------------------------------------------------------------------
// One release identity.
//
// `gradle.properties` is the only editable copy of the version. It reaches the
// runtime two ways: as the `tanseki.version` system property for the Gradle-driven
// paths, and as a generated classpath resource for a distribution launched by its
// own start script, which has no system property to inherit. `TansekiVersion` reads
// both, so the OpenAPI `info.version` and the MCP `initialize` response cannot
// drift onto separate copies. `checkVersionSource` (root) fails if either does.
// ---------------------------------------------------------------------------
val generateVersionResource = tasks.register("generateVersionResource") {
    group = "build"
    description = "Writes the release version onto the service runtime classpath."
    val outputDir = layout.buildDirectory.dir("generated/tanseki-version")
    val version = rootProject.version.toString()
    inputs.property("tansekiVersion", version)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().file("tanseki-version.properties").asFile
        target.parentFile.mkdirs()
        target.writeText("version=$version\n")
    }
}

sourceSets.named("main") { resources.srcDir(generateVersionResource) }

// ---------------------------------------------------------------------------
// Code-first OpenAPI: the /v1 routes are the source of truth.
//
// `generateOpenApi` exports the spec into the build directory and never touches
// the committed file, so verification tasks can run it without mutating the
// work tree or racing each other. `exportOpenApi` is the only writer of
// `docs/openapi.json`; `checkOpenApi` compares the committed file with the
// build-directory export and is wired into `check`.
// ---------------------------------------------------------------------------
val committedOpenApi = rootProject.layout.projectDirectory.file("docs/openapi.json")
val exportedOpenApi = layout.buildDirectory.file("openapi/openapi.json")

val generateOpenApi = tasks.register<JavaExec>("generateOpenApi") {
    group = "build"
    description = "Exports the code-first OpenAPI spec into the build directory."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("gokorei.tanseki.service.api.OpenApiExportKt")
    val spec = exportedOpenApi.get().asFile
    args(spec.absolutePath)
    jvmArgs("-Dtanseki.version=${rootProject.version}")
    inputs.property("tansekiVersion", rootProject.version.toString())
    inputs.files(sourceSets["main"].output, sourceSets["test"].output)
    outputs.file(spec)
    doFirst { spec.parentFile.mkdirs() }
}

val exportOpenApi = tasks.register("exportOpenApi") {
    group = "build"
    description = "Exports the code-first OpenAPI spec to docs/openapi.json."
    dependsOn(generateOpenApi)
    val fresh = exportedOpenApi.get().asFile
    val committed = committedOpenApi.asFile
    inputs.file(fresh)
    outputs.file(committed)
    doLast {
        committed.parentFile.mkdirs()
        committed.writeText(fresh.readText())
    }
}

val checkOpenApi = tasks.register("checkOpenApi") {
    group = "verification"
    description = "Fails when the committed OpenAPI spec drifts from the routes."
    dependsOn(generateOpenApi)
    // `exportOpenApi` is the only writer of the committed spec. When a single
    // invocation runs both (e.g. `./gradlew build exportOpenApi`), the gate must
    // compare against the file the export just wrote, not against the state that
    // was on disk when the task graph was built — otherwise a legitimate export
    // is reported as drift.
    mustRunAfter(exportOpenApi)
    val fresh = exportedOpenApi.get().asFile
    val committed = committedOpenApi.asFile
    inputs.file(fresh)
    inputs.file(committed)
    doLast {
        check(committed.isFile) { "docs/openapi.json is missing; run ./gradlew :service:exportOpenApi" }
        check(committed.readText() == fresh.readText()) {
            "docs/openapi.json is out of date; run ./gradlew :service:exportOpenApi"
        }
    }
}

tasks.named("check") { dependsOn(checkOpenApi) }
